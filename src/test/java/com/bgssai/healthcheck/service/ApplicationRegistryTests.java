package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.config.HealthCheckProperties.Probe;
import com.bgssai.healthcheck.config.HealthCheckProperties.Target;
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
    @DisplayName("未配置超时的应用回退到全局默认值")
    void fallsBackToGlobalTimeouts() {
        ApplicationRegistry registry = registryOf(target(null, "a", "http://a.internal/health"));

        MonitoredApplication app = registry.findAll().getFirst();
        assertThat(app.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(app.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(app.method()).isEqualTo(HttpMethod.GET);
        assertThat(app.group()).isEqualTo("未分组");
    }

    @Test
    @DisplayName("配置了用户名时生成 Basic 认证头")
    void buildsBasicAuthHeader() {
        Target target = new Target(null, "a", "g", "http://a.internal/health", "GET", true, false, List.of(), Map.of(),
                "monitor", "s3cret", null, null, List.of(), null, null);

        ApplicationRegistry registry = registryOf(target);

        // Base64("monitor:s3cret")
        assertThat(registry.findAll().getFirst().authorization()).isEqualTo("Basic bW9uaXRvcjpzM2NyZXQ=");
    }

    @Test
    @DisplayName("停用的应用不出现在待巡检列表中")
    void enabledExcludesDisabled() {
        Target disabled = new Target(null, "b", "g", "http://b.internal/health", "GET", false, false, List.of(),
                Map.of(), null, null, null, null, List.of(), null, null);

        ApplicationRegistry registry = registryOf(target(null, "a", "http://a.internal/health"), disabled);

        assertThat(registry.findAll()).hasSize(2);
        assertThat(registry.enabled()).extracting(MonitoredApplication::name).containsExactly("a");
    }

    @Test
    @DisplayName("非法地址在启动时就报错，而不是等到巡检时才失败")
    void rejectsInvalidUrl() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> registryOf(target(null, "a", "ftp://a.internal/health")))
                .withMessageContaining("只支持 http / https");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> registryOf(target(null, "a", "/health")))
                .withMessageContaining("必须是带主机名的绝对地址");
    }

    @Test
    @DisplayName("只允许 GET / HEAD")
    void rejectsUnsupportedMethod() {
        Target target = new Target(null, "a", "g", "http://a.internal/health", "POST", true, false, List.of(),
                Map.of(), null, null, null, null, List.of(), null, null);

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> registryOf(target))
                .withMessageContaining("只允许 GET / HEAD");
    }

    private static ApplicationRegistry registryOf(Target... targets) {
        HealthCheckProperties properties = new HealthCheckProperties(false, Duration.ofSeconds(30),
                Duration.ofSeconds(3), 16, 60, 10, PROBE, List.of(targets));
        return new ApplicationRegistry(properties);
    }

    private static Target target(String id, String name, String url) {
        return new Target(id, name, "未分组", url, "GET", true, false, List.of(), Map.of(), null, null, null, null,
                List.of(), null, null);
    }
}
