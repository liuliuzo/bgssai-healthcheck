package com.bgssai.healthcheck.alert;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.config.HealthCheckProperties.Detail;
import com.bgssai.healthcheck.config.HealthCheckProperties.Mysql;
import com.bgssai.healthcheck.config.HealthCheckProperties.Probe;
import com.bgssai.healthcheck.config.HealthCheckProperties.Redis;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.ProbeResult;
import com.bgssai.healthcheck.domain.TargetType;
import com.bgssai.healthcheck.service.HealthStatusStore;
import com.bgssai.healthcheck.service.MonitoredApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 告警状态机：什么时候该响、什么时候必须闭嘴。
 *
 * <p>用例不去构造 {@link HealthStatusStore.Transition}，而是把探测结果真的写进
 * {@link HealthStatusStore} 再拿它算出来的变化喂给 {@link AlertService}——「连续失败几次」
 * 这个计数本身就是告警的判据，手写一个假的 Transition 等于把要测的东西先假设成对的。</p>
 *
 * <p>发送侧换成同步的 {@link RecordingDispatcher}：状态机的每一条断言都是「这一次探测之后
 * 应不应该有通知」，中间夹一个异步队列只会让用例变成等待与超时的赌局。真正的异步投递由
 * {@link AlertDispatcherTests} 覆盖。</p>
 */
class AlertServiceTests {

    private static final Instant T0 = Instant.parse("2026-08-13T02:00:00Z");

    /** 巡检间隔，用来给各轮探测编出递增且可预期的时刻。 */
    private static final Duration ROUND = Duration.ofSeconds(30);

    private final HealthStatusStore store = new HealthStatusStore(properties());

    private final RecordingDispatcher dispatcher = new RecordingDispatcher();

    private int round;

    @Test
    @DisplayName("首次失败不告警：连续次数没到阈值之前一直闭嘴")
    void singleFailureStaysQuiet() {
        AlertService service = service(defaults().build());

        probe(service, app("blog-admin", false), HealthState.DOWN);

        assertThat(this.dispatcher.events()).as("阈值是 2，第一次失败就报会把偶发超时变成噪音").isEmpty();
    }

    @Test
    @DisplayName("连续失败达到阈值时告警一次，之后一直异常也不再重复")
    void firesOnceWhenThresholdReached() {
        AlertService service = service(defaults().build());
        MonitoredApplication app = app("blog-admin", false);

        probe(service, app, HealthState.DOWN);
        probe(service, app, HealthState.DOWN);
        probe(service, app, HealthState.DOWN);
        probe(service, app, HealthState.DOWN);

        assertThat(this.dispatcher.events()).hasSize(1);
        AlertEvent event = this.dispatcher.events().getFirst();
        assertThat(event.kind()).isEqualTo(AlertKind.FIRING);
        assertThat(event.state()).isEqualTo(HealthState.DOWN);
        assertThat(event.consecutiveFailures()).isEqualTo(2);
        assertThat(event.applicationId()).isEqualTo("blog-admin");
    }

    @Test
    @DisplayName("恢复时补一条恢复通知，故障起点是第一次失败而不是告警发出的那一刻")
    void recoveryNoticeCarriesTheRealIncidentStart() {
        AlertService service = service(defaults().build());
        MonitoredApplication app = app("blog-admin", false);

        Instant firstFailure = at(0);
        probe(service, app, HealthState.DOWN);
        probe(service, app, HealthState.DOWN);
        probe(service, app, HealthState.UP);

        assertThat(this.dispatcher.events()).hasSize(2);
        AlertEvent recovered = this.dispatcher.events().getLast();
        assertThat(recovered.kind()).isEqualTo(AlertKind.RECOVERED);
        assertThat(recovered.state()).isEqualTo(HealthState.UP);
        assertThat(recovered.previousState()).isEqualTo(HealthState.DOWN);
        assertThat(recovered.since())
                .as("故障时长要从第一次探测到异常算起，不能从「攒够两次」那一刻算")
                .isEqualTo(firstFailure);
        assertThat(recovered.text()).contains("故障持续");
    }

    @Test
    @DisplayName("没到阈值就恢复的抖动，告警与恢复通知都不发")
    void flappingBelowThresholdNotifiesNothing() {
        AlertService service = service(defaults().build());
        MonitoredApplication app = app("blog-admin", false);

        probe(service, app, HealthState.DOWN);
        probe(service, app, HealthState.UP);

        assertThat(this.dispatcher.events())
                .as("既没告过警，就不该冒出一条「恢复了」——只有下半句的通知比没有通知更让人困惑")
                .isEmpty();
    }

