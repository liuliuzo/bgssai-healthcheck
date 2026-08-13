package com.bgssai.healthcheck.alert;

/**
 * 一个告警通道。
 *
 * <p>与 {@link com.bgssai.healthcheck.service.HealthProbe} 同样的扩展方式：新增一个通道就是
 * 新增一个 {@code @Component}，{@link AlertDispatcher} 会自动把它收进来，别处不做 if-else 分发。</p>
 *
 * <p>实现约定两条：</p>
 * <ul>
 *   <li><strong>不抛异常</strong>——通道故障绝不能影响巡检本身，发不出去就自己记一条日志；
 *       {@link AlertDispatcher} 仍会兜一层，但那是兜底，不是许可。</li>
 *   <li><strong>可以阻塞</strong>——发送跑在专用的告警线程上，不占巡检的并发额度。</li>
 * </ul>
 */
public interface AlertNotifier {

    /** 通道名，用于日志与 {@code /api/alerts} 的展示。 */
    String name();

    /**
     * 当前是否可用。返回 {@code false} 的通道不会被调用，也不会出现在通道清单里——
     * 例如没配地址的 Webhook。
     */
    default boolean isEnabled() {
        return true;
    }

    /** 发送一条告警。 */
    void send(AlertEvent event);
}
