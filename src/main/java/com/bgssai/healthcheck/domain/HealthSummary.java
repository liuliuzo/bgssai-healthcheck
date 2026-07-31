package com.bgssai.healthcheck.domain;

import java.time.Instant;

/**
 * 全量应用的汇总视图。
 *
 * @param overall  所有已启用应用中最严重的状态
 * @param total    配置的应用总数（含已停用）
 * @param disabled 已停用、不参与巡检的应用数量
 */
public record HealthSummary(
        HealthState overall,
        int total,
        int up,
        int degraded,
        int down,
        int unknown,
        int disabled,
        Instant generatedAt,
        Instant lastCheckedAt,
        int uiRefreshSeconds) {
}
