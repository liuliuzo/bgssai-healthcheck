package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.ProbeDetail;
import com.bgssai.healthcheck.domain.ProbeResult;
import com.bgssai.healthcheck.domain.ProbeResult.ComponentStatus;
import com.bgssai.healthcheck.domain.TargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 只验证 TCP 端口能不能连上的通用探针。
 *
 * <p>给没有专用探针的中间件兜底：EMQX 的 1883、注册中心、网关这类目标，能建立连接
 * 就足以区分「进程没了 / 安全组不通」与「服务在跑」，比完全不监控强得多。它不证明
 * 服务能正确应答——这一点在看板与报告里都要如实呈现，不要拿它当就绪判据。</p>
 */
@Component
public class TcpHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(TcpHealthProbe.class);

    /** 只读一小段问候语；多数服务不会主动打招呼，读不到属正常。 */
    private static final int MAX_BANNER_BYTES = 256;

    private static final long MAX_BANNER_WAIT_MS = 1000L;

    @Override
    public Set<TargetType> supportedTypes() {
        return Set.of(TargetType.TCP);
    }

    @Override
    public ProbeResult probe(MonitoredApplication app) {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        String request = "tcp://" + app.hostPort();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(app.host(), app.port()),
                    (int) Math.max(1L, app.connectTimeout().toMillis()));
            long connectMs = elapsedMs(startNanos);

            String banner = readBanner(socket, app);
            Map<String, String> details = new LinkedHashMap<>();
            details.put("host", app.host());
            details.put("port", String.valueOf(app.port()));
            details.put("connect_ms", String.valueOf(connectMs));

            return ProbeResult.of(HealthState.UP, null, elapsedMs(startNanos), startedAt, null,
                    List.of(new ComponentStatus("socket", HealthState.UP, details)),
                    ProbeDetail.text("TCP", request, "连接成功",
                            banner.isEmpty() ? "对端未主动返回数据" : banner));
        }
        catch (Exception ex) {
            String reason = describeFailure(ex);
            log.debug("探测 TCP 端口 [{}] ({}) 失败：{}", app.id(), app.hostPort(), reason);
            return ProbeResult.failure(elapsedMs(startNanos), startedAt, reason,
                    ProbeDetail.failed("TCP", request, null, reason));
        }
    }

    /** 读不到问候语不算失败，所以这里把异常吞掉，只把读到的内容带回去。 */
    private static String readBanner(Socket socket, MonitoredApplication app) {
        try {
            socket.setSoTimeout((int) Math.max(1L, Math.min(MAX_BANNER_WAIT_MS, app.readTimeout().toMillis())));
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[MAX_BANNER_BYTES];
            int read = in.read(buffer);
            return (read > 0) ? printable(new String(buffer, 0, read, StandardCharsets.UTF_8)) : "";
        }
        catch (Exception ex) {
            return "";
        }
    }

    /** 二进制协议的问候语里常有控制字符，直接塞进页面会破坏排版，统一转义成 \\xNN。 */
    private static String printable(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t' || c >= 0x20) {
                sb.append(c);
            }
            else {
                sb.append("\\x").append(String.format("%02X", (int) c));
            }
        }
        return sb.toString();
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
}
