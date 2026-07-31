package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.domain.AppHealth;
import com.bgssai.healthcheck.domain.HealthState;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把被监控应用的巡检结果并入平台自身的 {@code /actuator/health}。
 *
 * <p>只有标记为 {@code critical: true} 的应用确认异常时，平台才会对外报 DOWN——
 * 巡检平台本身可用与被监控方是否可用是两件事，不能因为随便一个下游挂了就让
 * 编排系统重启这个平台。</p>
 *
 * <p>「尚未巡检」的 UNKNOWN 不算异常：否则平台刚启动、首轮巡检还没跑完时就会
 * 对外报 DOWN；如果此时又有人把本平台自己的 {@code /actuator/health} 配成了
 * critical 应用，这个 DOWN 会被巡检结果记下来，从此再也回不到 UP。</p>
 */
@Component("monitoredApplications")
public class MonitoredApplicationsHealthIndicator implements HealthIndicator {

    private final HealthCheckService healthCheckService;

    public MonitoredApplicationsHealthIndicator(HealthCheckService healthCheckService) {
        this.healthCheckService = healthCheckService;
    }

    @Override
    public Health health() {
        List<AppHealth> all = this.healthCheckService.findAll();
        List<String> criticalDown = all.stream()
                .filter(AppHealth::enabled)
                .filter(AppHealth::critical)
                .filter(app -> app.state() == HealthState.DOWN || app.state() == HealthState.DEGRADED)
                .map(AppHealth::name)
                .toList();

        Health.Builder builder = criticalDown.isEmpty() ? Health.up() : Health.down();
        builder.withDetail("total", all.size())
                .withDetail("up", all.stream().filter(app -> app.enabled() && app.state().isHealthy()).count())
                .withDetail("down",
                        all.stream().filter(app -> app.enabled() && app.state() == HealthState.DOWN).count());
        if (!criticalDown.isEmpty()) {
            builder.withDetail("criticalDown", criticalDown);
        }
        return builder.build();
    }
}
