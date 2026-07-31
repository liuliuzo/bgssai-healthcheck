package com.bgssai.healthcheck.domain;

import java.util.Locale;

/**
 * 归一化后的健康状态。
 *
 * <p>被监控应用可能返回 Spring Boot Actuator 风格的 {@code UP / DOWN / OUT_OF_SERVICE / UNKNOWN}，
 * 也可能只返回一个 HTTP 状态码，这里统一收敛为四种状态。</p>
 */
public enum HealthState {

    /** 接口可达且业务自报正常。 */
    UP("正常", 0),
    /** 状态无法确定，例如尚未巡检或应用已停用。 */
    UNKNOWN("未知", 1),
    /** 接口可达但自报降级 / 停止服务。已确认的部分故障，比「不知道」更严重。 */
    DEGRADED("降级", 2),
    /** 接口不可达，或自报异常。 */
    DOWN("异常", 3);

    private final String label;
    private final int severity;

    HealthState(String label, int severity) {
        this.label = label;
        this.severity = severity;
    }

    /** 中文展示名，供页面使用。 */
    public String getLabel() {
        return this.label;
    }

    /** 严重程度，数值越大越严重。 */
    public int getSeverity() {
        return this.severity;
    }

    /** 小写形式，供 CSS class 使用。 */
    public String getCode() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isHealthy() {
        return this == UP;
    }

    /** 取两者中更严重的一个。 */
    public HealthState worseOf(HealthState other) {
        if (other == null) {
            return this;
        }
        return (other.severity > this.severity) ? other : this;
    }

    /**
     * 把 Actuator 的状态字符串映射为本枚举，无法识别时返回 {@link #UNKNOWN}。
     */
    public static HealthState fromActuator(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "UP", "OK", "HEALTHY", "PASS", "SUCCESS" -> UP;
            case "DOWN", "ERROR", "FAIL", "UNHEALTHY", "CRITICAL" -> DOWN;
            case "OUT_OF_SERVICE", "OUT-OF-SERVICE", "DEGRADED", "WARN", "WARNING" -> DEGRADED;
            default -> UNKNOWN;
        };
    }
}
