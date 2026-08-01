package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.StubHttpsHealthServer;
import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.config.HealthCheckProperties.Probe;
import com.bgssai.healthcheck.config.HealthCheckProperties.Target;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.ProbeResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code skip-tls-verification} 的行为守护。
 *
 * <p>被测场景是产线现状的最小复现：对端以 TLS 监听，但证书签给的是别的主机名
 * （{@code some-other-host.example}），而巡检按 IP（{@code 127.0.0.1}）直连。</p>
 *
 * <p>两个用例是一对，缺一不可：前者证明这个坑真实存在（不放开就整片误判 DOWN），
 * 后者证明开关确实解决了它。只留后者的话，哪天默认行为变了也没人发现。</p>
 */
class TlsVerificationProbeTests {

    private static final StubHttpsHealthServer STUB = new StubHttpsHealthServer();

    @AfterAll
    static void shutdown() {
        STUB.close();
    }

    private static ProbeResult probeWith(boolean skipTlsVerification) {
        Probe probe = new Probe(Duration.ofSeconds(3), Duration.ofSeconds(5), false, 65536, false);
        Target target = new Target("stub", "stub", "g", STUB.url("/bgssai/health/readiness"), "GET",
                true, false, List.of(), Map.of(), null, null, null, null, List.of(), null,
                skipTlsVerification);
        HealthCheckProperties properties = new HealthCheckProperties(false, Duration.ofSeconds(30),
                Duration.ofSeconds(3), 16, 60, 10, probe, List.of(target));

        ApplicationRegistry registry = new ApplicationRegistry(properties);
        MonitoredApplication app = registry.findAll().get(0);
        return new HttpHealthProbe(RestClient.builder(), JsonMapper.builder().build(), properties).probe(app);
    }

    @Test
    @DisplayName("默认按标准校验：证书主机名不匹配时探测失败——这正是按 IP 探 HTTPS 的坑")
    void standardVerificationFailsOnHostnameMismatch() {
        ProbeResult result = probeWith(false);

        assertThat(result.state())
                .as("对端其实是健康的，但证书主机名对不上，标准校验下只能判 DOWN")
                .isEqualTo(HealthState.DOWN);
    }

    @Test
    @DisplayName("打开 skip-tls-verification 后能读到对端自报的 UP")
    void skipVerificationReachesTheEndpoint() {
        ProbeResult result = probeWith(true);

        assertThat(result.state()).isEqualTo(HealthState.UP);
        assertThat(result.httpStatus()).isEqualTo(200);
    }
}
