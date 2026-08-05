package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.config.HealthCheckProperties.Detail;
import com.bgssai.healthcheck.config.HealthCheckProperties.Mysql;
import com.bgssai.healthcheck.config.HealthCheckProperties.Probe;
import com.bgssai.healthcheck.config.HealthCheckProperties.Redis;
import com.bgssai.healthcheck.config.HealthCheckProperties.Target;
import com.bgssai.healthcheck.domain.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 配置解析的边界情况。
 */
class ApplicationRegistryTests {

    private static final Probe PROBE = new Probe(Duration.ofSeconds(3), Duration.ofSeconds(5), false, 65536, false);

    @Test
    @DisplayName("未配置 id 时按名称生成，纯中文名回退到序号")
    void derivesIdFromName() {
        ApplicationRegistry registry = registryOf(target(null, "User Center", "http://a.internal/health"),
                target(null, "订单服务", "http://b.internal/health"));

        assertThat(registry.findAll()).extracting(MonitoredApplication::id).containsExactly("user-center", "app-2");
    }

    @Test
    @DisplayName("id 重复时自动追加序号，保证唯一")
    void deduplicatesIds() {
        ApplicationRegistry registry = registryOf(target("api", "网关 A", "http://a.internal/health"),
                target("api", "网关 B", "http://b.internal/health"),
                target(null, "API", "http://c.internal/health"));

        assertThat(registry.findAll()).extracting(MonitoredApplication::id).containsExactly("api", "api-2", "api-3");
    }

    @Test
    @DisplayName("未配置超时的目标回退到全局默认值")
    void fallsBackToGlobalTimeouts() {
        ApplicationRegistry registry = registryOf(target(null, "a", "http://a.internal/health"));

        MonitoredApplication app = registry.findAll().getFirst();
        assertThat(app.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(app.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(app.method()).isEqualTo(HttpMethod.GET);
        assertThat(app.group()).isEqualTo("未分组");
        assertThat(app.type()).isEqualTo(TargetType.HTTP);
    }

    @Test
    @DisplayName("配置了用户名时生成 Basic 认证头，同时保留原文供其它协议使用")
    void buildsBasicAuthHeader() {
        Target target = new Target(null, "a", "g", "http://a.internal/health", null, "GET", true, false, List.of(),
                Map.of(), "monitor", "s3cret", null, null, List.of(), List.of(), null, null);

        MonitoredApplication app = registryOf(target).findAll().getFirst();

        // Base64("monitor:s3cret")
        assertThat(app.authorization()).isEqualTo("Basic bW9uaXRvcjpzM2NyZXQ=");
        assertThat(app.username()).isEqualTo("monitor");
        assertThat(app.password()).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("停用的目标不出现在待巡检列表中")
    void enabledExcludesDisabled() {
        Target disabled = new Target(null, "b", "g", "http://b.internal/health", null, "GET", false, false, List.of(),
                Map.of(), null, null, null, null, List.of(), List.of(), null, null);

        ApplicationRegistry registry = registryOf(target(null, "a", "http://a.internal/health"), disabled);

        assertThat(registry.findAll()).hasSize(2);
        assertThat(registry.enabled()).extracting(MonitoredApplication::name).containsExactly("a");
    }

    @Test
    @DisplayName("非法地址在启动时就报错，而不是等到巡检时才失败")
    void rejectsInvalidUrl() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> registryOf(target(null, "a", "ftp://a.internal/health")))
                .withMessageContaining("无法推导目标类型");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> registryOf(target(null, "a", "/health")))
                .withMessageContaining("必须是带主机名的绝对地址");
    }

