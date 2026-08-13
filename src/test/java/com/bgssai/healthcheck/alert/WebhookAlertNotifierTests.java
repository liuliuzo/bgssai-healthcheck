package com.bgssai.healthcheck.alert;

import com.bgssai.healthcheck.alert.AlertProperties.Webhook;
import com.bgssai.healthcheck.alert.AlertProperties.WebhookFormat;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.TargetType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Webhook 通道：报文长什么样，以及对端出各种问题时它是不是真的不抛异常。
 *
 * <p>对着一个真的 HTTP 服务发，不是对 mock 断言——这里要验的恰恰是「序列化之后在线上的那一串
 * 到底是什么」，以及三家机器人各自的报文结构，用 mock 就把要测的东西绕过去了。</p>
 */
class WebhookAlertNotifierTests {

    private StubWebhookServer server;

    @BeforeEach
    void startServer() {
        this.server = new StubWebhookServer();
    }

    @AfterEach
    void stopServer() {
        this.server.close();
    }

    @Test
    @DisplayName("generic 报文带齐字段，时间是 ISO-8601")
    void genericPayloadCarriesEveryField() throws InterruptedException {
        notifier(this.server.url("/hook"), WebhookFormat.GENERIC, Map.of()).send(event(AlertKind.FIRING));

        StubWebhookServer.Request request = this.server.take();
        assertThat(request.contentType()).contains("application/json");
        assertThat(request.body()).contains("\"kind\":\"firing\"")
                .contains("\"applicationId\":\"blog-admin\"")
                .contains("\"state\":\"DOWN\"")
                .contains("\"previousState\":\"UP\"")
                .contains("\"consecutiveFailures\":2")
                .contains("\"httpStatus\":503")
                .contains("\"critical\":false")
                .contains("\"occurredAt\":\"2026-08-13T02:00:30Z\"")
                .contains("\"since\":\"2026-08-13T02:00:00Z\"");
    }

    @Test
    @DisplayName("钉钉 / 企业微信报文是 msgtype=text，正文就是人读的那一段")
    void dingtalkPayloadIsATextMessage() throws InterruptedException {
        notifier(this.server.url("/hook"), WebhookFormat.DINGTALK, Map.of()).send(event(AlertKind.FIRING));

        String body = this.server.take().body();
        assertThat(body).contains("\"msgtype\":\"text\"").contains("\"content\":");
        assertThat(body).as("聊天机器人收到的是一段人读的正文，不是本平台的字段结构")
                .doesNotContain("applicationId")
                .doesNotContain("consecutiveFailures");
    }

    @Test
    @DisplayName("三家机器人的报文结构各按各的来")
    void everyChatFormatUsesItsOwnShape() {
        AlertEvent event = event(AlertKind.FIRING);

        assertThat(WebhookAlertNotifier.payload(event, WebhookFormat.WECOM))
                .isEqualTo(Map.of("msgtype", "text", "text", Map.of("content", event.text())));
        assertThat(WebhookAlertNotifier.payload(event, WebhookFormat.DINGTALK))
                .isEqualTo(WebhookAlertNotifier.payload(event, WebhookFormat.WECOM));
        assertThat(WebhookAlertNotifier.payload(event, WebhookFormat.FEISHU))
                .as("飞书用的是 msg_type / content.text，与钉钉不通用")
                .isEqualTo(Map.of("msg_type", "text", "content", Map.of("text", event.text())));
    }

    @Test
    @DisplayName("配置的附加请求头会带上")
    void customHeadersAreSent() throws InterruptedException {
        notifier(this.server.url("/hook"), WebhookFormat.GENERIC, Map.of("X-Gateway-Token", "abc"))
                .send(event(AlertKind.FIRING));

        assertThat(this.server.take().gatewayToken()).isEqualTo("abc");
    }

    @Test
    @DisplayName("没配地址就整个通道不启用，send 是空操作")
    void blankUrlDisablesTheChannel() {
        WebhookAlertNotifier notifier = notifier("", WebhookFormat.GENERIC, Map.of());

        assertThat(notifier.isEnabled()).isFalse();
        assertThatCode(() -> notifier.send(event(AlertKind.FIRING))).doesNotThrowAnyException();
        assertThat(this.server.received()).isZero();
    }

