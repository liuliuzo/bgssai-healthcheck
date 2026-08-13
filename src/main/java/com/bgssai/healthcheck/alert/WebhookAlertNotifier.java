package com.bgssai.healthcheck.alert;

import com.bgssai.healthcheck.alert.AlertProperties.WebhookFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把告警 POST 到一个 Webhook 地址。
 *
 * <p>没配 {@code bgssai.healthcheck.alert.webhook.url} 时整个通道不启用，
 * 不会构造任何客户端、也不会出现在 {@code /api/alerts} 的通道清单里。</p>
 *
 * <p>报文格式由 {@code format} 决定。之所以内置三家聊天机器人的格式而不是只发一份通用 JSON：
 * 钉钉 / 企业微信 / 飞书的机器人各有各的报文结构，只给通用 JSON 的话，每个想接机器人的人都得
 * 自己再搭一个转换服务，而这三种格式加起来不过十几行。</p>
 */
@Component
@Order(10)
public class WebhookAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertNotifier.class);

    /** 钉钉 / 企业微信用 errcode，飞书用 code；两者都以 0 表示成功。 */
    private static final Pattern ERROR_CODE = Pattern.compile("\"(?:errcode|code)\"\\s*:\\s*(-?\\d+)");

    /** 出错时回显的响应体上限，机器人的应答本就很短，超出的部分没有价值。 */
    private static final int MAX_ECHO_CHARS = 512;

    private final AlertProperties.Webhook settings;

    /** 未启用时为 {@code null}。 */
    private final RestClient client;

    public WebhookAlertNotifier(RestClient.Builder restClientBuilder, AlertProperties properties) {
        this.settings = properties.webhook();
        this.client = this.settings.isEnabled() ? build(restClientBuilder, this.settings) : null;
        if (this.client != null) {
            log.info("告警 Webhook 已启用：{}（格式 {}）", safeUrl(this.settings.url()), this.settings.format());
        }
    }

    private static RestClient build(RestClient.Builder builder, AlertProperties.Webhook settings) {
        RestClient.Builder configured = builder.clone()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults()
                                .withTimeouts(settings.connectTimeout(), settings.readTimeout())));
        settings.headers().forEach(configured::defaultHeader);
        return configured.build();
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public boolean isEnabled() {
        return this.client != null;
    }

    @Override
    public void send(AlertEvent event) {
        if (this.client == null) {
            return;
        }
        try {
            this.client.post()
                    .uri(this.settings.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload(event, this.settings.format()))
                    .exchange((request, response) -> inspect(event, response));
        }
        catch (Exception ex) {
            // 通道自己的故障不能影响巡检，也不该反过来产生新的告警——只记一条日志。
            log.warn("告警发送失败：{} -> {}（{}）", event.applicationId(), safeUrl(this.settings.url()),
                    ex.toString());
        }
    }

    /**
     * 检查应答。
     *
     * <p>不能只看 HTTP 状态码：三家机器人在报文被拒时（关键词不匹配、加签错误、机器人被停用）
     * 一律返回 {@code 200} 加一个非零的 {@code errcode}，只看状态码会把「发出去了但没送达」
     * 当成成功——而这正是告警最不能出现的失效方式。</p>
     */
    private Void inspect(AlertEvent event, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        String body = readBody(response);
        if (!status.is2xxSuccessful()) {
            log.warn("告警发送被拒：{} -> {} 返回 {}，响应体：{}", event.applicationId(),
                    safeUrl(this.settings.url()), status.value(), body);
            return null;
        }
        String failure = businessFailure(body, this.settings.format());
        if (failure != null) {
            log.warn("告警发送被拒：{} -> {} 返回 200，但报文里是 {}", event.applicationId(),
                    safeUrl(this.settings.url()), failure);
            return null;
        }
        log.debug("告警已发送：{} -> {}", event.applicationId(), safeUrl(this.settings.url()));
        return null;
    }

    /**
     * 报文里带了非零错误码时返回可读描述，正常时返回 {@code null}。
     *
     * <p>只对三家聊天机器人生效。「{@code errcode}/{@code code} 为 0 表示成功」是它们的约定，
     * 不是通用约定——{@code generic} 发给的是对方自建的网关，那边返回
     * {@code {"code":200}} 完全可能就是成功，套用这条规则只会造出假警报。</p>
     */
    static String businessFailure(String body, WebhookFormat format) {
        if (format == WebhookFormat.GENERIC || body == null || body.isBlank()) {
            return null;
        }
        Matcher matcher = ERROR_CODE.matcher(body);
        if (matcher.find() && !"0".equals(matcher.group(1))) {
            return body;
        }
        return null;
    }

    private static String readBody(ClientHttpResponse response) {
        try (InputStream in = response.getBody()) {
            byte[] bytes = in.readNBytes(MAX_ECHO_CHARS);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            return "";
        }
    }

    /** 按格式生成请求体。 */
    static Object payload(AlertEvent event, WebhookFormat format) {
        return switch (format) {
            case GENERIC -> generic(event);
            // 钉钉与企业微信的自定义机器人报文结构相同
            case WECOM, DINGTALK -> Map.of("msgtype", "text", "text", Map.of("content", event.text()));
            case FEISHU -> Map.of("msg_type", "text", "content", Map.of("text", event.text()));
        };
    }

    /**
     * 通用 JSON：字段最全，时间一律 ISO-8601。
     *
     * <p>显式拼一个 Map 而不是直接序列化 {@link AlertEvent}，是为了让报文结构成为一份
     * 明确的对外契约——记录上加个字段不会悄悄改变已经在跑的对接方看到的报文。</p>
     */
    private static Map<String, Object> generic(AlertEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", event.kind().getCode());
        body.put("applicationId", event.applicationId());
        body.put("applicationName", event.applicationName());
        body.put("group", event.group());
        body.put("type", event.type().name());
        body.put("url", event.url());
        body.put("critical", event.critical());
        body.put("state", event.state().name());
        body.put("previousState", (event.previousState() == null) ? null : event.previousState().name());
        body.put("consecutiveFailures", event.consecutiveFailures());
        body.put("message", event.message());
        body.put("httpStatus", event.httpStatus());
        body.put("latencyMs", event.latencyMs());
        body.put("since", iso(event.since()));
        body.put("occurredAt", iso(event.occurredAt()));
        body.put("title", event.title());
        body.put("text", event.text());
        return body;
    }

    private static String iso(Instant instant) {
        return (instant == null) ? null : instant.toString();
    }

    /**
     * 日志里出现的地址一律去掉查询串与用户信息。
     *
     * <p>钉钉与飞书的机器人地址把 {@code access_token} 放在查询串里，那串东西等同于一把
     * 「可以往这个群里发任意消息」的口令。它已经在配置文件里了，没必要再抄进日志——
     * 日志会被采集、会被转发，扩散面比配置文件大得多。</p>
     */
    static String safeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "(未配置)";
        }
        try {
            URI uri = new URI(raw);
            if (uri.getQuery() == null && uri.getUserInfo() == null) {
                return raw;
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
                    + ((uri.getQuery() == null) ? "" : "?***");
        }
        catch (URISyntaxException ex) {
            // 地址本身就不合法时，宁可只回显 scheme 也不要把可能含 token 的原文打出去
            int scheme = raw.indexOf("://");
            return (scheme > 0) ? raw.substring(0, scheme) + "://***" : "***";
        }
    }
}
