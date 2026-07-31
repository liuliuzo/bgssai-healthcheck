package com.bgssai.healthcheck.domain;

import java.time.Instant;

/**
 * 历史采样点，用于计算可用率并在页面上绘制趋势条。
 */
public record HealthSample(Instant at, HealthState state, long latencyMs) {
}
