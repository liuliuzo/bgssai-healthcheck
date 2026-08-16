package com.bgssai.healthcheck;

import com.bgssai.healthcheck.alert.AlertProperties;
import com.bgssai.healthcheck.config.HealthCheckProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * BGSSAI 健康巡检平台入口。
 *
 * <p>平台周期性地调用被监控应用的健康检查接口，把结果缓存在内存中，
 * 通过 REST 接口和 Thymeleaf 看板对外展示。</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({ HealthCheckProperties.class, AlertProperties.class })
public class HealthCheckApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealthCheckApplication.class, args);
    }
}
