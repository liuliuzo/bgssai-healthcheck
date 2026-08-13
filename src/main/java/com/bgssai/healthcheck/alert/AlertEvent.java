package com.bgssai.healthcheck.alert;

import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.TargetType;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 一条待发出的告警。
 *
 * <p>事件是**不可变的快照**：它把发出那一刻的目标信息与探测结论一并带走，因此通道在异步
 * 发送时不需要再回头去查任何状态——等 Webhook 发出去时，目标可能已经又变了一轮。</p>
 *
 * @param previousState 上一次探测的状态；该目标第一次被探测时为 {@code null}
 * @param since         本次故障的起始时刻（首次探测到异常的时间），恢复通知据此算出故障时长
 * @param occurredAt    产生这条告警的探测时刻
 */
public record AlertEvent(
        AlertKind kind,
        String applicationId,
        String applicationName,
        String group,
        TargetType type,
        String url,
        boolean critical,
        HealthState state,
        HealthState previousState,
        int consecutiveFailures,
        String message,
        Integer httpStatus,
        long latencyMs,
        Instant since,
        Instant occurredAt) {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    /**
     * 一行标题，形如 {@code 【告警】用户中心（核心服务）异常}。
     *
     * <p>关键目标额外带一个前缀——收到通知的人第一眼要能分清「这条会拖垮平台自身健康」
     * 和「这条只是看板上红了一格」。</p>
     */
    public String title() {
        String prefix = (this.critical && this.kind.isProblem()) ? "【关键 · " + this.kind.getLabel() + "】"
                : "【" + this.kind.getLabel() + "】";
        return prefix + this.applicationName + "（" + this.group + "）" + this.state.getLabel();
    }

    /**
     * 面向人的多行正文，日志通道与各家机器人的 text 消息共用同一份。
     *
     * <p>只用换行与全角冒号排版，不带 Markdown：钉钉 / 企业微信 / 飞书的 text 消息都不渲染
     * Markdown，而日志里出现 {@code **} 只会碍眼。</p>
     */
    public String text() {
        StringBuilder sb = new StringBuilder(title());
        sb.append('\n').append("目标：").append(this.url).append("（").append(this.type.getLabel()).append("）");
        sb.append('\n').append("状态：").append(describeTransition());
        if (this.message != null && !this.message.isBlank()) {
            sb.append('\n').append("说明：").append(this.message);
        }
        if (this.httpStatus != null) {
            sb.append('\n').append("状态码：").append(this.httpStatus);
        }
        if (this.latencyMs > 0L) {
            sb.append('\n').append("耗时：").append(this.latencyMs).append(" ms");
        }
        if (this.kind == AlertKind.RECOVERED && this.since != null) {
            sb.append('\n').append("故障持续：").append(describeDuration(Duration.between(this.since, this.occurredAt)));
        }
        else if (this.kind.isProblem()) {
            sb.append('\n').append("连续异常：").append(this.consecutiveFailures).append(" 次");
        }
        sb.append('\n').append("时间：").append(timestamp());
        return sb.toString();
    }

    /** {@code yyyy-MM-dd HH:mm:ss}，按平台所在时区。 */
    public String timestamp() {
        return TIMESTAMP.format(this.occurredAt.atZone(ZoneId.systemDefault()));
    }

    /** 形如 {@code 正常 → 异常}；首次探测没有上一状态时只写当前状态。 */
    private String describeTransition() {
        if (this.previousState == null || this.previousState == this.state) {
            return this.state.getLabel();
        }
        return this.previousState.getLabel() + " → " + this.state.getLabel();
    }

    /** 故障时长，取最大的一个量级即可，精确到秒没有意义。 */
    private static String describeDuration(Duration duration) {
        long seconds = Math.max(0L, duration.getSeconds());
        if (seconds < 60L) {
            return seconds + " 秒";
        }
        if (seconds < 3600L) {
            return (seconds / 60L) + " 分 " + (seconds % 60L) + " 秒";
        }
        if (seconds < 86400L) {
            return (seconds / 3600L) + " 小时 " + ((seconds % 3600L) / 60L) + " 分";
        }
        return (seconds / 86400L) + " 天 " + ((seconds % 86400L) / 3600L) + " 小时";
    }
}
