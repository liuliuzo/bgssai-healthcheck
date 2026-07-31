package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.ProbeResult;
import com.bgssai.healthcheck.domain.ProbeResult.ComponentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 通过 HTTP 调用被监控应用的健康检查接口，并把响应归一化为 {@link ProbeResult}。
 *
 * <p>解析优先级：响应体里的状态字段 &gt; HTTP 状态码。这样才能正确处理 Actuator
 * 用 503 + {@code {"status":"DOWN"}} 表达异常、用 200 + {@code {"status":"OUT_OF_SERVICE"}}
 * 表达降级的两种约定。</p>
 *
 * <p>本类不抛异常：任何失败都会转换成一个 DOWN 的 {@link ProbeResult}。</p>
 */
@Component
public class HttpHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(HttpHealthProbe.class);

    /** 依次尝试这些字段来获取对端自报的状态。 */
    private static final List<String> STATUS_KEYS = List.of("status", "state", "health");

    /** 响应体片段在错误信息里的最大长度。 */
    private static final int MESSAGE_SNIPPET_LIMIT = 200;

    private final RestClient.Builder restClientBuilder;

    private final JsonMapper jsonMapper;

    private final HealthCheckProperties.Probe settings;

    private final ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder = ClientHttpRequestFactoryBuilder.detect();

    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public HttpHealthProbe(RestClient.Builder restClientBuilder, JsonMapper jsonMapper,
            HealthCheckProperties properties) {
        this.restClientBuilder = restClientBuilder;
        this.jsonMapper = jsonMapper;
        this.settings = properties.probe();
    }

    /**
     * 探测一个应用，无论成功失败都会返回结果。
     */
    public ProbeResult probe(MonitoredApplication app) {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        try {
            return clientFor(app).method(app.method())
                    .uri(app.uri())
                    .accept(MediaType.APPLICATION_JSON, MediaType.ALL)
                    .exchange((request, response) -> evaluate(app, response, startedAt, startNanos));
        }
        catch (Exception ex) {
            long latency = elapsedMs(startNanos);
            String reason = describeFailure(ex);
            log.debug("探测应用 [{}] ({}) 失败：{}", app.id(), app.uri(), reason);
            return ProbeResult.failure(latency, startedAt, reason);
        }
    }

    private ProbeResult evaluate(MonitoredApplication app, ClientHttpResponse response, Instant startedAt,
            long startNanos) throws IOException {
        HttpStatusCode statusCode = response.getStatusCode();
        String body = readBody(response);
        long latency = elapsedMs(startNanos);

        boolean httpAccepted = app.expectedStatuses().isEmpty()
                ? statusCode.is2xxSuccessful()
                : app.expectedStatuses().contains(statusCode.value());

        ParsedBody parsed = parseBody(body);
        HealthState state;
        String message = null;
        if (parsed.state() == null) {
            // 响应体不是可识别的健康检查报文，只能依据 HTTP 状态码判断
            state = httpAccepted ? HealthState.UP : HealthState.DOWN;
            if (!httpAccepted) {
                message = "接口返回 " + describeStatus(statusCode);
                if (!body.isBlank()) {
                    message += "；响应体：" + snippet(body);
                }
            }
        }
        else if (parsed.state() == HealthState.UP && !httpAccepted) {
            // 自报正常但状态码不在预期内，降级处理而不是直接判死
            state = HealthState.DEGRADED;
            message = "响应体自报 UP，但接口返回 " + describeStatus(statusCode);
        }
        else {
            state = parsed.state();
            if (state != HealthState.UP) {
                message = "自报状态 " + parsed.rawStatus() + "（" + describeStatus(statusCode) + "）";
            }
        }
        return ProbeResult.of(state, statusCode.value(), latency, startedAt, message, parsed.components());
    }

    private RestClient clientFor(MonitoredApplication app) {
        return this.clients.computeIfAbsent(app.id(), key -> buildClient(app));
    }

    private RestClient buildClient(MonitoredApplication app) {
        HttpClientSettings clientSettings = HttpClientSettings.defaults()
                .withTimeouts(app.connectTimeout(), app.readTimeout())
                .withRedirects(this.settings.followRedirects() ? HttpRedirects.FOLLOW : HttpRedirects.DONT_FOLLOW);

        RestClient.Builder builder = this.restClientBuilder.clone()
                .requestFactory(this.requestFactoryBuilder.build(clientSettings));

        app.headers().forEach(builder::defaultHeader);
        if (app.authorization() != null) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, app.authorization());
        }
        return builder.build();
    }

    private String readBody(ClientHttpResponse response) {
        int limit = this.settings.maxBodyBytes();
        if (limit <= 0) {
            return "";
        }
        try (InputStream in = response.getBody()) {
            byte[] bytes = in.readNBytes(limit);
            return new String(bytes, charsetOf(response.getHeaders().getContentType()));
        }
        catch (IOException | RuntimeException ex) {
            log.debug("读取响应体失败：{}", ex.toString());
            return "";
        }
    }

    private static Charset charsetOf(MediaType contentType) {
        Charset charset = (contentType != null) ? contentType.getCharset() : null;
        return (charset != null) ? charset : StandardCharsets.UTF_8;
    }

    /**
     * 尝试把响应体当作 JSON 解析出状态与子组件；不是 JSON 时返回空结果，由调用方回退到 HTTP 状态码。
     */
    private ParsedBody parseBody(String body) {
        if (body == null || body.isBlank()) {
            return ParsedBody.empty();
        }
        Map<?, ?> root;
        try {
            root = this.jsonMapper.readValue(body, Map.class);
        }
        catch (Exception ex) {
            return ParsedBody.empty();
        }
        String rawStatus = readStatus(root);
        HealthState state = (rawStatus != null) ? HealthState.fromActuator(rawStatus) : null;
        return new ParsedBody(state, rawStatus, readComponents(root));
    }

    private static String readStatus(Map<?, ?> node) {
        for (String key : STATUS_KEYS) {
            Object value = node.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
            // {"status": {"code": "UP"}} 这类嵌套写法
            if (value instanceof Map<?, ?> nested) {
                Object code = nested.get("code");
                if (code instanceof String text && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static List<ComponentStatus> readComponents(Map<?, ?> root) {
        Object raw = (root.get("components") != null) ? root.get("components") : root.get("details");
        if (!(raw instanceof Map<?, ?> components) || components.isEmpty()) {
            return List.of();
        }
        List<ComponentStatus> result = new ArrayList<>(components.size());
        components.forEach((name, value) -> {
            if (!(value instanceof Map<?, ?> component)) {
                return;
            }
            String status = readStatus(component);
            if (status == null) {
                return;
            }
            result.add(new ComponentStatus(String.valueOf(name), HealthState.fromActuator(status),
                    readComponentDetails(component)));
        });
        result.sort((left, right) -> {
            int bySeverity = Integer.compare(right.state().getSeverity(), left.state().getSeverity());
            return (bySeverity != 0) ? bySeverity : left.name().compareToIgnoreCase(right.name());
        });
        return result;
    }

    private static Map<String, String> readComponentDetails(Map<?, ?> component) {
        Object raw = component.get("details");
        if (!(raw instanceof Map<?, ?> details) || details.isEmpty()) {
            return Map.of();
        }
        Map<String, String> flattened = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            if (value != null && !(value instanceof Map) && !(value instanceof List)) {
                flattened.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return flattened;
    }

    private static String describeStatus(HttpStatusCode statusCode) {
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        return (resolved != null) ? "HTTP %d %s".formatted(resolved.value(), resolved.getReasonPhrase())
                : "HTTP " + statusCode.value();
    }

    private static String describeFailure(Throwable ex) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        String detail = (cause.getMessage() != null && !cause.getMessage().isBlank())
                ? cause.getMessage()
                : cause.getClass().getSimpleName();
        if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
            return "请求超时：" + detail;
        }
        // 目标端口无人监听时，不同的 HTTP 客户端实现分别抛 ConnectException 或 ClosedChannelException
        if (cause instanceof java.net.ConnectException || cause instanceof java.nio.channels.ClosedChannelException) {
            return "无法建立连接：" + (detail.isBlank() ? cause.getClass().getSimpleName() : detail);
        }
        if (cause instanceof java.net.UnknownHostException) {
            return "域名解析失败：" + detail;
        }
        return "请求失败：" + snippet(detail);
    }

    private static String snippet(String text) {
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return (cleaned.length() <= MESSAGE_SNIPPET_LIMIT) ? cleaned
                : cleaned.substring(0, MESSAGE_SNIPPET_LIMIT) + "…";
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** 已解析的响应体内容。{@code state} 为 {@code null} 表示响应体里没有可识别的状态。 */
    private record ParsedBody(HealthState state, String rawStatus, List<ComponentStatus> components) {

        static ParsedBody empty() {
            return new ParsedBody(null, null, List.of());
        }
    }
}
