package com.bgssai.healthcheck.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 把告警写进日志。
 *
 * <p>始终启用，也不需要任何配置。这不是「兜底通道」而是主通道之一：本仓的日志会被
 * <a href="https://github.com/liuliuzo/bgssai-logs">bgssai-logs</a> 采走，因此零配置就有一份
 * 可检索、可留存的告警记录；Webhook 则解决「立刻有人看到」，两者互补。</p>
 *
 * <p>刻意写成<strong>单行</strong>而不是 {@link AlertEvent#text()} 的多行正文：告警最常见的用法是
 * 事后 {@code grep 告警} 拉出一段时间内的全部故障，多行会把每条记录拆散。人读的多行版本留给
 * 聊天机器人。</p>
 */
@Component
@Order(0)
public class LoggingAlertNotifier implements AlertNotifier {

    /**
     * 独立的 logger 名而不是本类的类名：告警行与巡检的调试日志混在
     * {@code com.bgssai.healthcheck.alert} 下不好分开，用一个固定名字，
     * 采集侧与运维可以直接按它筛。
     */
    private static final Logger log = LoggerFactory.getLogger("com.bgssai.healthcheck.ALERT");

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void send(AlertEvent event) {
        String line = format(event);
        // 恢复是好消息，用 INFO；其余都是要有人处理的，用 WARN。
        // 这里不用 ERROR：ERROR 在本产品线的口径里表示「本平台自己出错了」，
        // 而被监控方挂掉恰恰说明本平台在正常工作。
        if (event.kind() == AlertKind.RECOVERED) {
            log.info("{}", line);
        }
        else {
            log.warn("{}", line);
        }
    }

    /** 单行、可 grep 的告警记录。 */
    private static String format(AlertEvent event) {
        StringBuilder sb = new StringBuilder("[").append(event.kind().getLabel()).append(']');
        if (event.critical() && event.kind().isProblem()) {
            sb.append("[关键]");
        }
        sb.append(" app=").append(event.applicationId());
        sb.append(" name=").append(event.applicationName());
        sb.append(" group=").append(event.group());
        sb.append(" type=").append(event.type().name());
        sb.append(" state=").append(event.state().name());
        if (event.previousState() != null) {
            sb.append(" prev=").append(event.previousState().name());
        }
        if (event.kind().isProblem()) {
            sb.append(" consecutive=").append(event.consecutiveFailures());
        }
        if (event.httpStatus() != null) {
            sb.append(" http=").append(event.httpStatus());
        }
        sb.append(" latency=").append(event.latencyMs()).append("ms");
        sb.append(" url=").append(event.url());
        if (event.message() != null && !event.message().isBlank()) {
            // 说明来自对端的应答，可能带换行；压成一行才不会破坏「一条告警一行」
            sb.append(" message=").append(event.message().replaceAll("\\s+", " ").trim());
        }
        return sb.toString();
    }
}
