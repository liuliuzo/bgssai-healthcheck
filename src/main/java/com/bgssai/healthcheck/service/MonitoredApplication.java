package com.bgssai.healthcheck.service;

import org.springframework.http.HttpMethod;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置解析后的被监控应用：所有可选项都已经填上最终值。
 */
public record MonitoredApplication(
        String id,
        String name,
        String group,
        String description,
        URI uri,
        HttpMethod method,
        boolean enabled,
        boolean critical,
        List<String> tags,
        Map<String, String> headers,
        /** 已经编码好的 Authorization 头，未配置认证时为 {@code null}。 */
        String authorization,
        Duration connectTimeout,
        Duration readTimeout,
        /** 空集合表示「任意 2xx 都算成功」。 */
        Set<Integer> expectedStatuses) {

    public MonitoredApplication {
        tags = (tags == null) ? List.of() : List.copyOf(tags);
        headers = (headers == null) ? Map.of() : Map.copyOf(headers);
        expectedStatuses = (expectedStatuses == null) ? Set.of() : Set.copyOf(expectedStatuses);
    }
}