    @Test
    @DisplayName("异常程度变化（降级转异常）再响一次，反向恢复到降级也算变化")
    void escalationFiresAgain() {
        AlertService service = service(defaults().build());
        MonitoredApplication app = app("blog-admin", false);

        probe(service, app, HealthState.DEGRADED);
        probe(service, app, HealthState.DEGRADED);
        probe(service, app, HealthState.DOWN);
        probe(service, app, HealthState.DOWN);
        probe(service, app, HealthState.DEGRADED);

        assertThat(this.dispatcher.events()).extracting(AlertEvent::kind)
                .containsExactly(AlertKind.FIRING, AlertKind.FIRING, AlertKind.FIRING);
        assertThat(this.dispatcher.events()).extracting(AlertEvent::state)
                .containsExactly(HealthState.DEGRADED, HealthState.DOWN, HealthState.DEGRADED);
        assertThat(this.dispatcher.events().getLast().since())
                .as("程度变了还是同一次故障，起点不该被重置")
                .isEqualTo(at(0));
    }

    @Test
    @DisplayName("配了 repeat-interval 才会重复提醒，且要真的等够那么久")
    void reminderOnlyAfterRepeatInterval() {
        AlertService service = service(defaults().repeatInterval(Duration.ofMinutes(2)).build());
        MonitoredApplication app = app("blog-admin", false);

        // 每轮 30s：第 2 轮告警，第 3、4 轮还不够 2 分钟，第 6 轮才够
        for (int i = 0; i < 6; i++) {
            probe(service, app, HealthState.DOWN);
        }

        assertThat(this.dispatcher.events()).extracting(AlertEvent::kind)
                .containsExactly(AlertKind.FIRING, AlertKind.REMINDER);
        assertThat(this.dispatcher.events().getLast().occurredAt())
                .as("告警在第 2 轮（30s），距它满 2 分钟的是第 6 轮（150s）")
                .isEqualTo(at(5));
    }

    @Test
    @DisplayName("UNKNOWN 默认不算异常：既不告警，也不会被当成恢复")
    void unknownIsNeitherFailureNorRecovery() {
        AlertService service = service(defaults().build());
        MonitoredApplication app = app("blog-admin", false);

        probe(service, app, HealthState.DOWN);
        probe(service, app, HealthState.DOWN);
        this.dispatcher.clear();

        probe(service, app, HealthState.UNKNOWN);
        assertThat(this.dispatcher.events()).as("说不准不等于好了，不该发恢复通知").isEmpty();

        probe(service, app, HealthState.UP);
        assertThat(this.dispatcher.events()).extracting(AlertEvent::kind)
                .as("真的恢复时，那次故障仍然认得出来")
                .containsExactly(AlertKind.RECOVERED);
    }

    @Test
    @DisplayName("include-unknown 打开后 UNKNOWN 计入异常")
    void unknownCanBeCountedAsFailure() {
        AlertService service = service(defaults().includeUnknown(true).build());
        MonitoredApplication app = app("blog-admin", false);

        probe(service, app, HealthState.UNKNOWN);
        probe(service, app, HealthState.UNKNOWN);

        assertThat(this.dispatcher.events()).extracting(AlertEvent::state).containsExactly(HealthState.UNKNOWN);
    }

    @Test
    @DisplayName("only-critical 打开后只有关键目标会告警")
    void onlyCriticalSkipsOrdinaryTargets() {
        AlertService service = service(defaults().onlyCritical(true).build());

        MonitoredApplication ordinary = app("blog-admin", false);
        probe(service, ordinary, HealthState.DOWN);
        probe(service, ordinary, HealthState.DOWN);
        assertThat(this.dispatcher.events()).isEmpty();

        MonitoredApplication core = app("mysql-cn", true);
        probe(service, core, HealthState.DOWN);
        probe(service, core, HealthState.DOWN);
        assertThat(this.dispatcher.events()).extracting(AlertEvent::applicationId).containsExactly("mysql-cn");
        assertThat(this.dispatcher.events().getFirst().title()).startsWith("【关键 · 告警】");
    }

    @Test
    @DisplayName("enabled=false 时状态机完全不工作")
    void disabledEmitsNothing() {
        AlertService service = service(defaults().enabled(false).build());
        MonitoredApplication app = app("blog-admin", false);

        probe(service, app, HealthState.DOWN);
        probe(service, app, HealthState.DOWN);
        probe(service, app, HealthState.UP);

        assertThat(this.dispatcher.events()).isEmpty();
        assertThat(service.status().enabled()).isFalse();
    }

