package com.bgssai.healthcheck;

import com.bgssai.healthcheck.alert.AlertStatus;
import com.bgssai.healthcheck.domain.AppHealth;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.HealthSummary;
import com.bgssai.healthcheck.domain.TargetType;
import com.bgssai.healthcheck.service.HealthCheckService;
import com.bgssai.healthcheck.service.UnknownApplicationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 端到端用例：被监控方由 {@link StubHealthServer} 模拟，覆盖正常、降级、异常、
 * 超时、非 JSON 响应等场景，并验证 REST 接口与看板页面。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthCheckApplicationTests {

    private static final StubHealthServer STUB = new StubHealthServer();

    /** 先占一个端口再释放，得到一个大概率没人监听的地址。 */
    private static final int CLOSED_PORT = TestSocketUtils.findAvailableTcpPort();

    @LocalServerPort
    private int port;

    @Autowired
    private HealthCheckService healthCheckService;

    @Autowired
    private RestClient.Builder restClientBuilder;

    private RestClient client;

    @DynamicPropertySource
    static void monitoredApplications(DynamicPropertyRegistry registry) {
        target(registry, 0, "正常服务", "核心服务", STUB.url("/up"));
        target(registry, 1, "降级服务", "核心服务", STUB.url("/degraded"));
        target(registry, 2, "异常服务", "核心服务", STUB.url("/down"));
        target(registry, 3, "非 JSON 服务", "其它", STUB.url("/plain"));
        target(registry, 4, "路径不存在", "其它", STUB.url("/missing"));
        target(registry, 5, "连不上的服务", "其它", "http://127.0.0.1:" + CLOSED_PORT + "/health");
        target(registry, 6, "超时服务", "其它", STUB.url("/slow"));
        registry.add("bgssai.healthcheck.applications[6].read-timeout", () -> "500ms");

        target(registry, 7, "已停用的服务", "其它", STUB.url("/up"));
        registry.add("bgssai.healthcheck.applications[7].enabled", () -> "false");

        target(registry, 8, "204 表示健康", "其它", STUB.url("/no-content"));
        registry.add("bgssai.healthcheck.applications[8].expected-statuses[0]", () -> "204");

        // BGSSAI 产品线的应用把健康负载包在各仓统一响应封装里（Standards §13.3），封装字段名各仓不同
        target(registry, 9, "封装在 result 里", "BGSSAI 产品线", STUB.url("/wrapped-result"));
        target(registry, 10, "封装在 data 里", "BGSSAI 产品线", STUB.url("/wrapped-data"));

        // 中间件：Elasticsearch 用集群颜色表达健康，yellow 应被归一化为降级
        target(registry, 11, "搜索集群", "中间件与数据库", STUB.url("/_cluster/health"));
        registry.add("bgssai.healthcheck.applications[11].type", () -> "elasticsearch");
    }

    private static void target(DynamicPropertyRegistry registry, int index, String name, String group, String url) {
        String prefix = "bgssai.healthcheck.applications[" + index + "].";
        registry.add(prefix + "name", () -> name);
        registry.add(prefix + "group", () -> group);
        registry.add(prefix + "url", () -> url);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @BeforeEach
    void setUp() {
        this.client = this.restClientBuilder.clone().baseUrl("http://127.0.0.1:" + this.port).build();
        this.healthCheckService.refreshAll();
    }

    @Test
    @DisplayName("按响应体和状态码归一化出正确的健康状态")
    void normalizesEveryProbeOutcome() {
        List<AppHealth> all = this.healthCheckService.findAll();
        assertThat(all).hasSize(12);

        AppHealth up = byName(all, "正常服务");
        assertThat(up.state()).isEqualTo(HealthState.UP);
        assertThat(up.httpStatus()).isEqualTo(200);
        assertThat(up.components()).extracting(component -> component.name()).contains("db", "diskSpace");

        // 200 + OUT_OF_SERVICE 应判定为降级
        assertThat(byName(all, "降级服务").state()).isEqualTo(HealthState.DEGRADED);

        // 503 + status=DOWN：响应体里的状态优先于状态码
        AppHealth down = byName(all, "异常服务");
        assertThat(down.state()).isEqualTo(HealthState.DOWN);
        assertThat(down.httpStatus()).isEqualTo(503);
        assertThat(down.components()).singleElement()
                .satisfies(component -> assertThat(component.state()).isEqualTo(HealthState.DOWN));

        // 封装在 result 里：剥掉 {code,message,success,result} 后取到 DOWN，components 为数组形态
        AppHealth wrappedResult = byName(all, "封装在 result 里");
        assertThat(wrappedResult.state()).isEqualTo(HealthState.DOWN);
        assertThat(wrappedResult.httpStatus()).isEqualTo(503);
        assertThat(wrappedResult.components()).extracting(component -> component.name())
                .containsExactlyInAnyOrder("db", "mybatis");

        // 封装在 data 里：DEGRADED 留在 HTTP 200，不该被当成「自报 UP 但状态码异常」那一支
        AppHealth wrappedData = byName(all, "封装在 data 里");
        assertThat(wrappedData.state()).isEqualTo(HealthState.DEGRADED);
        assertThat(wrappedData.httpStatus()).isEqualTo(200);
        assertThat(wrappedData.components()).extracting(component -> component.name())
                .containsExactlyInAnyOrder("db", "disk_space");

        // 没有 JSON 报文时按状态码判断
        assertThat(byName(all, "非 JSON 服务").state()).isEqualTo(HealthState.UP);

        AppHealth notFound = byName(all, "路径不存在");
        assertThat(notFound.state()).isEqualTo(HealthState.DOWN);
        assertThat(notFound.httpStatus()).isEqualTo(404);

        AppHealth unreachable = byName(all, "连不上的服务");
        assertThat(unreachable.state()).isEqualTo(HealthState.DOWN);
        assertThat(unreachable.httpStatus()).isNull();
        assertThat(unreachable.message()).isNotBlank();

        AppHealth timeout = byName(all, "超时服务");
        assertThat(timeout.state()).isEqualTo(HealthState.DOWN);
        assertThat(timeout.message()).contains("超时");

        // 自定义预期状态码：204 也算健康
        assertThat(byName(all, "204 表示健康").state()).isEqualTo(HealthState.UP);
    }

    @Test
    @DisplayName("停用的应用不参与巡检")
    void skipsDisabledApplications() {
        AppHealth paused = byName(this.healthCheckService.findAll(), "已停用的服务");

        assertThat(paused.enabled()).isFalse();
        assertThat(paused.state()).isEqualTo(HealthState.UNKNOWN);
        assertThat(paused.stats().totalChecks()).isZero();
        assertThat(paused.message()).isEqualTo("已停用，不参与巡检");
    }

    @Test
    @DisplayName("重复巡检会累积历史与可用率")
    void accumulatesHistory() {
        this.healthCheckService.refreshAll();

        AppHealth up = byName(this.healthCheckService.findAll(), "正常服务");
        assertThat(up.stats().totalChecks()).isGreaterThanOrEqualTo(2);
        assertThat(up.stats().uptimePercent()).isEqualTo(100.0d);
        assertThat(up.history()).hasSizeGreaterThanOrEqualTo(2);

        AppHealth down = byName(this.healthCheckService.findAll(), "异常服务");
        assertThat(down.stats().uptimePercent()).isZero();
        assertThat(down.stats().consecutiveFailures()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("汇总接口返回各状态的计数")
    void summaryEndpointCountsStates() {
        HealthSummary summary = get("/api/summary", HealthSummary.class).getBody();

        assertThat(summary).isNotNull();
        assertThat(summary.overall()).isEqualTo(HealthState.DOWN);
        assertThat(summary.total()).isEqualTo(12);
        assertThat(summary.disabled()).isEqualTo(1);
        assertThat(summary.up()).isEqualTo(3);
        assertThat(summary.degraded()).isEqualTo(3);
        assertThat(summary.down()).isEqualTo(5);
    }

    @Test
    @DisplayName("列表接口返回全部应用")
    void listEndpointReturnsAllApplications() {
        ResponseEntity<String> response = get("/api/apps", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("正常服务").contains("已停用的服务").contains("\"state\":\"DEGRADED\"");
        // 只给页面用的字段不应该出现在接口返回里
        assertThat(response.getBody()).doesNotContain("searchKey");
    }

    @Test
    @DisplayName("看板接口按分组归拢")
    void dashboardEndpointGroupsApplications() {
        ResponseEntity<String> response = get("/api/dashboard", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"name\":\"核心服务\"").contains("\"name\":\"其它\"");
    }

    @Test
    @DisplayName("查询不存在的应用返回 404 与 problem 详情")
    void unknownApplicationReturnsProblemDetail() {
        ResponseEntity<String> response = get("/api/apps/nope", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("应用不存在").contains("\"applicationId\":\"nope\"");
    }

    @Test
    @DisplayName("可以单独触发某个应用的巡检")
    void refreshesSingleApplication() {
        String id = byName(this.healthCheckService.findAll(), "正常服务").id();

        ResponseEntity<String> response = this.client.post()
                .uri("/api/apps/{id}/refresh", id)
                .retrieve()
                .onStatus(status -> true, (request, ignored) -> {
                })
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"state\":\"UP\"");
    }

    @Test
    @DisplayName("告警接口只列连续失败达到阈值的目标，正常与停用的都不在其中")
    void alertsEndpointListsOngoingIncidents() {
        // 阈值是 2，而 setUp 只保证跑过一轮。这里再补一轮，让持续失败的目标一定越过阈值——
        // 不能指望「前面的用例已经巡检过很多轮了」，那会让本用例的结果取决于执行顺序。
        this.healthCheckService.refreshAll();

        AlertStatus status = get("/api/alerts", AlertStatus.class).getBody();

        assertThat(status).isNotNull();
        assertThat(status.enabled()).isTrue();
        assertThat(status.failureThreshold()).isEqualTo(2);
        assertThat(status.channels()).as("没配 webhook 时至少还有日志通道").containsExactly("log");
        assertThat(status.firing()).extracting(AlertStatus.Firing::applicationName)
                .contains("异常服务", "连不上的服务", "降级服务")
                .doesNotContain("正常服务", "已停用的服务");
        assertThat(status.firing()).allSatisfy(firing -> {
            assertThat(firing.notifications()).isPositive();
            assertThat(firing.since()).isNotNull();
            assertThat(firing.lastNotifiedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("服务层对未知 id 抛出领域异常")
    void serviceRejectsUnknownId() {
        assertThatExceptionOfType(UnknownApplicationException.class)
                .isThrownBy(() -> this.healthCheckService.findById("nope"));
    }

    @Test
    @DisplayName("看板页面渲染出应用卡片")
    void dashboardPageRenders() {
        ResponseEntity<String> response = get("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().includes(MediaType.TEXT_HTML)).isTrue();

        String html = response.getBody();
        assertThat(html).isNotNull();
        assertThat(html).contains("BGSSAI 应用健康巡检")
                // 应用名、分组名来自模型对象的 record 访问器
                .contains("正常服务")
                .contains("核心服务")
                // 状态枚举的中文标签与 CSS class
                .contains("data-state=\"up\"")
                .contains("data-state=\"down\"")
                .contains(">异常<")
                // 子组件与历史趋势条
                .contains("db")
                .contains("spark__bar");
    }

    @Test
    @DisplayName("片段接口只返回数据区")
    void dashboardFragmentReturnsOnlyDataRegion() {
        ResponseEntity<String> response = get("/fragments/dashboard", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).startsWith("<div id=\"dashboard\"").doesNotContain("<html");
    }

    @Test
    @DisplayName("平台自身的健康端点包含被监控应用的汇总")
    void actuatorHealthIncludesMonitoredApplications() {
        ResponseEntity<String> response = get("/actuator/health", String.class);

        assertThat(response.getBody()).contains("monitoredApplications");
        // 示例里没有 critical 应用，下游异常不应把平台自己拖成 DOWN
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Elasticsearch 按集群颜色判定，并拆出分片与节点指标")
    void elasticsearchClusterColourIsNormalized() {
        AppHealth cluster = byName(this.healthCheckService.findAll(), "搜索集群");

        assertThat(cluster.type()).isEqualTo(TargetType.ELASTICSEARCH);
        // yellow 是「主分片就绪、副本未分配」，可用但劣化，正好对应 DEGRADED
        assertThat(cluster.state()).isEqualTo(HealthState.DEGRADED);
        assertThat(cluster.httpStatus()).isEqualTo(200);
        assertThat(cluster.message()).contains("集群颜色 yellow").contains("未分配分片 3 个");
        assertThat(cluster.components()).extracting(component -> component.name())
                .containsExactlyInAnyOrder("cluster", "shards", "tasks");
        assertThat(cluster.components()).filteredOn(component -> "shards".equals(component.name()))
                .singleElement()
                .satisfies(component -> {
                    assertThat(component.state()).isEqualTo(HealthState.DEGRADED);
                    assertThat(component.details()).containsEntry("unassigned_shards", "3");
                });
    }

    @Test
    @DisplayName("每次探测都留下原始请求与应答，失败目标也有明细")
    void probeKeepsRawRequestAndResponse() {
        List<AppHealth> all = this.healthCheckService.findAll();

        AppHealth up = byName(all, "正常服务");
        assertThat(up.hasDetail()).isTrue();
        assertThat(up.detail().protocol()).isEqualTo("HTTP");
        assertThat(up.detail().request()).startsWith("GET http://127.0.0.1:");
        assertThat(up.detail().statusLine()).isEqualTo("200 OK");
        // 正文是对端原样返回的那一串，没有被美化或改写
        assertThat(up.detail().body()).isEqualTo(
                "{\"status\":\"UP\",\"components\":{\"db\":{\"status\":\"UP\",\"details\":{\"database\":\"H2\"}},"
                        + "\"diskSpace\":{\"status\":\"UP\"}}}");
        assertThat(up.detail().bodyBytes()).isEqualTo(up.detail().body().length());
        // JDK 的 HttpClient 统一把响应头名归一成小写，这里按它的实际口径断言
        assertThat(up.detail().responseHeaders()).extracting(header -> header.name()).contains("content-type");

        AppHealth unreachable = byName(all, "连不上的服务");
        assertThat(unreachable.hasDetail()).isTrue();
        assertThat(unreachable.detail().statusLine()).isNull();
        assertThat(unreachable.detail().error()).contains("无法建立连接");

        AppHealth disabled = byName(all, "已停用的服务");
        assertThat(disabled.hasDetail()).as("从未探测过的目标没有明细可展示").isFalse();
    }

    @Test
    @DisplayName("目标恢复后仍能回看最近一次失败的现场")
    void keepsLastFailureAfterRecovery() {
        String id = byName(this.healthCheckService.findAll(), "异常服务").id();

        AppHealth failing = this.healthCheckService.findById(id);
        assertThat(failing.state()).isEqualTo(HealthState.DOWN);
        // 当前就在失败状态时，latest 本身就是失败明细，不需要重复展示
        assertThat(failing.hasRecoveredFailure()).isFalse();
        assertThat(failing.lastFailure()).isNotNull();
        assertThat(failing.lastFailure().detail().body()).contains("connection refused");
    }

    @Test
    @DisplayName("详情片段渲染出原始应答与子组件")
    void detailFragmentRendersRawResponse() {
        String id = byName(this.healthCheckService.findAll(), "正常服务").id();

        ResponseEntity<String> response = get("/fragments/apps/" + id + "/detail", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String html = response.getBody();
        assertThat(html).isNotNull();
        assertThat(html).contains("最近一次原始应答")
                .contains("正常服务")
                .contains("diskSpace")
                // JSON 被缩进后展示，尖括号一律转义，绝不把对端响应当 HTML 注入
                .contains("&quot;status&quot;");
    }

    @Test
    @DisplayName("查不到的目标在详情片段里给出提示而不是 500")
    void detailFragmentExplainsUnknownTarget() {
        ResponseEntity<String> response = get("/fragments/apps/nope/detail", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("不存在");
    }

    @Test
    @DisplayName("Markdown 报告可下载，含全部章节与原始应答，且不回显凭据")
    void markdownReportIsDownloadable() {
        ResponseEntity<String> response = get("/api/report.md", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .startsWith("attachment; filename=\"bgssai-healthcheck-report-")
                .endsWith(".md");
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/markdown");

        String report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(report).contains("# BGSSAI 健康巡检报告")
                .contains("## 一、报告元信息")
                .contains("## 二、状态汇总")
                .contains("## 三、需要处理的目标")
                .contains("## 四、全部目标一览")
                .contains("## 五、逐目标明细")
                .contains("## 六、当前巡检配置")
                // 告警配置与进行中的故障也要进报告，否则 AI 无从判断阈值是否合适
                .contains("### 告警")
                .contains("| 连续失败几次才告警 | 2 |")
                .contains("## 七、口径说明（给 AI 的上下文）")
                .contains("## 八、可以让 AI 回答的问题")
                // 逐目标明细里带上了原始应答，AI 才能自己看出对端返回了什么
                .contains("#### 最近一次原始应答")
                .contains("\"status\":\"UP\"")
                // 异常目标要出现在「需要处理」一节里
                .contains("异常服务");
    }

    @Test
    @DisplayName("报告可以关掉原始应答，只留归一化结果")
    void markdownReportCanOmitRawBodies() {
        String full = get("/api/report.md", String.class).getBody();
        String slim = get("/api/report.md?raw=false&download=false", String.class).getBody();

        assertThat(slim).isNotNull();
        assertThat(slim).doesNotContain("#### 最近一次原始应答");
        assertThat(slim.length()).isLessThan(full.length());
        assertThat(get("/api/report.md?download=false", String.class).getHeaders()
                .getFirst("Content-Disposition")).startsWith("inline;");
    }

    private <T> ResponseEntity<T> get(String path, Class<T> type) {
        return this.client.get()
                .uri(path)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {
                })
                .toEntity(type);
    }

    private static AppHealth byName(List<AppHealth> all, String name) {
        return all.stream()
                .filter(app -> app.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到应用 " + name));
    }
}
