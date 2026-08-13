package com.bgssai.healthcheck.alert;

import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步投递：一个坏掉的通道不能连累其它通道，也不能连累巡检。
 */
class AlertDispatcherTests {

    @Test
    @DisplayName("事件异步送达全部启用的通道")
    void deliversToEveryEnabledChannel() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(2);
        CollectingNotifier first = new CollectingNotifier("first", delivered);
        CollectingNotifier second = new CollectingNotifier("second", delivered);
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(first, second));

        dispatcher.dispatch(event());

        assertThat(delivered.await(5L, TimeUnit.SECONDS)).as("两个通道都该收到").isTrue();
        assertThat(dispatcher.enabledChannels()).containsExactly("first", "second");
    }

    @Test
    @DisplayName("未启用的通道不会被调用，也不出现在通道清单里")
    void skipsDisabledChannels() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicBoolean touched = new AtomicBoolean();
        AlertNotifier off = new AlertNotifier() {

            @Override
            public String name() {
                return "off";
            }

            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public void send(AlertEvent event) {
                touched.set(true);
            }
        };
        CollectingNotifier on = new CollectingNotifier("on", delivered);
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(off, on));

        dispatcher.dispatch(event());

        assertThat(delivered.await(5L, TimeUnit.SECONDS)).isTrue();
        assertThat(touched).isFalse();
        assertThat(dispatcher.enabledChannels()).containsExactly("on");
        assertThat(dispatcher.hasEnabledChannel()).isTrue();
    }

    @Test
    @DisplayName("一个通道抛异常，排在它后面的通道照常收到")
    void oneBrokenChannelDoesNotStopTheRest() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(1);
        AlertNotifier broken = new AlertNotifier() {

            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void send(AlertEvent event) {
                throw new IllegalStateException("机器人被停用了");
            }
        };
        CollectingNotifier healthy = new CollectingNotifier("healthy", delivered);
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(broken, healthy));

        dispatcher.dispatch(event());

        assertThat(delivered.await(5L, TimeUnit.SECONDS))
                .as("日志通道不该因为 Webhook 挂了就跟着哑掉")
                .isTrue();
    }

    @Test
    @DisplayName("一个不启用的通道都没有时，状态机不必再维护任何东西")
    void reportsWhenNothingCanReceive() {
        AlertDispatcher dispatcher = new AlertDispatcher(List.of());

        assertThat(dispatcher.hasEnabledChannel()).isFalse();
        assertThat(dispatcher.enabledChannels()).isEmpty();
    }

    private static AlertEvent event() {
        return new AlertEvent(AlertKind.FIRING, "blog-admin", "博客 管理端", "博客", TargetType.HTTP,
                "https://10.0.0.1/bgssai/health/readiness", false, HealthState.DOWN, HealthState.UP, 2,
                "接口返回 503 Service Unavailable", 503, 120L, Instant.parse("2026-08-13T02:00:00Z"),
                Instant.parse("2026-08-13T02:00:30Z"));
    }

    private static final class CollectingNotifier implements AlertNotifier {

        private final String name;

        private final CountDownLatch latch;

        private CollectingNotifier(String name, CountDownLatch latch) {
            this.name = name;
            this.latch = latch;
        }

        @Override
        public String name() {
            return this.name;
        }

        @Override
        public void send(AlertEvent event) {
            this.latch.countDown();
        }
    }
}