    @Test
    @DisplayName("/api/alerts 只列已经通知过、且还没恢复的故障")
    void statusListsOngoingIncidentsOnly() {
        AlertService service = service(defaults().build());
        MonitoredApplication firing = app("blog-admin", false);
        MonitoredApplication flapping = app("vpn-user", false);

        probe(service, firing, HealthState.DOWN);
        probe(service, firing, HealthState.DOWN);
        // 这一条只失败了一次，还在抖动窗口内
        probe(service, flapping, HealthState.DOWN);

        AlertStatus status = service.status();
        assertThat(status.enabled()).isTrue();
        assertThat(status.failureThreshold()).isEqualTo(2);
        assertThat(status.channels()).containsExactly("recording");
        assertThat(status.firing()).extracting(AlertStatus.Firing::applicationId).containsExactly("blog-admin");
        assertThat(status.firing().getFirst().since()).isEqualTo(at(0));

        probe(service, firing, HealthState.UP);
        assertThat(service.status().firing()).as("恢复之后就不该再挂在告警列表里").isEmpty();
    }

    // ---- 脚手架 ----

    /** 把一次探测结果写进 store，再把它算出的状态变化喂给状态机，与生产路径一致。 */
    private void probe(AlertService service, MonitoredApplication app, HealthState state) {
        Instant checkedAt = at(this.round++);
        ProbeResult result = new ProbeResult(state, (state == HealthState.UP) ? 200 : 503, 120L, checkedAt,
                (state == HealthState.UP) ? null : "接口返回 503 Service Unavailable", List.of(), null);
        service.onProbed(app, result, this.store.record(app.id(), result));
    }

    /** 第 n 轮巡检的时刻。 */
    private static Instant at(int round) {
        return T0.plus(ROUND.multipliedBy(round));
    }

    private AlertService service(AlertProperties properties) {
        return new AlertService(properties, this.dispatcher);
    }

    private static AlertPropertiesBuilder defaults() {
        return new AlertPropertiesBuilder();
    }

    private static MonitoredApplication app(String id, boolean critical) {
        return new MonitoredApplication(id, "博客 管理端", "博客", null, TargetType.HTTP,
                URI.create("https://10.0.0.1/bgssai/health/readiness"), HttpMethod.GET, true, critical, List.of(),
                Map.of(), null, null, null, Duration.ofSeconds(3), Duration.ofSeconds(5), Set.of(), List.of(), true);
    }

    private static HealthCheckProperties properties() {
        return new HealthCheckProperties(false, Duration.ofSeconds(30), Duration.ofSeconds(3), 4, 10, 0,
                new Probe(Duration.ofSeconds(1), Duration.ofSeconds(1), false, 4096, false),
                new Detail(true, 4096, true), new Redis(90), new Mysql(90, 3), List.of());
    }

    /**
     * 同步的投递器：直接把事件收进列表，不经过线程池。
     *
     * <p>{@link AlertDispatcher#dispatch} 是本类唯一需要拦下来的行为，
     * 覆写它比给状态机再抽一层接口划算。</p>
     */
    private static final class RecordingDispatcher extends AlertDispatcher {

        private final List<AlertEvent> events = new ArrayList<>();

        private RecordingDispatcher() {
            super(List.of(new RecordingNotifier()));
        }

        @Override
        public void dispatch(AlertEvent event) {
            this.events.add(event);
        }

        List<AlertEvent> events() {
            return this.events;
        }

        void clear() {
            this.events.clear();
        }
    }

    /** 只为让 {@code hasEnabledChannel()} 与通道清单有内容，不会真的被调用。 */
    private static final class RecordingNotifier implements AlertNotifier {

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public void send(AlertEvent event) {
            throw new AssertionError("同步投递器不会走到通道");
        }
    }

    /** {@link AlertProperties} 有七个字段，逐个用例写一遍构造器会盖掉真正想说的那一句。 */
    private static final class AlertPropertiesBuilder {

        private boolean enabled = true;

        private int failureThreshold = 2;

        private boolean recoveryNotice = true;

        private Duration repeatInterval = Duration.ZERO;

        private boolean includeUnknown;

        private boolean onlyCritical;

        AlertPropertiesBuilder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        AlertPropertiesBuilder includeUnknown(boolean value) {
            this.includeUnknown = value;
            return this;
        }

        AlertPropertiesBuilder onlyCritical(boolean value) {
            this.onlyCritical = value;
            return this;
        }

        AlertPropertiesBuilder repeatInterval(Duration value) {
            this.repeatInterval = value;
            return this;
        }

        AlertProperties build() {
            return new AlertProperties(this.enabled, this.failureThreshold, this.recoveryNotice, this.repeatInterval,
                    this.includeUnknown, this.onlyCritical, new AlertProperties.Webhook(null,
                            AlertProperties.WebhookFormat.GENERIC, Duration.ofSeconds(3), Duration.ofSeconds(5),
                            Map.of()));
        }
    }
}