    @Test
    @DisplayName("只允许 GET / HEAD")
    void rejectsUnsupportedMethod() {
        Target target = new Target(null, "a", "g", "http://a.internal/health", null, "POST", true, false, List.of(),
                Map.of(), null, null, null, null, List.of(), List.of(), null, null);

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> registryOf(target))
                .withMessageContaining("只允许 GET / HEAD");
    }

    @Test
    @DisplayName("scheme 决定目标类型，端口按类型补齐")
    void derivesTypeAndPortFromScheme() {
        ApplicationRegistry registry = registryOf(
                target(null, "redis", "redis://cache.internal"),
                target(null, "db", "mysql://db.internal/bgssai_blog"),
                target(null, "broker", "tcp://mq.internal:1883"));

        List<MonitoredApplication> apps = registry.findAll();
        assertThat(apps).extracting(MonitoredApplication::type)
                .containsExactly(TargetType.REDIS, TargetType.MYSQL, TargetType.TCP);
        assertThat(apps).extracting(MonitoredApplication::port).containsExactly(6379, 3306, 1883);
        assertThat(apps.get(1).uri().getPath()).isEqualTo("/bgssai_blog");
    }

    @Test
    @DisplayName("Elasticsearch 必须显式声明类型，未写路径时补上集群健康接口")
    void elasticsearchNeedsExplicitTypeAndGetsDefaultPath() {
        Target implicit = new Target("es", "es", "g", "https://es.internal:9200", null, "GET", true, false, List.of(),
                Map.of(), null, null, null, null, List.of(), List.of(), null, null);
        Target explicit = new Target("es", "es", "g", "https://es.internal:9200", TargetType.ELASTICSEARCH, "GET",
                true, false, List.of(), Map.of(), "elastic", "pw", null, null, List.of(), List.of(), null, true);

        // scheme 是 https，无法与普通 HTTP 接口区分，所以不写 type 时只会被当成普通 HTTP
        assertThat(registryOf(implicit).findAll().getFirst().type()).isEqualTo(TargetType.HTTP);

        MonitoredApplication app = registryOf(explicit).findAll().getFirst();
        assertThat(app.type()).isEqualTo(TargetType.ELASTICSEARCH);
        assertThat(app.uri().toString()).isEqualTo("https://es.internal:9200/_cluster/health");
        assertThat(app.port()).isEqualTo(9200);
    }

    @Test
    @DisplayName("类型与 scheme 对不上时启动即失败")
    void rejectsTypeSchemeMismatch() {
        Target mismatched = new Target("x", "x", "g", "http://a.internal/health", TargetType.REDIS, "GET", true,
                false, List.of(), Map.of(), null, null, null, null, List.of(), List.of(), null, null);

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> registryOf(mismatched))
                .withMessageContaining("该类型只接受");
    }

    @Test
    @DisplayName("TCP 目标必须写明端口，没有可推导的默认值")
    void tcpRequiresExplicitPort() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> registryOf(target(null, "mq", "tcp://mq.internal")))
                .withMessageContaining("必须写明端口");
    }

    @Test
    @DisplayName("expected-databases 只对 mysql 目标生效，配错地方直接启动失败")
    void expectedDatabasesOnlyApplyToMysql() {
        Target mysql = new Target("db", "db", "g", "mysql://db.internal:3306/", null, "GET", true, false, List.of(),
                Map.of(), "root", "pw", null, null, List.of(), List.of("bgssai_blog", "bgssai_vpn"), null, null);
        Target http = new Target("api", "api", "g", "http://a.internal/health", null, "GET", true, false, List.of(),
                Map.of(), null, null, null, null, List.of(), List.of("bgssai_blog"), null, null);

        assertThat(registryOf(mysql).findAll().getFirst().expectedDatabases())
                .containsExactly("bgssai_blog", "bgssai_vpn");

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> registryOf(http))
                .withMessageContaining("只对 mysql 目标生效");
    }

    @Test
    @DisplayName("按类型统计目标数量")
    void countsByType() {
        ApplicationRegistry registry = registryOf(target(null, "a", "http://a.internal/health"),
                target(null, "b", "http://b.internal/health"),
                target(null, "cache", "redis://cache.internal:6379"));

        assertThat(registry.countByType()).containsEntry(TargetType.HTTP, 2).containsEntry(TargetType.REDIS, 1);
    }

    private static ApplicationRegistry registryOf(Target... targets) {
        HealthCheckProperties properties = new HealthCheckProperties(false, Duration.ofSeconds(30),
                Duration.ofSeconds(3), 16, 60, 10, PROBE, new Detail(true, 16384, true), new Redis(90),
                new Mysql(90, 3), List.of(targets));
        return new ApplicationRegistry(properties);
    }

    private static Target target(String id, String name, String url) {
        return new Target(id, name, "未分组", url, null, "GET", true, false, List.of(), Map.of(), null, null, null,
                null, List.of(), List.of(), null, null);
    }
}
