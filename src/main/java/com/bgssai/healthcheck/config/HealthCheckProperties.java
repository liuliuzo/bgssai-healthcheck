package com.bgssai.healthcheck.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 巡检平台的全部可配置项，前缀 {@code bgssai.healthcheck}。
 */
@Validated
@ConfigurationProperties(prefix = "bgssai.healthcheck")
public record HealthCheckProperties(

        /* 是否启用后台定时巡检；关闭后只能通过接口手动触发。 */
        @DefaultValue("true") boolean scheduled,

        /* 两轮巡检之间的间隔（上一轮结束到下一轮开始）。 */
        @DefaultValue("30s") Duration refreshInterval,

        /* 应用启动后首轮巡检的延迟。 */
        @DefaultValue("3s") Duration initialDelay,

        /* 单轮巡检允许的最大并发探测数。 */
        @Min(1) @Max(512) @DefaultValue("16") int concurrency,

        /* 每个应用保留的历史采样点数量，用于计算可用率和绘制趋势条。 */
        @Min(1) @Max(2000) @DefaultValue("60") int historySize,

        /* 页面自动刷新间隔（秒），0 表示不自动刷新。 */
        @Min(0) @Max(3600) @DefaultValue("10") int uiRefreshSeconds,

        @DefaultValue @Valid Probe probe,

        @DefaultValue List<@Valid Target> applications) {

    /** 探测行为的全局默认值。 */
    public record Probe(
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout,
            /* 是否跟随 3xx 跳转；健康检查通常不希望跳转到登录页后被判定为正常。 */
            @DefaultValue("false") boolean followRedirects,
            /* 读取响应体的最大字节数，防止对端返回超大内容拖垮巡检。 */
            @Min(0) @DefaultValue("65536") int maxBodyBytes) {
    }

    /** 一个被监控的应用。 */
    public record Target(

            /* 唯一标识；留空时由名称自动推导。 */
            String id,

            @NotBlank String name,

            @DefaultValue("未分组") String group,

            /* 健康检查接口地址，例如 http://host:8080/actuator/health */
            @NotBlank String url,

            /* 只支持 GET / HEAD。 */
            @DefaultValue("GET") String method,

            @DefaultValue("true") boolean enabled,

            /* 关键应用异常时，平台自身的 /actuator/health 也会变为 DOWN。 */
            @DefaultValue("false") boolean critical,

            @DefaultValue List<String> tags,

            @DefaultValue Map<String, String> headers,

            /* HTTP Basic 认证，可选。 */
            String username,
            String password,

            /* 未配置时回退到 probe 的全局默认值。 */
            Duration connectTimeout,
            Duration readTimeout,

            /* 判定为「接口调用成功」的 HTTP 状态码；留空表示任意 2xx。 */
            @DefaultValue List<Integer> expectedStatuses,

            /* 备注，展示在看板上。 */
            String description) {
    }
}
