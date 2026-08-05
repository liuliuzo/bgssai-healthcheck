package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.ProbeDetail;
import com.bgssai.healthcheck.domain.ProbeResult;
import com.bgssai.healthcheck.domain.ProbeResult.ComponentStatus;
import com.bgssai.healthcheck.domain.TargetType;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MySQL 探针：建一条连接、跑几条只读语句，用真正的查询证明库是活的。
 *
 * <p>为什么不满足于 TCP 端口通：端口开着但实例只读、连接数打满、或者库被
 * {@code bgssai-database} 的 clean 流水线 DROP 掉，这些都不会让 3306 关掉，
 * 却都会让应用在下一次写入时炸掉。所以这里跑的是 {@code SELECT 1} 加几条状态查询，
 * 并把配置里期望存在的库与 {@code information_schema} 对一遍。</p>
 *
 * <p>为什么要专用线程池：调用线程是虚拟线程，而 JDK 21 上 Connector/J 内部大量
 * {@code synchronized} 会把载体线程钉住（thread pinning）。一台库不可达时，
 * 连接会一直阻塞到超时，被钉住的载体线程期间无法运行其它虚拟线程——同一轮里
 * 其它目标的探测会被这台库连累。把 JDBC 调用挪到少量平台线程上，并在外层加一道
 * 硬截止时间，探测慢的代价就只由这一条目标承担。</p>
 */
