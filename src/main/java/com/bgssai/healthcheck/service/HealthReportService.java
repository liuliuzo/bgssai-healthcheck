package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.domain.AppHealth;
import com.bgssai.healthcheck.domain.HealthSample;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.HealthSummary;
import com.bgssai.healthcheck.domain.ProbeDetail;
import com.bgssai.healthcheck.domain.ProbeResult;
import com.bgssai.healthcheck.domain.TargetType;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把当前巡检结果渲染成一份可下载的 Markdown 报告。
 *
 * <p>这份报告有两个读者，因此内容比看板更啰嗦：人拿它排障，AI 拿它提改进建议。
 * 后者决定了报告必须自带口径说明与当前配置——只给一堆状态值，AI 只能泛泛而谈
 * 「建议加强监控」；把判定规则、阈值、目标清单一并给出，它才可能指出
 * 「这条目标的读超时 5s 对跨境链路偏紧」这类有依据的结论。</p>
 *
 * <p>口令一律不进报告：目标清单只取 username，明细里的凭据在探针写入前就已脱敏。</p>
 */
@Service
public class HealthReportService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss",
            Locale.CHINA);

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.CHINA);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA);

    /** 逐目标明细里最多回看多少次采样，再多对判断趋势也没帮助。 */
    private static final int HISTORY_LIMIT = 20;

    private final HealthCheckService healthCheckService;

    private final ApplicationRegistry registry;

    private final HealthCheckProperties properties;

    private final ZoneId zone = ZoneId.systemDefault();

    public HealthReportService(HealthCheckService healthCheckService, ApplicationRegistry registry,
            HealthCheckProperties properties) {
        this.healthCheckService = healthCheckService;
        this.registry = registry;
        this.properties = properties;
    }

    /** 建议的下载文件名，带生成时刻，便于多份报告并存比对。 */
    public String fileName() {
        return "bgssai-healthcheck-report-" + FILE_STAMP.format(Instant.now().atZone(this.zone)) + ".md";
    }

    /**
     * 渲染整份报告。
     *
     * @param includeRaw 是否附上每个目标的原始应答；关掉可以把报告缩小一个数量级，
     *                   但也就失去了「AI 能自己看出对端到底返回了什么」的能力
     */
    public String render(boolean includeRaw) {
        List<AppHealth> all = this.healthCheckService.findAll();
        HealthSummary summary = this.healthCheckService.summary();

        StringBuilder md = new StringBuilder(16384);
        md.append("# BGSSAI 健康巡检报告\n\n");
        renderMetadata(md, summary, all);
        renderSummary(md, summary, all);
        renderAttention(md, all);
        renderInventory(md, all);
        renderPerTarget(md, all, includeRaw);
        renderConfiguration(md);
        renderConventions(md);
        renderQuestions(md, all);
        return md.toString();
    }

    /* ---------- 一、报告元信息 ---------- */

    private void renderMetadata(StringBuilder md, HealthSummary summary, List<AppHealth> all) {
        md.append("## 一、报告元信息\n\n");
        md.append("| 项目 | 值 |\n| --- | --- |\n");
        row(md, "生成时间", timestamp(summary.generatedAt()));
        row(md, "整体状态", summary.overall().getLabel() + "（" + summary.overall().name() + "）");
        row(md, "目标总数", String.valueOf(all.size()));
        row(md, "最近一轮巡检", timestamp(summary.lastCheckedAt()));
        row(md, "定时巡检", this.properties.scheduled() ? "已启用" : "已关闭（只能手动触发）");
        row(md, "巡检间隔", describe(this.properties.refreshInterval()));
        row(md, "首轮延迟", describe(this.properties.initialDelay()));
        row(md, "最大并发探测数", String.valueOf(this.properties.concurrency()));
        row(md, "历史采样点数", String.valueOf(this.properties.historySize()));
        row(md, "原始明细保留", this.properties.detail().enabled()
                ? "开启，单条上限 " + this.properties.detail().maxBodyChars() + " 字符"
                : "关闭");
        row(md, "保留最近一次失败明细", this.properties.detail().keepLastFailure() ? "是" : "否");
        md.append('\n');
    }

    /* ---------- 二、状态汇总 ---------- */

    private void renderSummary(StringBuilder md, HealthSummary summary, List<AppHealth> all) {
        md.append("## 二、状态汇总\n\n### 按状态\n\n");
        md.append("| 状态 | 数量 | 占比 |\n| --- | --- | --- |\n");
        int total = Math.max(1, all.size());
        stateRow(md, "正常", summary.up(), total);
        stateRow(md, "降级", summary.degraded(), total);
        stateRow(md, "异常", summary.down(), total);
        stateRow(md, "未知", summary.unknown(), total);
        stateRow(md, "已停用", summary.disabled(), total);

        md.append("\n### 按目标类型\n\n");
        md.append("| 类型 | 总数 | 正常 | 降级 | 异常 | 未知 |\n| --- | --- | --- | --- | --- | --- |\n");
        Map<TargetType, List<AppHealth>> byType = new EnumMap<>(TargetType.class);
        all.forEach(app -> byType.computeIfAbsent(app.type(), key -> new ArrayList<>()).add(app));
        byType.forEach((type, apps) -> md.append("| ")
                .append(type.getLabel())
                .append(" | ")
                .append(apps.size())
                .append(" | ")
                .append(count(apps, HealthState.UP))
                .append(" | ")
                .append(count(apps, HealthState.DEGRADED))
                .append(" | ")
                .append(count(apps, HealthState.DOWN))
                .append(" | ")
                .append(count(apps, HealthState.UNKNOWN))
                .append(" |\n"));
        md.append('\n');
    }

    /* ---------- 三、需要处理的目标 ---------- */

    private void renderAttention(StringBuilder md, List<AppHealth> all) {
        md.append("## 三、需要处理的目标\n\n");
        List<AppHealth> attention = all.stream()
                .filter(app -> app.state() == HealthState.DOWN || app.state() == HealthState.DEGRADED)
                .sorted(Comparator.comparingInt((AppHealth app) -> -app.state().getSeverity())
                        .thenComparing(AppHealth::group)
                        .thenComparing(AppHealth::name))
                .toList();
        if (attention.isEmpty()) {
            md.append("本轮巡检未发现异常或降级目标。\n\n");
            return;
        }
        md.append("| 目标 | id | 类型 | 分组 | 状态 | 地址 | 连续失败 | 说明 |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (AppHealth app : attention) {
            md.append("| ").append(cell(app.name()))
                    .append(" | ").append(cell(app.id()))
                    .append(" | ").append(cell(app.type().getLabel()))
                    .append(" | ").append(cell(app.group()))
                    .append(" | ").append(app.state().getLabel())
                    .append(" | ").append(cell(app.url()))
                    .append(" | ").append(app.stats().consecutiveFailures())
                    .append(" | ").append(cell(app.message()))
                    .append(" |\n");
        }
        md.append('\n');
    }

    /* ---------- 四、全部目标一览 ---------- */

    private void renderInventory(StringBuilder md, List<AppHealth> all) {
        md.append("## 四、全部目标一览\n\n");
        Map<String, List<AppHealth>> grouped = new LinkedHashMap<>();
        all.forEach(app -> grouped.computeIfAbsent(app.group(), key -> new ArrayList<>()).add(app));
        grouped.forEach((group, apps) -> {
            md.append("### ").append(group).append('\n').append('\n');
            md.append("| 目标 | id | 类型 | 状态 | HTTP | 耗时 | 可用率 | 最近检查 | 地址 |\n");
            md.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
            for (AppHealth app : apps) {
                md.append("| ").append(cell(app.name()))
                        .append(" | ").append(cell(app.id()))
                        .append(" | ").append(cell(app.type().getLabel()))
                        .append(" | ").append(app.state().getLabel())
                        .append(" | ").append(app.httpStatus() == null ? "-" : app.httpStatus())
                        .append(" | ").append(latency(app.latencyMs()))
                        .append(" | ").append(uptime(app))
                        .append(" | ").append(timestamp(app.checkedAt()))
                        .append(" | ").append(cell(app.url()))
                        .append(" |\n");
            }
            md.append('\n');
        });
    }

    /* ---------- 五、逐目标明细 ---------- */

    private void renderPerTarget(StringBuilder md, List<AppHealth> all, boolean includeRaw) {
        md.append("## 五、逐目标明细\n\n");
        int index = 0;
        for (AppHealth app : all) {
            index++;
            md.append("### ").append(index).append(". ").append(app.name())
                    .append("（").append(app.id()).append("）\n\n");

            md.append("| 基本信息 | 值 |\n| --- | --- |\n");
            row(md, "类型", app.type().getLabel());
            row(md, "分组", app.group());
            row(md, "地址", app.url());
            row(md, "标签", app.tags().isEmpty() ? "-" : String.join(", ", app.tags()));
            row(md, "备注", app.description());
            row(md, "关键目标", app.critical() ? "是" : "否");
            row(md, "已启用", app.enabled() ? "是" : "否");

            md.append("\n| 运行状况 | 值 |\n| --- | --- |\n");
            row(md, "当前状态", app.state().getLabel() + "（" + app.state().name() + "）");
            row(md, "HTTP 状态码", app.httpStatus() == null ? "-" : String.valueOf(app.httpStatus()));
            row(md, "响应耗时", latency(app.latencyMs()));
            row(md, "最近检查", timestamp(app.checkedAt()));
            row(md, "说明", app.message());
            row(md, "累计探测次数", String.valueOf(app.stats().totalChecks()));
            row(md, "其中正常", String.valueOf(app.stats().upChecks()));
            row(md, "可用率", uptime(app));
            row(md, "平均耗时", latency(app.stats().avgLatencyMs()));
            row(md, "最大耗时", latency(app.stats().maxLatencyMs()));
            row(md, "连续失败次数", String.valueOf(app.stats().consecutiveFailures()));
            row(md, "最近正常时刻", timestamp(app.stats().lastUpAt()));
            row(md, "最近异常时刻", timestamp(app.stats().lastDownAt()));
            md.append('\n');

            if (!app.components().isEmpty()) {
                md.append("| 子组件 | 状态 | 明细 |\n| --- | --- | --- |\n");
                app.components().forEach(component -> md.append("| ").append(cell(component.name()))
                        .append(" | ").append(component.state().getLabel())
                        .append(" | ").append(cell(flatten(component.details())))
                        .append(" |\n"));
                md.append('\n');
            }

            if (includeRaw && app.detail() != null) {
                renderDetail(md, "#### 最近一次原始应答", app.detail(), null);
            }
            if (includeRaw && app.hasRecoveredFailure()) {
                ProbeResult failure = app.lastFailure();
                renderDetail(md, "#### 最近一次失败的原始应答", failure.detail(),
                        "失败时刻 " + timestamp(failure.checkedAt()) + "，当时判定为 " + failure.state().getLabel()
                                + (failure.message() == null ? "" : "，说明：" + failure.message()));
            }

            if (!app.history().isEmpty()) {
                List<HealthSample> history = new ArrayList<>(app.history());
                java.util.Collections.reverse(history);
                md.append("最近 ").append(Math.min(HISTORY_LIMIT, history.size())).append(" 次采样（由新到旧）：");
                List<String> samples = new ArrayList<>();
                history.stream().limit(HISTORY_LIMIT).forEach(sample -> samples.add(
                        clock(sample.at()) + " " + sample.state().getLabel() + "(" + latency(sample.latencyMs())
                                + ")"));
                md.append(String.join(" | ", samples)).append("\n\n");
            }
        }
    }

    private void renderDetail(StringBuilder md, String heading, ProbeDetail detail, String note) {
        md.append(heading).append("\n\n");
        if (note != null) {
            md.append(note).append("\n\n");
        }
        if (detail == null) {
            md.append("（未保留明细）\n\n");
            return;
        }
        md.append("| 明细 | 值 |\n| --- | --- |\n");
        row(md, "协议", detail.protocol());
        row(md, "请求", detail.request());
        row(md, "应答首行", detail.statusLine());
        if (detail.error() != null) {
            row(md, "错误", detail.error());
        }
        md.append('\n');

        if (detail.hasHeaders()) {
            md.append("| 响应头 | 值 |\n| --- | --- |\n");
            detail.responseHeaders().forEach(header -> md.append("| ").append(cell(header.name()))
                    .append(" | ").append(cell(header.value())).append(" |\n"));
            md.append('\n');
        }

        if (detail.hasBody()) {
            String body = detail.body();
            String trimmed = body.stripLeading();
            String language = (trimmed.startsWith("{") || trimmed.startsWith("[")) ? "json" : "text";
            // 正文里若本来就有三个反引号，用四个才不会把代码块提前收掉
            String fence = body.contains("```") ? "````" : "```";
            md.append(fence).append(language).append('\n').append(body);
            if (!body.endsWith("\n")) {
                md.append('\n');
            }
            md.append(fence).append("\n\n");
            if (detail.truncated()) {
                md.append("（正文已按 detail.max-body-chars 截断，完整长度 ")
                        .append(detail.bodyBytes())
                        .append(" 字节）\n\n");
            }
        }
    }

    /* ---------- 六、当前巡检配置 ---------- */

    private void renderConfiguration(StringBuilder md) {
        HealthCheckProperties.Probe probe = this.properties.probe();
        md.append("## 六、当前巡检配置\n\n### 探测参数\n\n");
        md.append("| 参数 | 值 |\n| --- | --- |\n");
        row(md, "连接超时", describe(probe.connectTimeout()));
        row(md, "读取超时", describe(probe.readTimeout()));
        row(md, "跟随 3xx 跳转", probe.followRedirects() ? "是" : "否");
        row(md, "响应体读取上限", probe.maxBodyBytes() + " 字节");
        row(md, "全局跳过证书校验", probe.skipTlsVerification() ? "是" : "否（按目标单独打开）");
        row(md, "Redis 内存告警阈值", this.properties.redis().memoryWarnPercent() + "%");
        row(md, "MySQL 连接数告警阈值", this.properties.mysql().connectionWarnPercent() + "%");
        row(md, "MySQL 查询超时", this.properties.mysql().queryTimeoutSeconds() + " 秒");

        md.append("\n### 目标清单\n\n");
        md.append("| id | 类型 | 地址 | 启用 | 关键 | 连接超时 | 读取超时 | 跳过证书校验 | 期望状态码 | 期望数据库 |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (MonitoredApplication app : this.registry.findAll()) {
            md.append("| ").append(cell(app.id()))
                    .append(" | ").append(cell(app.type().getLabel()))
                    .append(" | ").append(cell(app.uri().toString()))
                    .append(" | ").append(app.enabled() ? "是" : "否")
                    .append(" | ").append(app.critical() ? "是" : "否")
                    .append(" | ").append(describe(app.connectTimeout()))
                    .append(" | ").append(describe(app.readTimeout()))
                    .append(" | ").append(app.skipTlsVerification() ? "是" : "否")
                    .append(" | ").append(app.expectedStatuses().isEmpty() ? "任意 2xx"
                            : app.expectedStatuses().stream().sorted().map(String::valueOf)
                                    .collect(java.util.stream.Collectors.joining(", ")))
                    .append(" | ").append(app.expectedDatabases().isEmpty() ? "-"
                            : String.join(", ", app.expectedDatabases()))
                    .append(" |\n");
        }
        md.append("\n口令不在本报告中出现：目标清单只列到账号层面，明细里的认证信息在探针写入前已替换为占位符。\n\n");
    }

    /* ---------- 七、口径说明 ---------- */

    private void renderConventions(StringBuilder md) {
        md.append("""
                ## 七、口径说明（给 AI 的上下文）

                本平台是 BGSSAI 产品线的集中巡检工具，周期性调用各目标的健康接口或直连中间件，
                把各种约定收敛成四种状态。读这份报告前需要知道下面这些口径。

                ### 状态语义

                - `UP` 正常：目标可达，且自报正常。
                - `DEGRADED` 降级：目标可达但自报降级（`OUT_OF_SERVICE` / `WARN` / Elasticsearch 的 `yellow`），
                  或自报 `UP` 但 HTTP 状态码不在预期内，或中间件命中了阈值（Redis 内存、MySQL 连接数、缺库、只读）。
                - `DOWN` 异常：连接失败、超时、状态码不符合预期，或自报异常。
                - `UNKNOWN` 未知：尚未巡检，或该目标已停用。

                ### 判定优先级

                1. 能从响应体解析出状态字段（依次尝试 `status`、`state`、`health`）时以响应体为准。
                   这样才能正确处理 Actuator 用 `503 + {"status":"DOWN"}` 表示异常、
                   `200 + {"status":"OUT_OF_SERVICE"}` 表示降级的约定。
                2. 响应体不是可识别的健康报文时按 HTTP 状态码判断，命中 `expected-statuses`（未配置则任意 2xx）即为正常。
                3. 顶层没有状态字段时会再往 `result` / `data` 下沉一层：BGSSAI 各仓把健康负载包在自己的统一响应
                   封装里，封装的 `code` / `success` 表达的是「接口调用成功」，与健康无关。

                ### 为什么中间件与数据库要单独直连探测

                - 9 个产品共 18 个后端，巡检地址统一为 `/bgssai/health/readiness`（Standards §13.7）。
                - 但按 Standards §13.2，就绪探针**只由 critical 组件决定结论**，而 critical 只有 `db` 与 `mybatis`；
                  Redis、Elasticsearch 这类依赖一律非 critical，既不参与判定、**也不在就绪端点被检查**。
                  也就是说 Redis 挂了，应用的就绪探针照样返回 `UP`。
                - Standards §13.4 又禁止健康负载回显连接串、主机、端口与数据库版本，所以即使查到应用侧，
                  也拿不到中间件本身的水位。
                - 因此中间件与数据库的可用性只能由本平台自己连过去看：Redis 走 RESP 发 `PING` 与 `INFO`，
                  MySQL 走 JDBC 跑 `SELECT 1` 并核对 `information_schema` 里的库，
                  Elasticsearch 走 `_cluster/health` 按集群颜色判定。

                ### 其它需要知道的事实

                - 18 条产品后端一律 `critical=false`：本平台自身的 `/actuator/health` 只该反映「平台还能不能巡检」，
                  不该因为某个下游挂了就对外报 DOWN，否则编排系统会去重启这个本来正常的平台。
                - 这些目标按机器 IP 直连 HTTPS，而证书签给的是业务域名，握手会因主机名不匹配失败，
                  因此打开了 `skip-tls-verification`；Elasticsearch 三台实例是自签证书，同理。
                  收紧的正解是给每台机器配域名后按域名探测，而不是继续放开校验。
                - 巡检结果**只保存在进程内存里**，平台重启即归零。因此报告里的可用率是「自本次启动以来」的口径，
                  不是长期 SLA，比较不同目标的可用率时要先看它们的累计探测次数是否可比。
                - 单轮巡检并发受 `concurrency` 限制，每个目标的连接与读取都有独立超时。

                """);
    }

    /* ---------- 八、可以让 AI 回答的问题 ---------- */

    private void renderQuestions(StringBuilder md, List<AppHealth> all) {
        long neverUp = all.stream().filter(AppHealth::enabled)
                .filter(app -> app.stats().totalChecks() > 0 && app.stats().upChecks() == 0).count();
        long skipTls = this.registry.findAll().stream().filter(MonitoredApplication::skipTlsVerification).count();
        long middleware = this.registry.findAll().stream().filter(app -> app.type() != TargetType.HTTP).count();

        md.append("## 八、可以让 AI 回答的问题\n\n");
        md.append("把本文件整份交给 AI 时，可以直接让它回答下面这些问题。\n\n");
        md.append("1. 第三节列出的异常与降级目标中，哪些更像巡检配置问题（超时过紧、期望状态码不对、路径写错），"
                + "哪些更像对端真的有故障？分别给出判断依据。\n");
        md.append("2. 对比同一分组内各目标的可用率与平均耗时，是否存在明显偏离的目标？");
        md.append("本报告里从未探测成功过的目标有 ").append(neverUp).append(" 个，它们的共同点是什么？\n");
        md.append("3. 当前的连接超时与读取超时对跨境目标（境外机器、腾讯云机器）是否偏紧？"
                + "结合第五节的平均耗时与最大耗时给出建议值。\n");
        md.append("4. 巡检间隔与最大并发的组合是否合理？若目标数量继续增加，先撞上的会是哪一个限制？\n");
        md.append("5. 已有 ").append(middleware).append(" 个非 HTTP 目标（数据库 / 缓存 / 搜索 / 端口）纳入巡检。"
                + "从第六节的目标清单看，还有哪些基础设施应该补进来？\n");
        md.append("6. Redis 内存告警阈值与 MySQL 连接数告警阈值，是否应该按第五节里各实例的实际水位调整？\n");
        md.append("7. 有 ").append(skipTls).append(" 个目标打开了 skip-tls-verification。"
                + "哪些可以优先改为按域名探测从而收紧校验？给出迁移顺序。\n");
        md.append("8. 现在所有目标都是 `critical=false`。是否有哪些目标值得升级为 critical？"
                + "升级后对平台自身 `/actuator/health` 与编排系统会有什么影响？\n");
        md.append("9. 逐目标明细里的原始应答，有没有哪些字段其实对定位问题没有帮助、可以不再保留？"
                + "反过来，有没有明显缺失、应该补进明细的信息？\n");
        md.append("10. 从这份报告能否看出某类故障反复出现？如果要为它加一条自动告警规则，"
                + "触发条件应该怎么写才不会误报？\n");
        md.append("11. 报告本身的结构对你分析是否够用？哪些章节可以合并、哪些需要拆细？\n\n");
    }

    /* ---------- 格式化辅助 ---------- */

    private static void row(StringBuilder md, String name, String value) {
        md.append("| ").append(name).append(" | ").append(cell(value)).append(" |\n");
    }

    private static void stateRow(StringBuilder md, String name, int value, int total) {
        md.append("| ").append(name).append(" | ").append(value).append(" | ").append(percent(value, total))
                .append(" |\n");
    }

    /** 表格单元格里的竖线会切断列，换行会切断行，两者都要先中和掉。 */
    private static String cell(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    private static String flatten(Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        details.forEach((key, value) -> parts.add(key + "=" + value));
        return String.join("; ", parts);
    }

    private static long count(List<AppHealth> apps, HealthState state) {
        return apps.stream().filter(app -> app.state() == state).count();
    }

    private static String percent(int value, int total) {
        return "%.1f%%".formatted(value * 100.0d / total);
    }

    private static String uptime(AppHealth app) {
        return (app.stats().totalChecks() == 0) ? "-" : "%.2f%%".formatted(app.stats().uptimePercent());
    }

    private static String latency(long millis) {
        if (millis <= 0L) {
            return "-";
        }
        return (millis < 1000L) ? millis + " ms" : "%.2f s".formatted(millis / 1000.0d);
    }

    private static String describe(Duration duration) {
        if (duration == null) {
            return "-";
        }
        return (duration.toMillis() % 1000L == 0L) ? duration.toSeconds() + " 秒" : duration.toMillis() + " 毫秒";
    }

    private String timestamp(Instant instant) {
        return (instant == null) ? "-" : TIMESTAMP.format(instant.atZone(this.zone));
    }

    private String clock(Instant instant) {
        return (instant == null) ? "-" : CLOCK.format(instant.atZone(this.zone));
    }
}