    @Test
    @DisplayName("对端返回 500、连不上、或 200 但带非零 errcode，都只记日志不抛异常")
    void deliveryFailuresNeverPropagate() {
        AlertEvent event = event(AlertKind.FIRING);

        assertThatCode(() -> notifier(this.server.url("/boom"), WebhookFormat.GENERIC, Map.of()).send(event))
                .as("HTTP 500")
                .doesNotThrowAnyException();
        assertThatCode(() -> notifier(this.server.url("/rejected"), WebhookFormat.DINGTALK, Map.of()).send(event))
                .as("200 + errcode=310000，机器人其实没发出去")
                .doesNotThrowAnyException();
        assertThatCode(() -> notifier("http://127.0.0.1:1/hook", WebhookFormat.GENERIC, Map.of()).send(event))
                .as("连不上")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("恢复通知也照常发得出去")
    void recoveryIsDeliveredToo() throws InterruptedException {
        notifier(this.server.url("/hook"), WebhookFormat.GENERIC, Map.of()).send(event(AlertKind.RECOVERED));

        assertThat(this.server.take().body()).contains("\"kind\":\"recovered\"");
    }

    @Test
    @DisplayName("非零 errcode 只对聊天机器人算失败，generic 不套用这条约定")
    void errorCodeConventionAppliesToChatBotsOnly() {
        String rejected = "{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}";

        assertThat(WebhookAlertNotifier.businessFailure(rejected, WebhookFormat.DINGTALK)).isNotNull();
        assertThat(WebhookAlertNotifier.businessFailure("{\"code\":1,\"msg\":\"bad\"}", WebhookFormat.FEISHU))
                .isNotNull();
        assertThat(WebhookAlertNotifier.businessFailure("{\"errcode\":0}", WebhookFormat.WECOM))
                .as("0 就是成功")
                .isNull();
        assertThat(WebhookAlertNotifier.businessFailure("{\"code\":200,\"msg\":\"ok\"}", WebhookFormat.GENERIC))
                .as("自建网关返回 code=200 完全可能就是成功，套用机器人的约定只会造出假警报")
                .isNull();
        assertThat(WebhookAlertNotifier.businessFailure("", WebhookFormat.DINGTALK)).isNull();
    }

    @Test
    @DisplayName("日志里的地址一律抹掉 access_token")
    void urlIsMaskedForLogging() {
        assertThat(WebhookAlertNotifier.safeUrl("https://oapi.dingtalk.com/robot/send?access_token=secret"))
                .isEqualTo("https://oapi.dingtalk.com/robot/send?***")
                .doesNotContain("secret");
        assertThat(WebhookAlertNotifier.safeUrl("https://hooks.internal/alert"))
                .as("没有查询串就没什么好抹的")
                .isEqualTo("https://hooks.internal/alert");
        assertThat(WebhookAlertNotifier.safeUrl("https://user:pass@hooks.internal/alert"))
                .doesNotContain("pass");
        assertThat(WebhookAlertNotifier.safeUrl("http://a b c?token=secret"))
                .as("地址本身不合法时，宁可只回显 scheme")
                .doesNotContain("secret");
        assertThat(WebhookAlertNotifier.safeUrl(null)).isEqualTo("(未配置)");
    }

    private static WebhookAlertNotifier notifier(String url, WebhookFormat format, Map<String, String> headers) {
        AlertProperties properties = new AlertProperties(true, 2, true, Duration.ZERO, false, false,
                new Webhook(url, format, Duration.ofSeconds(2), Duration.ofSeconds(2), headers));
        return new WebhookAlertNotifier(RestClient.builder(), properties);
    }

    private static AlertEvent event(AlertKind kind) {
        HealthState state = (kind == AlertKind.RECOVERED) ? HealthState.UP : HealthState.DOWN;
        return new AlertEvent(kind, "blog-admin", "博客 管理端", "博客", TargetType.HTTP,
                "https://10.0.0.1/bgssai/health/readiness", false, state, HealthState.UP, 2,
                "接口返回 503 Service Unavailable", 503, 120L, Instant.parse("2026-08-13T02:00:00Z"),
                Instant.parse("2026-08-13T02:00:30Z"));
    }

    /** 收下 POST 并把请求原样留档的假机器人。 */
    private static final class StubWebhookServer implements AutoCloseable {

        private final HttpServer server;

        private final BlockingQueue<Request> requests = new ArrayBlockingQueue<>(16);

        private final AtomicInteger count = new AtomicInteger();

        private StubWebhookServer() {
            try {
                this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            }
            catch (IOException ex) {
                throw new IllegalStateException("无法启动测试用 HTTP 服务", ex);
            }
            this.server.setExecutor(Executors.newFixedThreadPool(2));
            this.server.createContext("/hook", exchange -> respond(exchange, 200, "{\"errcode\":0}"));
            // 对端整个挂了
            this.server.createContext("/boom", exchange -> respond(exchange, 500, "boom"));
            // 钉钉的典型失效：HTTP 200，但报文被拒（关键词不匹配 / 机器人被停用）
            this.server.createContext("/rejected",
                    exchange -> respond(exchange, 200, "{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}"));
            this.server.start();
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] request = exchange.getRequestBody().readAllBytes();
            this.count.incrementAndGet();
            this.requests.offer(new Request(new String(request, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("X-Gateway-Token")));

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        }

        String url(String path) {
            return "http://127.0.0.1:" + this.server.getAddress().getPort() + path;
        }

        Request take() throws InterruptedException {
            Request request = this.requests.poll(5L, TimeUnit.SECONDS);
            assertThat(request).as("假机器人没有收到任何请求").isNotNull();
            return request;
        }

        int received() {
            return this.count.get();
        }

        @Override
        public void close() {
            this.server.stop(0);
        }

        private record Request(String body, String contentType, String gatewayToken) {
        }
    }
}
