package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.ProbeDetail;
import com.bgssai.healthcheck.domain.ProbeResult;
import com.bgssai.healthcheck.domain.ProbeResult.ComponentStatus;
import com.bgssai.healthcheck.domain.TargetType;
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
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 通过 HTTP 调用被监控目标的健康检查接口，并把响应归一化为 {@link ProbeResult}。
 *
 * <p>解析优先级：响应体里的状态字段 &gt; HTTP 状态码。这样才能正确处理 Actuator
 * 用 503 + {@code {"status":"DOWN"}} 表达异常、用 200 + {@code {"status":"OUT_OF_SERVICE"}}
 * 表达降级的两种约定。</p>
 *
 * <p>同时负责 {@link TargetType#ELASTICSEARCH}：它的集群健康接口就是一个普通的
 * HTTPS GET，请求、TLS、超时、明细捕获全都一样，差别只在于响应体里 {@code status}
 * 是集群颜色、以及能多解析出几个分片指标——为它另开一个探针只会把这些逻辑复制一遍。</p>
 *
 * <p>本类不抛异常：任何失败都会转换成一个 DOWN 的 {@link ProbeResult}。</p>
 */
@Component
public class HttpHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(HttpHealthProbe.class);

    /** 依次尝试这些字段来获取对端自报的状态。 */
    private static final List<String> STATUS_KEYS = List.of("status", "state", "health");

    /** 统一响应封装里承载健康负载的字段名，见 {@link #unwrapEnvelope(Map)}。 */
    private static final List<String> ENVELOPE_PAYLOAD_KEYS = List.of("result", "data");

    /** 响应体片段在错误信息里的最大长度。 */
    private static final int MESSAGE_SNIPPET_LIMIT = 200;

    private final RestClient.Builder restClientBuilder;

    private final JsonMapper jsonMapper;

    private final HealthCheckProperties.Probe settings;

    private final ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder = ClientHttpRequestFactoryBuilder.detect();

    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    /**
     * 放开证书链校验的 TrustManager，仅供显式打开 {@code skip-tls-verification} 的目标使用。
     *
     * <p>用 {@link X509ExtendedTrustManager} 而不是 {@code X509TrustManager}：只有前者的
     * 两个带 {@link Socket} / {@link SSLEngine} 的重载会被 JDK 在启用端点识别时调用，
     * 实现基类版本才能确保主机名校验一并被放开。</p>
     */
    private static final X509ExtendedTrustManager TRUST_ALL = new X509ExtendedTrustManager() {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    public HttpHealthProbe(RestClient.Builder restClientBuilder, JsonMapper jsonMapper,
            HealthCheckProperties properties) {
        this.restClientBuilder = restClientBuilder;
        this.jsonMapper = jsonMapper;
        this.settings = properties.probe();
    }

    @Override
    public Set<TargetType> supportedTypes() {
        return Set.of(TargetType.HTTP, TargetType.ELASTICSEARCH, TargetType.BOXPOOL);
    }

    /**
     * 探测一个目标，无论成功失败都会返回结果。
     */
    @Override
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
            log.debug("探测目标 [{}] ({}) 失败：{}", app.id(), app.uri(), reason);
            return ProbeResult.failure(latency, startedAt, reason,
                    ProbeDetail.failed("HTTP", describeRequest(app), null, reason));
        }
    }

    private ProbeResult evaluate(MonitoredApplication app, ClientHttpResponse response, Instant startedAt,
            long startNanos) throws IOException {
        HttpStatusCode statusCode = response.getStatusCode();
        Body read = readBody(response);
        String body = read.text();
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

        List<ComponentStatus> components = parsed.components();
        if (app.type() == TargetType.ELASTICSEARCH) {
            components = elasticsearchComponents(parsed, components);
            String clusterNote = describeCluster(parsed);
            if (clusterNote != null) {
                message = (message == null) ? clusterNote : message + "；" + clusterNote;
            }
        }
        else if (app.type() == TargetType.BOXPOOL) {
            components = boxPoolComponents(parsed, components);
            String capacityNote = describeCapacity(parsed);
            if (capacityNote != null) {
                message = (message == null) ? capacityNote : message + "；" + capacityNote;
            }
            // 宿主活着但一个人都接不下，等同于对用户不可用——不能报绿。
            if (state == HealthState.UP && readLong(parsed.payload(), "headroom") <= 0L) {
                state = HealthState.DEGRADED;
            }
        }

        ProbeDetail detail = new ProbeDetail("HTTP", describeRequest(app), statusLine(statusCode),
                responseHeaders(response), body, read.bytes(), false, read.error());
        return ProbeResult.of(state, statusCode.value(), latency, startedAt, message, components, detail);
    }

    /**
     * 请求摘要：第一行是方法与地址，随后逐行列出实际发出的请求头。
     *
     * <p>抽成方法是因为成功与失败两条路径都要用它——失败时恰恰最需要知道「我们到底发了什么」。
     * 认证类请求头的值一律换成占位符，明细会原样出现在看板与报告里。</p>
     */
    private static String describeRequest(MonitoredApplication app) {
        StringBuilder sb = new StringBuilder(app.method().name()).append(' ').append(app.uri());
        app.headers().forEach((name, value) -> sb.append('\n')
                .append(name)
                .append(": ")
                .append(ProbeSecrets.maskHeader(name, value)));
        if (app.authorization() != null) {
            sb.append('\n').append(HttpHeaders.AUTHORIZATION).append(": ").append(ProbeSecrets.MASK);
        }
        return sb.toString();
    }

    private static List<ProbeDetail.Header> responseHeaders(ClientHttpResponse response) {
        List<ProbeDetail.Header> headers = new ArrayList<>();
        response.getHeaders().forEach((name, values) -> headers.add(
                new ProbeDetail.Header(name, ProbeSecrets.maskHeader(name, String.join(", ", values)))));
        return headers;
    }

    private static String statusLine(HttpStatusCode statusCode) {
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        return (resolved != null) ? resolved.value() + " " + resolved.getReasonPhrase()
                : String.valueOf(statusCode.value());
    }

    private RestClient clientFor(MonitoredApplication app) {
        return this.clients.computeIfAbsent(app.id(), key -> buildClient(app));
    }

    private RestClient buildClient(MonitoredApplication app) {
        RestClient.Builder builder = this.restClientBuilder.clone()
                .requestFactory(app.skipTlsVerification()
                        ? insecureRequestFactory(app)
                        : this.requestFactoryBuilder.build(standardSettings(app)));

        app.headers().forEach(builder::defaultHeader);
        if (app.authorization() != null) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, app.authorization());
        }
        return builder.build();
    }

    private HttpClientSettings standardSettings(MonitoredApplication app) {
        return HttpClientSettings.defaults()
                .withTimeouts(app.connectTimeout(), app.readTimeout())
                .withRedirects(this.settings.followRedirects() ? HttpRedirects.FOLLOW : HttpRedirects.DONT_FOLLOW);
    }

    /**
     * 为「跳过证书校验」的目标单独装配请求工厂。
     *
     * <p>只在该目标显式打开 {@code skip-tls-verification} 时使用，且仅作用于这一个
     * {@link RestClient} 实例——不碰 JVM 全局的 {@code SSLContext.setDefault}，因此不会影响本平台
     * 的其它出站请求，更不会影响任何被监控应用。</p>
     *
     * <p>两处都要放开才有效：自定义 {@link X509ExtendedTrustManager} 放开证书链校验，
     * 清空 {@code endpointIdentificationAlgorithm} 放开主机名校验。只做前者时，JDK
     * {@link HttpClient} 仍会因 SNI 主机名与证书 CN/SAN 不符而握手失败。</p>
     */
    private ClientHttpRequestFactory insecureRequestFactory(MonitoredApplication app) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { TRUST_ALL }, null);

            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm(null);

            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(app.connectTimeout())
                    .followRedirects(this.settings.followRedirects()
                            ? HttpClient.Redirect.NORMAL
                            : HttpClient.Redirect.NEVER)
                    .sslContext(sslContext)
                    .sslParameters(sslParameters)
                    .build();

            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(app.readTimeout());
            return factory;
        }
        catch (GeneralSecurityException ex) {
            // 装配失败时退回标准校验：宁可这条目标报证书错误，也不要让整个平台起不来。
            log.warn("目标 [{}] 配置了跳过证书校验，但 SSLContext 装配失败（{}），本条改用标准校验",
                    app.id(), ex.getClass().getSimpleName());
            return this.requestFactoryBuilder.build(standardSettings(app));
        }
    }

    /**
     * 读响应体。
     *
     * <p>返回值区分「对端就是没给正文」与「我们没读成功」：前者是正常情况（HEAD、204），
     * 后者要写进明细的 error，否则看板上会出现一个空白的原始应答，让人误以为对端返回了空。</p>
     */
    private Body readBody(ClientHttpResponse response) {
        int limit = this.settings.maxBodyBytes();
        if (limit <= 0) {
            return new Body("", 0, null);
        }
        try (InputStream in = response.getBody()) {
            byte[] bytes = in.readNBytes(limit);
            return new Body(new String(bytes, charsetOf(response.getHeaders().getContentType())), bytes.length, null);
        }
        catch (IOException | RuntimeException ex) {
            log.debug("读取响应体失败：{}", ex.toString());
            return new Body("", 0, "读取响应体失败：" + ex.getClass().getSimpleName());
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
        Map<?, ?> payload = unwrapEnvelope(root);
        String rawStatus = readStatus(payload);
        HealthState state = (rawStatus != null) ? HealthState.fromActuator(rawStatus) : null;
        return new ParsedBody(state, rawStatus, readComponents(payload), payload);
    }

    /**
     * 剥掉统一响应封装，拿到真正的健康负载。
     *
     * <p>BGSSAI 产品线的应用按 Standards §13 把健康负载放在各仓既有的统一响应封装里，而各仓封装
     * 字段名并不相同：{@code {code, message, success, result}}、{@code {code, message, data}}、
     * {@code {success, code, message, data}} 都在用。封装的 {@code code} / {@code success} 表达的是
     * 「接口调用成功」，与健康无关，因此这里只认负载里的 {@code status}。</p>
     *
     * <p>规则：顶层自己就有可识别状态时（Actuator 那种裸响应）原样返回；否则若 {@code result} 或
     * {@code data} 是一个带状态的对象，就下沉一层。两者都不成立时返回顶层，由调用方回退到 HTTP 状态码。</p>
     */
    private static Map<?, ?> unwrapEnvelope(Map<?, ?> root) {
        if (readStatus(root) != null) {
            return root;
        }
        for (String key : ENVELOPE_PAYLOAD_KEYS) {
            if (root.get(key) instanceof Map<?, ?> payload && readStatus(payload) != null) {
                return payload;
            }
        }
        return root;
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
        List<ComponentStatus> result = new ArrayList<>();
        // Actuator 形态：{"components": {"db": {"status": "UP"}}} —— 键即组件名。
        if (raw instanceof Map<?, ?> components) {
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
        }
        // BGSSAI 形态（Standards §13.3）：components 是数组，组件名在元素的 name 字段里。
        else if (raw instanceof List<?> components) {
            for (Object value : components) {
                if (!(value instanceof Map<?, ?> component)) {
                    continue;
                }
                Object name = component.get("name");
                String status = readStatus(component);
                if (!(name instanceof String text) || text.isBlank() || status == null) {
                    continue;
                }
                result.add(new ComponentStatus(text, HealthState.fromActuator(status),
                        readComponentDetails(component)));
            }
        }
        if (result.isEmpty()) {
            return List.of();
        }
        return sortBySeverity(result);
    }

    static List<ComponentStatus> sortBySeverity(List<ComponentStatus> components) {
        List<ComponentStatus> sorted = new ArrayList<>(components);
        sorted.sort((left, right) -> {
            int bySeverity = Integer.compare(right.state().getSeverity(), left.state().getSeverity());
            return (bySeverity != 0) ? bySeverity : left.name().compareToIgnoreCase(right.name());
        });
        return List.copyOf(sorted);
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

    /**
     * 把 {@code _cluster/health} 的扁平数字字段拆成三个子组件。
     *
     * <p>Elasticsearch 不返回 {@code components}，所有指标都平铺在顶层，直接展示等于让人在
     * 一行 JSON 里数字段。拆成 cluster / shards / tasks 之后，卡片展开就能看出「黄是因为
     * 有几个分片没分配」还是「节点少了一台」。</p>
     */
    private static List<ComponentStatus> elasticsearchComponents(ParsedBody parsed, List<ComponentStatus> fallback) {
        Map<?, ?> payload = parsed.payload();
        if (payload == null || payload.isEmpty()) {
            return fallback;
        }
        List<ComponentStatus> components = new ArrayList<>();
        HealthState clusterState = (parsed.state() != null) ? parsed.state() : HealthState.UNKNOWN;
        components.add(new ComponentStatus("cluster", clusterState,
                pick(payload, "cluster_name", "number_of_nodes", "number_of_data_nodes", "timed_out")));

        long unassigned = readLong(payload, "unassigned_shards");
        components.add(new ComponentStatus("shards", (unassigned > 0L) ? HealthState.DEGRADED : HealthState.UP,
                pick(payload, "active_shards", "active_primary_shards", "relocating_shards", "initializing_shards",
                        "unassigned_shards", "delayed_unassigned_shards", "active_shards_percent_as_number")));

        // 待处理任务瞬时不为 0 是正常的，只报数不参与判定
        components.add(new ComponentStatus("tasks", HealthState.UP,
                pick(payload, "number_of_pending_tasks", "number_of_in_flight_fetch",
                        "task_max_waiting_in_queue_millis")));
        return sortBySeverity(components);
    }

    /**
     * 把云电脑宿主的水位拆成两个子组件：还能接几个人、这台机器上存了多少人的电脑。
     *
     * <p>分开是因为两者的含义完全不同，混在一起看会得出错误结论：{@code headroom} 受
     * <strong>内存</strong>约束，是「现在还能让几个人同时上来」；{@code boxesTotal} 受
     * <strong>磁盘</strong>约束，是「这台机器上一共有多少人的电脑」——box 休眠时不占内存，
     * 只占一百多兆磁盘，所以后者通常比前者大一个数量级。</p>
     */
    private static List<ComponentStatus> boxPoolComponents(ParsedBody parsed, List<ComponentStatus> fallback) {
        Map<?, ?> payload = parsed.payload();
        if (payload == null || payload.isEmpty()) {
            return fallback;
        }
        List<ComponentStatus> components = new ArrayList<>();

        long headroom = readLong(payload, "headroom");
        components.add(new ComponentStatus("在线容量", (headroom > 0L) ? HealthState.UP : HealthState.DEGRADED,
                pick(payload, "headroom", "online", "maxOnline")));

        // 休眠的 box 只占磁盘，不参与在线容量的判定，所以恒 UP，只报数。
        components.add(new ComponentStatus("已开电脑", HealthState.UP,
                pick(payload, "boxesTotal", "freeMemMb", "boxMemMb")));
        return sortBySeverity(components);
    }

    /** 看板上那一行摘要，直接说人话：还能接几个人。 */
    private static String describeCapacity(ParsedBody parsed) {
        Map<?, ?> payload = parsed.payload();
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        long headroom = readLong(payload, "headroom");
        long online = readLong(payload, "online");
        long max = readLong(payload, "maxOnline");
        if (headroom <= 0L) {
            return "已满：在线 " + online + "/" + max + "，接不下新用户了";
        }
        return "还能接 " + headroom + " 人同时用（在线 " + online + "/" + max + "）";
    }

    private static String describeCluster(ParsedBody parsed) {
        if (parsed.rawStatus() == null || parsed.state() == HealthState.UP) {
            return null;
        }
        long unassigned = readLong(parsed.payload(), "unassigned_shards");
        String note = "集群颜色 " + parsed.rawStatus();
        return (unassigned > 0L) ? note + "，未分配分片 " + unassigned + " 个" : note;
    }

    /** 只挑存在的键，缺字段就不写——写成 "null" 反而让人以为对端返回了这个值。 */
    private static Map<String, String> pick(Map<?, ?> payload, String... keys) {
        Map<String, String> picked = new LinkedHashMap<>();
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null && !(value instanceof Map) && !(value instanceof List)) {
                picked.put(key, String.valueOf(value));
            }
        }
        return picked;
    }

    private static long readLong(Map<?, ?> payload, String key) {
        if (payload == null) {
            return 0L;
        }
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            }
            catch (NumberFormatException ex) {
                return 0L;
            }
        }
        return 0L;
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

    /** 读到的响应体正文、字节数，以及读取失败时的原因。 */
    private record Body(String text, int bytes, String error) {
    }

    /**
     * 已解析的响应体内容。{@code state} 为 {@code null} 表示响应体里没有可识别的状态；
     * {@code payload} 是剥掉封装后的那一层，Elasticsearch 的指标要从它上面取。
     */
    private record ParsedBody(HealthState state, String rawStatus, List<ComponentStatus> components,
            Map<?, ?> payload) {

        static ParsedBody empty() {
            return new ParsedBody(null, null, List.of(), Map.of());
        }
    }
}
