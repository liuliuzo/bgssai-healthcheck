package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.alert.AlertService;
import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.config.HealthCheckProperties.Detail;
import com.bgssai.healthcheck.config.HealthCheckProperties.Mysql;
import com.bgssai.healthcheck.config.HealthCheckProperties.Probe;
import com.bgssai.healthcheck.config.HealthCheckProperties.Redis;
import com.bgssai.healthcheck.config.HealthCheckProperties.Target;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.ProbeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台自身健康状态与被监控目标之间的关系。
 */
class MonitoredApplicationsHealthIndicatorTests {

    private static final Probe PROBE = new Probe(Duration.ofSeconds(1), Duration.ofSeconds(1), false, 4096, false);

    private HealthStatusStore store;

    private MonitoredApplicationsHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        HealthCheckProperties properties = new HealthCheckProperties(false, Duration.ofSeconds(30),
                Duration.ofSeconds(3), 4, 10, 0, PROBE, new Detail(true, 4096, true), new Redis(90), new Mysql(90, 3),
                List.of(target("core", "核心服务", true), target("side", "边缘服务", false)));

        ApplicationRegistry registry = new ApplicationRegistry(properties);
        this.store = new HealthStatusStore(properties);
        HttpHealthProbe probe = new HttpHealthProbe(RestClient.builder(), JsonMapper.builder().build(), properties);
        HealthProbeDispatcher dispatcher = new HealthProbeDispatcher(List.of(probe), registry, properties);
        HealthCheckService service = new HealthCheckService(registry, dispatcher, this.store,
                AlertService.disabled(), properties);
        this.indicator = new MonitoredApplicationsHealthIndicator(service);
    }

    @Test
    @DisplayName("首轮巡检之前不因为「未知」就报 DOWN")
    void unknownDoesNotMarkPlatformDown() {
        Health health = this.indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("total", 2);
    }

    @Test
    @DisplayName("非关键目标异常不影响平台自身状态")
    void nonCriticalFailureKeepsPlatformUp() {
        this.store.record("side", ProbeResult.failure(12L, Instant.now(), "连接被拒绝", null));

        assertThat(this.indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("关键目标异常时平台报 DOWN 并列出名称")
    void criticalFailureMarksPlatformDown() {
        this.store.record("core", ProbeResult.failure(12L, Instant.now(), "连接被拒绝", null));

        Health health = this.indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("criticalDown", List.of("核心服务"));
    }

    @Test
    @DisplayName("关键目标恢复后平台状态跟着恢复")
    void recoveryRestoresPlatformHealth() {
        this.store.record("core", ProbeResult.failure(12L, Instant.now(), "连接被拒绝", null));
        assertThat(this.indicator.health().getStatus()).isEqualTo(Status.DOWN);

        this.store.record("core",
                ProbeResult.of(HealthState.UP, 200, 8L, Instant.now(), null, List.of(), null));

        assertThat(this.indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    private static Target target(String id, String name, boolean critical) {
        return new Target(id, name, "测试", "http://127.0.0.1:1/health", null, "GET", true, critical, List.of(),
                Map.of(), null, null, null, null, List.of(), List.of(), null, null);
    }
}
