package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.ProbeDetail;
import com.bgssai.healthcheck.domain.ProbeResult;
import com.bgssai.healthcheck.domain.ProbeResult.ComponentStatus;
import com.bgssai.healthcheck.domain.TargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 探针：直接说 RESP 协议，发 PING 与 INFO。
 *
 * <p>为什么不引 Jedis / Lettuce：为了一次 PING 拉进一整套连接池与 Netty 不划算，而且客户端
 * 会把应答解析成对象，反而拿不到「原样的应答文本」——那正是排障时最想看的东西。RESP 是行协议，
 * 手写一个只读的小解析器不到一百行，还能把 INFO 的原文直接留进明细。</p>
 *
 * <p>为什么必须直连探：按 Standards §13.2，各产品后端的就绪探针只由 critical 组件
 * （db、mybatis）决定结论，Redis 属非 critical 依赖，既不参与判定也不在就绪端点被检查——
 * Redis 挂了应用照样报 UP。</p>
 */
@Component
public class RedisHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(RedisHealthProbe.class);

    /** INFO 的应答上限，正常输出大约 4 KB，给足余量即可，防止对端异常时读到停不下来。 */
    private static final int MAX_INFO_BYTES = 256 * 1024;

    private final HealthCheckProperties.Redis settings;

    /** 与 HttpHealthProbe 同款的全放行 TrustManager，rediss 且显式跳过校验时才会用到。 */
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

    public RedisHealthProbe(HealthCheckProperties properties) {
        this.settings = properties.redis();
    }

    @Override
    public Set<TargetType> supportedTypes() {
        return Set.of(TargetType.REDIS);
    }

    @Override
    public ProbeResult probe(MonitoredApplication app) {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        List<String> sent = new ArrayList<>();
        sent.add(app.uri().toString());

        try (Socket socket = openSocket(app)) {
            socket.setSoTimeout((int) Math.max(1L, app.readTimeout().toMillis()));
            OutputStream out = socket.getOutputStream();
            InputStream in = new java.io.BufferedInputStream(socket.getInputStream());

            if (app.password() != null && !app.password().isEmpty()) {
                sent.add((app.username() != null && !app.username().isBlank())
                        ? "AUTH " + app.username() + " " + ProbeSecrets.MASK
                        : "AUTH " + ProbeSecrets.MASK);
                Reply auth = command(out, in, authArgs(app));
                if (auth.error()) {
                    return failed(startedAt, startNanos, sent, "认证失败：" + scrub(app, auth.text()), null);
                }
            }

            Integer database = databaseIndex(app);
            if (database != null) {
                sent.add("SELECT " + database);
                Reply select = command(out, in, List.of("SELECT", String.valueOf(database)));
                if (select.error()) {
                    return failed(startedAt, startNanos, sent,
                            "SELECT " + database + " 被拒绝：" + scrub(app, select.text()), null);
                }
            }

            sent.add("PING");
            Reply ping = command(out, in, List.of("PING"));
            if (ping.error()) {
                return failed(startedAt, startNanos, sent, "PING 被拒绝：" + scrub(app, ping.text()), null);
            }

            sent.add("INFO");
            Reply info = command(out, in, List.of("INFO"));
            long latency = elapsedMs(startNanos);
            if (info.error()) {
                // PING 已经过了，说明实例是活的，只是 INFO 被禁用或受限——降级而不是判死
                return ProbeResult.of(HealthState.DEGRADED, null, latency, startedAt,
                        "PING 正常，但 INFO 被拒绝：" + scrub(app, info.text()),
                        List.of(ComponentStatus.of("server", HealthState.UP)),
                        ProbeDetail.text("RESP", String.join("\n", sent), "+PONG", info.text()));
            }

            Map<String, String> stats = parseInfo(info.text());
            Assessment assessment = assess(stats);
            return ProbeResult.of(assessment.state(), null, latency, startedAt, assessment.message(),
                    components(stats, assessment),
                    ProbeDetail.text("RESP", String.join("\n", sent), ping.text(), info.text()));
        }
        catch (Exception ex) {
            String reason = describeFailure(ex);
            log.debug("探测 Redis [{}] ({}) 失败：{}", app.id(), app.hostPort(), reason);
            return failed(startedAt, startNanos, sent, reason, null);
        }
    }

    private Socket openSocket(MonitoredApplication app) throws IOException {
        int connectTimeout = (int) Math.max(1L, app.connectTimeout().toMillis());
        if (!"rediss".equalsIgnoreCase(app.uri().getScheme())) {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(app.host(), app.port()), connectTimeout);
            return socket;
        }
        // TLS 目标：先按普通 socket 连上再包一层，这样 connect 超时才有效
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(app.host(), app.port()), connectTimeout);
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            if (app.skipTlsVerification()) {
                context.init(null, new TrustManager[] { TRUST_ALL }, null);
            }
            else {
                context.init(null, null, null);
            }
            SSLSocket ssl = (SSLSocket) context.getSocketFactory().createSocket(plain, app.host(), app.port(), true);
            if (app.skipTlsVerification()) {
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm(null);
                ssl.setSSLParameters(parameters);
            }
            return ssl;
        }
        catch (IOException | java.security.GeneralSecurityException ex) {
            plain.close();
            throw new IOException("TLS 握手失败：" + ex.getClass().getSimpleName(), ex);
        }
    }

    private static List<String> authArgs(MonitoredApplication app) {
        return (app.username() != null && !app.username().isBlank())
                ? List.of("AUTH", app.username(), app.password())
                : List.of("AUTH", app.password());
    }

    /** {@code redis://host:6379/2} 里的库序号；没写就返回 null。 */
    private static Integer databaseIndex(MonitoredApplication app) {
        String path = app.uri().getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return null;
        }
        try {
            return Integer.valueOf(path.substring(1).trim());
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    /* ---------- RESP 编解码 ---------- */

    /** 发一条命令并读回一个应答。只需要支持顶层的四种类型，不做嵌套数组。 */
    private static Reply command(OutputStream out, InputStream in, List<String> args) throws IOException {
        StringBuilder request = new StringBuilder("*").append(args.size()).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            request.append('$').append(bytes.length).append("\r\n").append(arg).append("\r\n");
        }
        out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        return readReply(in);
    }

    private static Reply readReply(InputStream in) throws IOException {
        String line = readLine(in);
        if (line.isEmpty()) {
            throw new IOException("对端提前关闭了连接");
        }
        char marker = line.charAt(0);
        String payload = line.substring(1);
        return switch (marker) {
            case '+', ':' -> new Reply(line, false);
            case '-' -> new Reply(line, true);
            case '$' -> {
                int length = Integer.parseInt(payload.trim());
                if (length < 0) {
                    yield new Reply("", false);
                }
                if (length > MAX_INFO_BYTES) {
                    throw new IOException("应答超过 " + MAX_INFO_BYTES + " 字节，已放弃读取");
                }
                byte[] body = in.readNBytes(length);
                in.readNBytes(2); // 丢掉结尾的 CRLF
                yield new Reply(new String(body, StandardCharsets.UTF_8), false);
            }
            default -> new Reply(line, false);
        };
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        int previous = -1;
        int current;
        while ((current = in.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = buffer.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.UTF_8);
            }
            buffer.write(current);
            previous = current;
            if (buffer.size() > MAX_INFO_BYTES) {
                throw new IOException("应答行过长，已放弃读取");
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /* ---------- INFO 解析与判定 ---------- */

    /** INFO 的格式是分节的 {@code key:value}，节名以 # 开头，这里只要扁平的键值。 */
    private static Map<String, String> parseInfo(String text) {
        Map<String, String> stats = new LinkedHashMap<>();
        if (text == null) {
            return stats;
        }
        for (String line : text.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf(':');
            if (separator > 0) {
                stats.put(trimmed.substring(0, separator), trimmed.substring(separator + 1));
            }
        }
        return stats;
    }

    private Assessment assess(Map<String, String> stats) {
        List<String> notes = new ArrayList<>();
        long used = readLong(stats, "used_memory");
        long max = readLong(stats, "maxmemory");
        double usedPercent = (max > 0L) ? used * 100.0d / max : -1.0d;
        boolean memoryTight = usedPercent >= this.settings.memoryWarnPercent();
        if (memoryTight) {
            notes.add("已用内存占 maxmemory 的 %.2f%%，超过阈值 %d%%".formatted(usedPercent,
                    this.settings.memoryWarnPercent()));
        }

        boolean replicationBroken = "slave".equalsIgnoreCase(stats.getOrDefault("role", ""))
                && !"up".equalsIgnoreCase(stats.getOrDefault("master_link_status", "up"));
        if (replicationBroken) {
            notes.add("从节点与主节点链路为 " + stats.get("master_link_status"));
        }

        boolean persistenceBroken = isFailed(stats.get("rdb_last_bgsave_status"))
                || isFailed(stats.get("aof_last_write_status"));
        if (persistenceBroken) {
            notes.add("持久化异常（rdb=%s，aof=%s）".formatted(stats.getOrDefault("rdb_last_bgsave_status", "-"),
                    stats.getOrDefault("aof_last_write_status", "-")));
        }

        HealthState state = notes.isEmpty() ? HealthState.UP : HealthState.DEGRADED;
        return new Assessment(state, notes.isEmpty() ? null : String.join("；", notes), usedPercent, memoryTight,
                replicationBroken, persistenceBroken);
    }

    private static boolean isFailed(String value) {
        return value != null && !value.isBlank() && !"ok".equalsIgnoreCase(value.trim());
    }

    private static List<ComponentStatus> components(Map<String, String> stats, Assessment assessment) {
        List<ComponentStatus> components = new ArrayList<>();
        components.add(new ComponentStatus("server", HealthState.UP,
                pick(stats, "redis_version", "redis_mode", "os", "uptime_in_days")));
        components.add(new ComponentStatus("clients", HealthState.UP,
                pick(stats, "connected_clients", "blocked_clients", "maxclients")));

        Map<String, String> memory = pick(stats, "used_memory_human", "maxmemory_human", "used_memory_peak_human",
                "mem_fragmentation_ratio");
        if (assessment.usedPercent() >= 0.0d) {
            memory.put("used_memory_percent", "%.2f%%".formatted(assessment.usedPercent()));
        }
        components.add(new ComponentStatus("memory",
                assessment.memoryTight() ? HealthState.DEGRADED : HealthState.UP, memory));

        components.add(new ComponentStatus("persistence",
                assessment.persistenceBroken() ? HealthState.DEGRADED : HealthState.UP,
                pick(stats, "rdb_last_bgsave_status", "aof_enabled", "aof_last_write_status",
                        "rdb_changes_since_last_save")));
        components.add(new ComponentStatus("replication",
                assessment.replicationBroken() ? HealthState.DEGRADED : HealthState.UP,
                pick(stats, "role", "connected_slaves", "master_link_status")));
        return HttpHealthProbe.sortBySeverity(components);
    }

    /** 缺字段就不写——写成 "null" 反而让人以为对端返回了这个值。 */
    private static Map<String, String> pick(Map<String, String> stats, String... keys) {
        Map<String, String> picked = new LinkedHashMap<>();
        for (String key : keys) {
            String value = stats.get(key);
            if (value != null && !value.isBlank()) {
                picked.put(key, value.trim());
            }
        }
        return picked;
    }

    private static long readLong(Map<String, String> stats, String key) {
        try {
            String value = stats.get(key);
            return (value == null) ? 0L : Long.parseLong(value.trim());
        }
        catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /* ---------- 失败路径 ---------- */

    private static ProbeResult failed(Instant startedAt, long startNanos, List<String> sent, String reason,
            String partialBody) {
        return ProbeResult.failure(elapsedMs(startNanos), startedAt, reason,
                ProbeDetail.failed("RESP", String.join("\n", sent), partialBody, reason));
    }

    private static String scrub(MonitoredApplication app, String text) {
        return ProbeSecrets.scrub(text, app.password());
    }

    private static String describeFailure(Throwable ex) {
        String detail = (ex.getMessage() != null && !ex.getMessage().isBlank())
                ? ex.getMessage()
                : ex.getClass().getSimpleName();
        if (ex instanceof java.net.SocketTimeoutException) {
            return "请求超时：" + detail;
        }
        if (ex instanceof java.net.ConnectException) {
            return "无法建立连接：" + detail;
        }
        if (ex instanceof java.net.UnknownHostException) {
            return "域名解析失败：" + detail;
        }
        return "请求失败：" + detail;
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** 一条 RESP 应答的原文，以及它是不是错误应答。 */
    private record Reply(String text, boolean error) {
    }

    /** INFO 判定的结论与依据。 */
    private record Assessment(HealthState state, String message, double usedPercent, boolean memoryTight,
            boolean replicationBroken, boolean persistenceBroken) {
    }
}
