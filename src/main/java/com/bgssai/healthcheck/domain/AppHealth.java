package com.bgssai.healthcheck.domain;

import java.time.Instant;
import java.util.List;

/**
 * 对外暴露的单个应用健康视图，REST 接口与看板共用。
 */
public record AppHealth(
        String id,
        String name,
        String group,
        String url,
        String description,
        List<String> tags,
        boolean critical,
        boolean enabled,
        HealthState state,
        Integer httpStatus,
        long latencyMs,
        Instant checkedAt,
        String message,
        List<ProbeResult.ComponentStatus> components,
        HealthStats stats,
        List<HealthSample> history) {

    public AppHealth {
        tags = (tags == null) ? List.of() : List.copyOf(tags);
        components = (components == null) ? List.of() : List.copyOf(components);
        history = (history == null) ? List.of() : List.copyOf(history);
    }
}
