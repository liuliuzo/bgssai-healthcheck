package com.bgssai.healthcheck.alert;

import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.ProbeResult;
import com.bgssai.healthcheck.service.HealthStatusStore.Transition;
import com.bgssai.healthcheck.service.MonitoredApplication;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 告警状态机：决定一次探测结果要不要变成一条通知。
 *
 * <p>看板回答的是「现在怎么样」，告警回答的是「什么时候变了」——所以这里的输入不是状态本身，
 * 而是 {@link Transition}。每个目标最多只有一个进行中的故障（{@link Incident}），从首次探测到
 * 异常开始、到恢复正常结束，中间无论巡检多少轮都只在这几个时刻发通知：</p>
 *
 * <ol>
 *   <li>连续异常次数首次达到阈值 —— {@link AlertKind#FIRING}</li>
 *   <li>已在告警中，但异常程度变了（如降级转异常）—— 再发一条 {@link AlertKind#FIRING}</li>
 *   <li>配了 {@code repeat-interval} 且距上次通知已超过该间隔 —— {@link AlertKind#REMINDER}</li>
 *   <li>恢复正常 —— {@link AlertKind#RECOVERED}</li>
 * </ol>
 *
 * <p>没达到阈值就恢复的抖动<strong>两条都不发</strong>：既没告过警，就不该有恢复通知——
 * 只有「好了」没有「坏了」的通知，比没有通知更让人困惑。</p>
 */
@Service
public class AlertService {

    private final AlertProperties properties;

    private final AlertDispatcher dispatcher;

    /**
     * 进行中的故障，按目标 id 索引。恢复即移除，因此这张表的大小天然不超过目标数。
     *
     * <p>与巡检结果一样只放在内存里：平台重启后所有目标都会重新走一遍「连续 N 次才告警」，
     * 最多把重启期间就已存在的故障重报一次，这比把告警状态持久化再考虑一致性划算得多。</p>
     */
    private final Map<String, Incident> incidents = new ConcurrentHashMap<>();

    public AlertService(AlertProperties properties, AlertDispatcher dispatcher) {
        this.properties = properties;
        this.dispatcher = dispatcher;
    }

    /**
     * 一个永不告警的实例。
     *
     * <p>给那些要构造 {@link com.bgssai.healthcheck.service.HealthCheckService} 但与告警无关的
     * 用例用——否则每个这样的用例都得手写一遍七个字段的 {@link AlertProperties}，
     * 而它们真正想表达的只是「这里不测告警」。不启用任何通道，也不会创建发送线程。</p>
     */
    public static AlertService disabled() {
        AlertProperties off = new AlertProperties(false, 1, false, Duration.ZERO, false, false,
                new AlertProperties.Webhook(null, AlertProperties.WebhookFormat.GENERIC, Duration.ofSeconds(3),
                        Duration.ofSeconds(5), Map.of()));
        return new AlertService(off, new AlertDispatcher(List.of()));
    }

    /**
     * 每次探测落库后调用。
     *
     * <p>由 {@link com.bgssai.healthcheck.service.HealthCheckService} 在巡检线程上直接调用，
     * 本方法只更新内存状态、把真正的发送交给 {@link AlertDispatcher} 异步做，因此不会拖慢巡检。</p>
     */
    public void onProbed(MonitoredApplication app, ProbeResult result, Transition transition) {
        if (!this.properties.enabled() || !this.dispatcher.hasEnabledChannel()) {
            return;
        }
        if (this.properties.onlyCritical() && !app.critical()) {
            return;
        }

        // 用 compute 而不是「读—判断—写」：单个目标的手动重检不走全量巡检那把锁，
        // 同一个 id 可能被两个线程同时走到这里，分成三步会把一次故障报成两条告警。
        AtomicReference<AlertEvent> pending = new AtomicReference<>();
        this.incidents.compute(app.id(), (id, current) -> advance(app, result, transition, current, pending));

        AlertEvent event = pending.get();
        if (event != null) {
            this.dispatcher.dispatch(event);
        }
    }

    /** 当前的告警配置与进行中的故障，供 {@code GET /api/alerts} 展示。 */
    public AlertStatus status() {
        List<AlertStatus.Firing> firing = this.incidents.values().stream()
                .filter(Incident::notified)
                .sorted(Comparator.comparing(Incident::since))
                .map(incident -> new AlertStatus.Firing(incident.applicationId(), incident.applicationName(),
                        incident.group(), incident.critical(), incident.state(), incident.since(),
                        incident.lastNotifiedAt(), incident.notifications()))
                .toList();
        return new AlertStatus(this.properties.enabled(), this.properties.failureThreshold(),
                this.properties.repeatInterval(), this.properties.onlyCritical(),
                this.dispatcher.enabledChannels(), firing);
    }

    /**
     * 状态机的一步：给定当前故障与本次探测，算出新的故障状态，并在需要通知时写入 {@code pending}。
     *
     * <p>返回 {@code null} 表示该目标此刻没有进行中的故障。整个方法没有副作用（除了写
     * {@code pending}），因此可以安全地放在 {@code compute} 里被重试。</p>
     */
    private Incident advance(MonitoredApplication app, ProbeResult result, Transition transition, Incident current,
            AtomicReference<AlertEvent> pending) {
        HealthState state = transition.current();
        Instant at = (result.checkedAt() != null) ? result.checkedAt() : Instant.now();

        if (state == HealthState.UP) {
            if (current == null) {
                return null;
            }
            // 只有真的告过警才发恢复通知，否则就是「没坏过却宣布好了」
            if (current.notified() && this.properties.recoveryNotice()) {
                pending.set(event(AlertKind.RECOVERED, app, result, transition, current.since(), at));
            }
            return null;
        }

        if (!countsAsProblem(state)) {
            // UNKNOWN 且未计入异常：说不准不等于恢复，维持原状不动
            return current;
        }

        Instant since = (current != null) ? current.since() : at;
        Incident observed = new Incident(app.id(), app.name(), app.group(), app.critical(), state, since,
                (current != null) ? current.lastNotifiedAt() : null,
                (current != null) ? current.notifications() : 0);

        if (transition.consecutiveFailures() < this.properties.failureThreshold()) {
            // 还在抖动窗口内：记下故障起点，但先不打扰任何人
            return observed;
        }

        if (!observed.notified()) {
            pending.set(event(AlertKind.FIRING, app, result, transition, since, at));
            return observed.notifiedAt(at);
        }
        if (observed.state() != current.state()) {
            // 降级转异常（或反过来）是新的事实，值得再响一次
            pending.set(event(AlertKind.FIRING, app, result, transition, since, at));
            return observed.notifiedAt(at);
        }
        if (dueForReminder(observed, at)) {
            pending.set(event(AlertKind.REMINDER, app, result, transition, since, at));
            return observed.notifiedAt(at);
        }
        return observed;
    }

    /** 是否配了重复提醒，且距上次通知已经够久。 */
    private boolean dueForReminder(Incident incident, Instant at) {
        Duration repeat = this.properties.repeatInterval();
        if (repeat == null || repeat.isZero() || repeat.isNegative() || incident.lastNotifiedAt() == null) {
            return false;
        }
        return !at.isBefore(incident.lastNotifiedAt().plus(repeat));
    }

    private boolean countsAsProblem(HealthState state) {
        return switch (state) {
            case DOWN, DEGRADED -> true;
            case UNKNOWN -> this.properties.includeUnknown();
            case UP -> false;
        };
    }

    private static AlertEvent event(AlertKind kind, MonitoredApplication app, ProbeResult result,
            Transition transition, Instant since, Instant at) {
        return new AlertEvent(kind, app.id(), app.name(), app.group(), app.type(), app.uri().toString(),
                app.critical(), transition.current(), transition.previous(), transition.consecutiveFailures(),
                result.message(), result.httpStatus(), result.latencyMs(), since, at);
    }

    /**
     * 一个进行中的故障。
     *
     * @param since          首次探测到异常的时刻，未必等于首次通知的时刻（中间隔着阈值）
     * @param lastNotifiedAt 最近一次发出通知的时刻；还没通知过时为 {@code null}
     * @param notifications  已发出的通知条数，0 表示仍在抖动窗口内
     */
    private record Incident(String applicationId, String applicationName, String group, boolean critical,
            HealthState state, Instant since, Instant lastNotifiedAt, int notifications) {

        /** 是否已经就这次故障通知过。 */
        boolean notified() {
            return this.notifications > 0;
        }

        Incident notifiedAt(Instant at) {
            return new Incident(this.applicationId, this.applicationName, this.group, this.critical, this.state,
                    this.since, at, this.notifications + 1);
        }
    }
}
