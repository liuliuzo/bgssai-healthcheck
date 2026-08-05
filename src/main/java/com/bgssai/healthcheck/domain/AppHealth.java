package com.bgssai.healthcheck.domain;

import java.time.Instant;
import java.util.List;

/**
 * 对外暴露的单个监控目标健康视图，REST 接口、看板与报告共用。
 *
 * @param detail      最近一次探测的原始请求 / 应答明细，未保留或尚未探测时为 {@code null}
 * @param lastFailure 最近一次非正常探测的完整结果，用于恢复后回溯；从未失败时为 {@code null}
 */
public record AppHealth(
        String id,
        String name,
        String group,
        TargetType type,
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
        ProbeDetail detail,
        HealthStats stats,
        List<HealthSample> history,
        ProbeResult lastFailure) {

    public AppHealth {
        tags = (tags == null) ? List.of() : List.copyOf(tags);
        components = (components == null) ? List.of() : List.copyOf(components);
        history = (history == null) ? List.of() : List.copyOf(history);
    }

    /** 是否有可展开查看的明细（原始应答、响应头或错误说明）。 */
    public boolean hasDetail() {
        return this.detail != null;
    }

    /**
     * 最近一次失败是否值得单独展示：当前已经恢复、且确实失败过时才有意义。
     * 当前就处在失败状态时，{@link #detail()} 本身就是失败明细，不必重复展示。
     */
    public boolean hasRecoveredFailure() {
        return this.lastFailure != null && this.state == HealthState.UP;
    }
}
