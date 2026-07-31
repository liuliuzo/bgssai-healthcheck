package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * 按配置的间隔触发全量巡检。
 *
 * <p>用 {@link SchedulingConfigurer} 而不是 {@code @Scheduled} 注解，是为了让
 * 「是否启用」和「间隔」都直接来自同一份配置对象，避免注解里再写一遍占位符。</p>
 */
@Component
public class HealthCheckScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckScheduler.class);

    private final HealthCheckService healthCheckService;

    private final HealthCheckProperties properties;

    public HealthCheckScheduler(HealthCheckService healthCheckService, HealthCheckProperties properties) {
        this.healthCheckService = healthCheckService;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!this.properties.scheduled()) {
            log.info("定时巡检已关闭（bgssai.healthcheck.scheduled=false），只能通过接口手动触发");
            return;
        }
        log.info("定时巡检已启用：首轮延迟 {}，之后每隔 {} 执行一次", this.properties.initialDelay(),
                this.properties.refreshInterval());
        registrar.addFixedDelayTask(new FixedDelayTask(this::runOnce, this.properties.refreshInterval(),
                this.properties.initialDelay()));
    }

    private void runOnce() {
        try {
            this.healthCheckService.refreshAll();
        }
        catch (RuntimeException ex) {
            // 定时任务里抛出异常会让后续调度停摆，这里必须兜住
            log.error("定时巡检执行失败", ex);
        }
    }
}