@Component
public class MysqlHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(MysqlHealthProbe.class);

    /** 只列本产品线的库，避免把整台实例上无关的库也拉进报告。 */
    private static final String SCHEMA_QUERY = "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA "
            + "WHERE SCHEMA_NAME LIKE 'bgssai%' ORDER BY SCHEMA_NAME";

    private final HealthCheckProperties.Mysql settings;

    private final ThreadPoolExecutor executor;

    public MysqlHealthProbe(HealthCheckProperties properties) {
        this.settings = properties.mysql();
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "healthcheck-jdbc-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // 池子刻意开得很小：库目标只有两三个，真正需要的是「不要钉住虚拟线程的载体」，
        // 不是并发能力。队列用 SynchronousQueue 保证任务要么立刻有线程接、要么由调用方自己跑。
        this.executor = new ThreadPoolExecutor(0, 4, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Override
    public Set<TargetType> supportedTypes() {
        return Set.of(TargetType.MYSQL);
    }

    @Override
    public ProbeResult probe(MonitoredApplication app) {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        String jdbcUrl = jdbcUrl(app);
        String request = ProbeSecrets.scrub(jdbcUrl, app.password()) + "\nuser: "
                + ((app.username() == null) ? "-" : app.username()) + "\npassword: "
                + ((app.password() == null || app.password().isEmpty()) ? "-" : ProbeSecrets.MASK);

        Future<Outcome> future = this.executor.submit(() -> query(app, jdbcUrl));
        Outcome outcome;
        try {
            long budgetMs = app.connectTimeout().toMillis() + app.readTimeout().toMillis() + 1000L;
            outcome = future.get(budgetMs, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException ex) {
            future.cancel(true);
            return ProbeResult.failure(elapsedMs(startNanos), startedAt, "探测超时：JDBC 调用未在预算内返回",
                    ProbeDetail.failed("MySQL", request, null, "探测超时"));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ProbeResult.failure(elapsedMs(startNanos), startedAt, "探测被中断",
                    ProbeDetail.failed("MySQL", request, null, "探测被中断"));
        }
        catch (Exception ex) {
            String reason = describe(ex, app);
            return ProbeResult.failure(elapsedMs(startNanos), startedAt, "请求失败：" + reason,
                    ProbeDetail.failed("MySQL", request, null, reason));
        }

        long latency = elapsedMs(startNanos);
        String transcript = String.join("\n", outcome.transcript());
        if (!outcome.connected()) {
            log.debug("探测 MySQL [{}] ({}) 失败：{}", app.id(), app.hostPort(), outcome.failure());
            return ProbeResult.failure(latency, startedAt, outcome.failure(),
                    ProbeDetail.failed("MySQL", request, transcript, outcome.failure()));
        }
        return ProbeResult.of(outcome.state(), null, latency, startedAt, outcome.message(), outcome.components(),
                ProbeDetail.text("MySQL", request, "连接成功 " + outcome.version(), transcript));
    }

    /**
     * 真正跑 JDBC 的那一段，运行在专用平台线程上。
     *
     * <p>只有第一步 {@code SELECT 1} 决定死活：后面几条查询失败通常是权限不足
     * （监控账号不一定能读 global status），把这种情况判成 DOWN 等于用巡检账号的权限
     * 去误报一台健康的库。</p>
     */
    private Outcome query(MonitoredApplication app, String jdbcUrl) {
        List<String> transcript = new ArrayList<>();
        List<ComponentStatus> components = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        long connectStart = System.nanoTime();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, app.username(), app.password())) {
            long connectMs = elapsedMs(connectStart);

            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(this.settings.queryTimeoutSeconds());
                transcript.add("-- SELECT 1");
                try (ResultSet rs = statement.executeQuery("SELECT 1")) {
                    transcript.add(rs.next() ? String.valueOf(rs.getInt(1)) : "(空结果)");
                }
            }

            Map<String, String> connectionDetails = new LinkedHashMap<>();
            connectionDetails.put("jdbc_url", ProbeSecrets.scrub(jdbcUrl, app.password()));
            if (app.username() != null) {
                connectionDetails.put("user", app.username());
            }
            connectionDetails.put("connect_ms", String.valueOf(connectMs));
            components.add(new ComponentStatus("connection", HealthState.UP, connectionDetails));

            Map<String, String> serverDetails = new LinkedHashMap<>();
            String version = "";
            long maxConnections = 0L;
            boolean readOnly = false;
            transcript.add("-- SELECT VERSION(), @@read_only, @@max_connections");
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(this.settings.queryTimeoutSeconds());
                try (ResultSet rs = statement.executeQuery("SELECT VERSION(), @@read_only, @@max_connections")) {
                    if (rs.next()) {
                        version = String.valueOf(rs.getString(1));
                        readOnly = rs.getInt(2) == 1;
                        maxConnections = rs.getLong(3);
                        transcript.add(version + " | " + (readOnly ? 1 : 0) + " | " + maxConnections);
                        serverDetails.put("version", version);
                        serverDetails.put("read_only", readOnly ? "1" : "0");
                        serverDetails.put("max_connections", String.valueOf(maxConnections));
                    }
                }
            }
            catch (SQLException ex) {
                transcript.add("-- 查询失败：" + ex.getClass().getSimpleName());
                serverDetails.put("server_query", "查询失败：" + ex.getClass().getSimpleName());
            }

            long threadsConnected = -1L;
            transcript.add("-- SHOW GLOBAL STATUS LIKE 'Threads_connected'");
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(this.settings.queryTimeoutSeconds());
                try (ResultSet rs = statement.executeQuery("SHOW GLOBAL STATUS LIKE 'Threads_connected'")) {
                    if (rs.next()) {
                        threadsConnected = parseLong(rs.getString(2));
                        transcript.add(rs.getString(1) + " | " + rs.getString(2));
                        serverDetails.put("threads_connected", String.valueOf(threadsConnected));
                    }
                }
            }
            catch (SQLException ex) {
                transcript.add("-- 查询失败：" + ex.getClass().getSimpleName());
                serverDetails.put("status_query", "查询失败：" + ex.getClass().getSimpleName());
            }

            boolean connectionsTight = false;
            if (threadsConnected >= 0L && maxConnections > 0L) {
                double usedPercent = threadsConnected * 100.0d / maxConnections;
                serverDetails.put("connection_used_percent", "%.2f%%".formatted(usedPercent));
                connectionsTight = usedPercent >= this.settings.connectionWarnPercent();
                if (connectionsTight) {
                    notes.add("连接数占用 %.2f%%，超过阈值 %d%%".formatted(usedPercent,
                            this.settings.connectionWarnPercent()));
                }
            }
            if (readOnly) {
                notes.add("实例处于只读状态（@@read_only=1）");
            }
            components.add(new ComponentStatus("server",
                    (connectionsTight || readOnly) ? HealthState.DEGRADED : HealthState.UP, serverDetails));

            Map<String, String> schemaDetails = new LinkedHashMap<>();
            HealthState schemaState = HealthState.UP;
            Set<String> found = new LinkedHashSet<>();
            transcript.add("-- information_schema.SCHEMATA LIKE 'bgssai%'");
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(this.settings.queryTimeoutSeconds());
                try (ResultSet rs = statement.executeQuery(SCHEMA_QUERY)) {
                    while (rs.next()) {
                        found.add(rs.getString(1));
                    }
                }
                transcript.add(found.isEmpty() ? "(没有 bgssai 开头的库)" : String.join(", ", found));
                schemaDetails.put("found", String.join(", ", found));
                if (!app.expectedDatabases().isEmpty()) {
                    schemaDetails.put("expected", String.join(", ", app.expectedDatabases()));
                    List<String> missing = app.expectedDatabases().stream().filter(name -> !found.contains(name))
                            .toList();
                    if (!missing.isEmpty()) {
                        schemaDetails.put("missing", String.join(", ", missing));
                        schemaState = HealthState.DEGRADED;
                        notes.add("缺少期望的库：" + String.join("、", missing));
                    }
                }
            }
            catch (SQLException ex) {
                transcript.add("-- 查询失败：" + ex.getClass().getSimpleName());
                schemaDetails.put("schema_query", "查询失败：" + ex.getClass().getSimpleName());
                schemaState = HealthState.UNKNOWN;
            }
            components.add(new ComponentStatus("schemas", schemaState, schemaDetails));

            HealthState state = notes.isEmpty() ? HealthState.UP : HealthState.DEGRADED;
            return new Outcome(true, state, notes.isEmpty() ? null : String.join("；", notes), version,
                    HttpHealthProbe.sortBySeverity(components), transcript, null);
        }
        catch (SQLException | RuntimeException ex) {
            String reason = describe(ex, app);
            transcript.add("-- 连接失败：" + reason);
            return new Outcome(false, HealthState.DOWN, null, "", List.of(), transcript, failureMessage(ex, reason));
        }
    }

    /**
     * 由 {@code mysql://host:3306/db?a=b} 拼出 JDBC 地址，并补上超时等必备参数。
     *
     * <p>只在配置没写过同名参数时才补，好让个别目标能自己覆盖（例如某台库要求 useSSL=true）。
     * 超时参数是必补的：不带 {@code connectTimeout} 时驱动会一直等到操作系统的 TCP 超时，
     * 那已经远超一轮巡检的预算。</p>
     */
    static String jdbcUrl(MonitoredApplication app) {
        URI uri = app.uri();
        String path = uri.getPath();
        StringBuilder url = new StringBuilder("jdbc:mysql://").append(uri.getHost()).append(':').append(app.port());
        url.append((path == null || path.isBlank()) ? "/" : path);

        Map<String, String> params = new LinkedHashMap<>();
        if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
            for (String pair : uri.getQuery().split("&")) {
                int separator = pair.indexOf('=');
                if (separator > 0) {
                    params.put(pair.substring(0, separator), pair.substring(separator + 1));
                }
                else if (!pair.isBlank()) {
                    params.put(pair, "");
                }
            }
        }
        params.putIfAbsent("connectTimeout", String.valueOf(app.connectTimeout().toMillis()));
        params.putIfAbsent("socketTimeout", String.valueOf(app.readTimeout().toMillis()));
        params.putIfAbsent("useSSL", "false");
        params.putIfAbsent("allowPublicKeyRetrieval", "true");
        params.putIfAbsent("serverTimezone", "Asia/Shanghai");
        params.putIfAbsent("characterEncoding", "UTF-8");

        StringBuilder query = new StringBuilder();
        params.forEach((key, value) -> query.append(query.isEmpty() ? '?' : '&').append(key).append('=').append(value));
        return url.append(query).toString();
    }

    /**
     * JDBC 的异常文案常把连接串与主机名整段带出来，直接展示等于把连接信息搬上页面。
     * 这里只保留异常类型名与一小段原因，并把口令兜底替换掉。
     */
    private static String describe(Throwable ex, MonitoredApplication app) {
        String message = (ex.getMessage() == null) ? "" : ex.getMessage().replaceAll("\\s+", " ").trim();
        if (message.length() > 160) {
            message = message.substring(0, 160) + "…";
        }
        String scrubbed = ProbeSecrets.scrub(message, app.password());
        return scrubbed.isEmpty() ? ex.getClass().getSimpleName()
                : ex.getClass().getSimpleName() + "：" + scrubbed;
    }

    private static String failureMessage(Throwable ex, String reason) {
        String lower = (ex.getMessage() == null) ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        if (ex instanceof SQLException sql && "28000".equals(sql.getSQLState())) {
            return "认证失败：" + reason;
        }
        if (lower.contains("unknown database")) {
            return "库不存在：" + reason;
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "请求超时：" + reason;
        }
        if (lower.contains("communications link failure") || lower.contains("connection refused")) {
            return "无法建立连接：" + reason;
        }
        return "请求失败：" + reason;
    }

    private static long parseLong(String value) {
        try {
            return (value == null) ? -1L : Long.parseLong(value.trim());
        }
        catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    @PreDestroy
    void shutdown() {
        this.executor.shutdownNow();
    }

    /** JDBC 那一段跑完的结果，包含给明细用的语句执行记录。 */
    private record Outcome(boolean connected, HealthState state, String message, String version,
            List<ComponentStatus> components, List<String> transcript, String failure) {
    }
}
