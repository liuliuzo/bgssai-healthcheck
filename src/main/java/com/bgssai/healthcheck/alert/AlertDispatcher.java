package com.bgssai.healthcheck.alert;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 把告警交给全部启用的通道，在专用线程上异步发送。
 *
 * <p>为什么必须异步：{@link AlertService#onProbed} 跑在巡检线程上，而那个线程正持有一张
 * 并发额度（{@code bgssai.healthcheck.concurrency} 的信号量）。若在那里同步等一次 Webhook
 * 往返，一个响应慢的机器人就会拖住整轮巡检——监控平台因为发告警而漏了下一轮探测，是最难堪的
 * 一种失效。</p>
 *
 * <p>队列有界且**满了就丢**：告警积压说明要么对端挂了、要么故障面太大，这两种情况下把内存
 * 堆满都比丢几条通知更糟；丢弃本身会记一条 ERROR，不会静默。</p>
 */
@Component
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    /** 队列容量。按 19 个目标算，积到 256 条说明通道已经堵了很久。 */
    private static final int QUEUE_CAPACITY = 256;

    private final List<AlertNotifier> notifiers;

    private final ThreadPoolExecutor executor;

    public AlertDispatcher(List<AlertNotifier> notifiers) {
        this.notifiers = notifiers;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    // 守护线程：JVM 退出时不该被一条发不出去的告警拖着不结束
                    Thread thread = new Thread(runnable, "healthcheck-alert");
                    thread.setDaemon(true);
                    return thread;
                }, (runnable, pool) -> log.error("告警队列已满（{} 条待发），本条被丢弃——请检查通道是否可达",
                        QUEUE_CAPACITY));
    }

    /** 当前启用的通道名，供 {@code /api/alerts} 展示。 */
    public List<String> enabledChannels() {
        return this.notifiers.stream().filter(AlertNotifier::isEnabled).map(AlertNotifier::name).toList();
    }

    /** 是否有任何通道可用。一个都没有时，产生告警只是白白维护状态机。 */
    public boolean hasEnabledChannel() {
        return this.notifiers.stream().anyMatch(AlertNotifier::isEnabled);
    }

    /** 异步投递一条告警。调用方不会因此阻塞，也不会看到任何异常。 */
    public void dispatch(AlertEvent event) {
        this.executor.execute(() -> deliver(event));
    }

    /**
     * 逐个通道发送。单个通道抛异常只影响它自己——一个坏掉的 Webhook 不能让日志通道也哑掉。
     */
    private void deliver(AlertEvent event) {
        for (AlertNotifier notifier : this.notifiers) {
            if (!notifier.isEnabled()) {
                continue;
            }
            try {
                notifier.send(event);
            }
            catch (Exception ex) {
                log.warn("告警通道 [{}] 发送 [{}] 时出错", notifier.name(), event.applicationId(), ex);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        this.executor.shutdownNow();
    }
}
