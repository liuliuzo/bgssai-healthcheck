package com.bgssai.healthcheck.domain;

import java.time.Instant;

/**
 * 单个应用的累计统计（进程内存活，重启后归零）。
 *
 * @param totalChecks          累计探测次数
 * @param upChecks             其中判定为正常的次数
 * @param uptimePercent        可用率，保留两位小数
 * @param avgLatencyMs         平均耗时，只统计成功拿到响应的探测
 * @param maxLatencyMs         最大耗时
 * @param consecutiveFailures  当前连续失败次数
 * @param lastUpAt             最近一次正常的时刻
 * @param lastDownAt           最近一次异常的时刻
 */
public record HealthStats(
        int totalChecks,
        int upChecks,
        double uptimePercent,
        long avgLatencyMs,
        long maxLatencyMs,
        int consecutiveFailures,
        Instant lastUpAt,
        Instant lastDownAt) {

    public static HealthStats empty() {
        return new HealthStats(0, 0, 0.0d, 0L, 0L, 0, null, null);
    }
}
