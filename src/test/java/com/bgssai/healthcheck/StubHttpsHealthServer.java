package com.bgssai.healthcheck;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.Executors;

/**
 * 模拟「以 TLS 监听、但证书签给别的主机名」的被监控应用。
 *
 * <p>这正是产线现状：18 个后端都在 443 上以 TLS 监听，证书签给业务域名
 * （www.bgssai-blog.com / www.bgssai-geo.cn / www.bgssai-geo.com 等），而巡检按机器 IP 直连。</p>
 *
 * <p>本类加载的自签证书 CN 固定为 {@code some-other-host.example}
 * （{@code src/test/resources/stub-mismatched-host.p12}），而测试用
 * {@code https://127.0.0.1:<port>} 访问它，因此**必然**触发主机名不匹配。
 * 用来验证两件事：默认配置下探测确实会失败（说明这个坑真实存在），
 * 打开 {@code skip-tls-verification} 后确实能探通。</p>
 */
public final class StubHttpsHealthServer implements AutoCloseable {

    private static final String KEYSTORE = "/stub-mismatched-host.p12";

    private static final char[] PASSWORD = "changeit".toCharArray();

    private static final String READY = """
            {"code":0,"message":"OK","data":{"status":"UP","app":"stub","checked_at":"2026-08-01 00:00:00",\
            "components":[{"name":"db","status":"UP"},{"name":"mybatis","status":"UP"}]}}""";

    private final HttpsServer server;

    public StubHttpsHealthServer() {
        try {
            this.server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.setHttpsConfigurator(new HttpsConfigurator(sslContext()));
            this.server.createContext("/bgssai/health/readiness", exchange -> {
                byte[] body = READY.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            this.server.start();
        }
        catch (Exception ex) {
            throw new IllegalStateException("启动 HTTPS stub 失败", ex);
        }
    }

    public String url(String path) {
        return "https://127.0.0.1:" + this.server.getAddress().getPort() + path;
    }

    private static SSLContext sslContext() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = StubHttpsHealthServer.class.getResourceAsStream(KEYSTORE)) {
            keyStore.load(in, PASSWORD);
        }
        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, PASSWORD);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
        return sslContext;
    }

    @Override
    public void close() {
        this.server.stop(0);
    }
}
