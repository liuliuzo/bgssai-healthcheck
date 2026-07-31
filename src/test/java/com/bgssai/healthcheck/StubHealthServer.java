package com.bgssai.healthcheck;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * 用 JDK 自带的 HttpServer 模拟被监控应用，覆盖健康检查接口的各种返回形态。
 *
 * <p>比让测试去探测自己的 Actuator 端点更可控：可以精确制造 503、非 JSON 响应和超时。</p>
 */
final class StubHealthServer implements AutoCloseable {

    private static final String ACTUATOR_UP = """
            {"status":"UP","components":{"db":{"status":"UP","details":{"database":"H2"}},\
            "diskSpace":{"status":"UP"}}}""";

    private static final String ACTUATOR_DOWN = """
            {"status":"DOWN","components":{"db":{"status":"DOWN","details":{"error":"connection refused"}}}}""";

    private static final String ACTUATOR_OUT_OF_SERVICE = """
            {"status":"OUT_OF_SERVICE"}""";

    /** BGSSAI 产品线形态一：健康负载包在 {code, message, success, result} 封装里（Standards §13.3）。 */
    private static final String WRAPPED_RESULT_DOWN = """
            {"code":"0","message":null,"success":true,\
            "result":{"status":"DOWN","app":"bgssai-vpn-user","checked_at":"2026-07-31 10:24:05",\
            "components":[{"name":"db","status":"DOWN"},{"name":"mybatis","status":"DOWN"}]}}""";

    /** BGSSAI 产品线形态二：健康负载包在 {code, message, data} 封装里，且整体只是降级（HTTP 仍 200）。 */
    private static final String WRAPPED_DATA_DEGRADED = """
            {"code":0,"message":"OK",\
            "data":{"status":"DEGRADED","app":"bgssai-blog-user","checked_at":"2026-07-31 10:24:05",\
            "components":[{"name":"disk_space","status":"DEGRADED"},{"name":"db","status":"UP"}]}}""";

    private final HttpServer server;

    StubHealthServer() {
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        catch (IOException ex) {
            throw new IllegalStateException("无法启动测试用 HTTP 服务", ex);
        }
        this.server.setExecutor(Executors.newFixedThreadPool(4));

        // 200 + status=UP，带子组件
        this.server.createContext("/up", respond(200, "application/json", ACTUATOR_UP));
        // Actuator 的惯例：503 + status=DOWN
        this.server.createContext("/down", respond(503, "application/json", ACTUATOR_DOWN));
        // 200 + status=OUT_OF_SERVICE，应被判定为降级
        this.server.createContext("/degraded", respond(200, "application/json", ACTUATOR_OUT_OF_SERVICE));
        // 封装在 result 里的健康负载：503 + result.status=DOWN
        this.server.createContext("/wrapped-result", respond(503, "application/json", WRAPPED_RESULT_DOWN));
        // 封装在 data 里的健康负载：200 + data.status=DEGRADED
        this.server.createContext("/wrapped-data", respond(200, "application/json", WRAPPED_DATA_DEGRADED));
        // 没有 JSON 报文，只能按状态码判断
        this.server.createContext("/plain", respond(200, "text/plain", "OK"));
        // 用 204 表示健康的接口
        this.server.createContext("/no-content", respond(204, "text/plain", ""));
        // 慢接口，用来触发读超时
        this.server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000L);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            respond(200, "application/json", "{\"status\":\"UP\"}").handle(exchange);
        });

        this.server.start();
    }

    int port() {
        return this.server.getAddress().getPort();
    }

    String url(String path) {
        return "http://127.0.0.1:" + port() + path;
    }

    private static HttpHandler respond(int status, String contentType, String body) {
        return exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType + ";charset=UTF-8");
            // 204 不允许有响应体，用 -1 表示无内容
            exchange.sendResponseHeaders(status, (payload.length == 0) ? -1 : payload.length);
            if (payload.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
            exchange.close();
        };
    }

    @Override
    public void close() {
        this.server.stop(0);
    }
}
