package com.bgssai.healthcheck.alert;

import java.util.Locale;

/**
 * 一条告警属于哪一类。
 *
 * <p>三类都是「状态机的输出」而不是「当前状态」：同一个目标从异常到恢复，会先后产生
 * {@link #FIRING} 与 {@link #RECOVERED} 两条事件，接收方据此配对即可算出故障时长。</p>
 */
public enum AlertKind {

    /** 首次达到失败阈值，或异常程度发生了变化（如 DEGRADED 转 DOWN）。 */
    FIRING("告警"),
    /** 仍在异常中，按 {@code repeat-interval} 发出的重复提醒。 */
    REMINDER("提醒"),
    /** 目标恢复正常。 */
    RECOVERED("恢复");

    private final String label;

    AlertKind(String label) {
        this.label = label;
    }

    /** 中文展示名。 */
    public String getLabel() {
        return this.label;
    }

    /** 小写形式，供 CSS class 与报文使用。 */
    public String getCode() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 是否是「出事了」这一类——恢复通知不算。 */
    public boolean isProblem() {
        return this != RECOVERED;
    }
}
