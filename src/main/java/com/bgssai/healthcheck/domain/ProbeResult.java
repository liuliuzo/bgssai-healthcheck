package com.bgssai.healthcheck.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 一次探测的原始结果。
 *
 * @param state      归一化后的状态
 * @param httpStatus 对端返回的 HTTP 状态码；非 HTTP 协议或网络异常时为 {@code null}
 * @param latencyMs  从发起请求到读完响应体的耗时（毫秒）
 * @param checkedAt  探测发生的时刻
 * @param message    面向人的说明，正常时通常为空
 * @param components 对端自报的子组件状态，或探针从原始应答里解析出的组件
 * @param detail     原始请求与应答明细，供看板与报告展开查看；未保留时为 {@code null}
 */
public record ProbeResult(
        HealthState state,
        Integer httpStatus,
        long latencyMs,
        Instant checkedAt,
        String message,
        List<ComponentStatus> components,
        ProbeDetail detail) {

    public ProbeResult {
        components = (components == null) ? List.of() : List.copyOf(components);
    }

    public static ProbeResult of(HealthState state, Integer httpStatus, long latencyMs, Instant checkedAt,
            String message, List<ComponentStatus> components, ProbeDetail detail) {
        return new ProbeResult(state, httpStatus, latencyMs, checkedAt, message, components, detail);
    }

    /** 网络层失败（连接被拒、超时、DNS 解析失败等）。 */
    public static ProbeResult failure(long latencyMs, Instant checkedAt, String message, ProbeDetail detail) {
        return new ProbeResult(HealthState.DOWN, null, latencyMs, checkedAt, message, List.of(), detail);
    }

    /** 尚未巡检 / 已停用。 */
    public static ProbeResult unknown(Instant checkedAt, String message) {
        return new ProbeResult(HealthState.UNKNOWN, null, 0L, checkedAt, message, List.of(), null);
    }

    /** 按保留策略裁剪明细，返回可长期驻留内存的结果。 */
    public ProbeResult withDetail(ProbeDetail replacement) {
        return new ProbeResult(this.state, this.httpStatus, this.latencyMs, this.checkedAt, this.message,
                this.components, replacement);
    }

    /** 子组件状态，例如 db、redis、diskSpace。 */
    public record ComponentStatus(String name, HealthState state, Map<String, String> details) {

        public ComponentStatus {
            details = (details == null) ? Map.of() : Map.copyOf(details);
        }

        public static ComponentStatus of(String name, HealthState state) {
            return new ComponentStatus(name, state, Map.of());
        }
    }
}
