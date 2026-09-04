package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.config.HealthCheckProperties.Probe;
import com.bgssai.healthcheck.config.HealthCheckProperties.Target;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.ProbeResult;
import com.bgssai.healthcheck.domain.TargetType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 云电脑宿主（{@link TargetType#BOXPOOL}）的判定守护。
 *
 * <p>这一类目标与其它目标有个根本差别：<strong>宿主活着不等于可用</strong>。
 * 每个用户占一台带桌面的容器，宿主内存一到底就再也开不出新的——此时进程健在、
 * 端口正常、HTTP 200，但下一个想用云电脑的人会被拒之门外。看板若因此报绿，
 * 就会一直绿到用户打电话来说打不开电脑为止。</p>
 *
 * <p>三个用例覆盖三种水位：有余量、余量归零、宿主根本不通。</p>
 */
class BoxPoolProbeTests {

    private HttpServer server;

    @AfterEach
    void shutdown() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    /** 起一个只回一份容量 JSON 的桩，模拟编排服务的 {@code /v1/capacity}。 */
    private String stub(String json) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/v1/capacity", (exchange) -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        this.server.start();
        return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1/capacity";
    }

    private ProbeResult probe(String url) {
        Probe probe = new Probe(Duration.ofSeconds(3), Duration.ofSeconds(5), false, 65536, false);
        Target target = new Target("boxpool", "boxpool", "g", url, TargetType.BOXPOOL, "GET",
                true, false, List.of(), Map.of(), null, null, null, null, List.of(), List.of(), null, false);
        HealthCheckProperties properties = new HealthCheckProperties(false, Duration.ofSeconds(30),
                Duration.ofSeconds(3), 16, 60, 10, probe,
                new HealthCheckProperties.Detail(true, 16384, true), new HealthCheckProperties.Redis(90),
                new HealthCheckProperties.Mysql(90, 3), List.of(target));

        MonitoredApplication app = new ApplicationRegistry(properties).findAll().get(0);
        return new HttpHealthProbe(RestClient.builder(), JsonMapper.builder().build(), properties).probe(app);
    }

    @Test
    @DisplayName("还有余量：报 UP，并说清楚还能接几个人")
    void reportsHeadroom() throws IOException {
        ProbeResult result = probe(stub("""
                {"ok":true,"headroom":10,"online":1,"maxOnline":20,
                 "boxesTotal":37,"freeMemMb":12525,"boxMemMb":1200}"""));

        assertThat(result.state()).isEqualTo(HealthState.UP);
        assertThat(result.message()).contains("还能接 10 人");

        Map<String, String> capacity = result.components().stream()
                .filter((c) -> "在线容量".equals(c.name()))
                .findFirst().orElseThrow().details();
        assertThat(capacity).containsEntry("headroom", "10").containsEntry("online", "1");

        // 已开电脑数远大于在线数是正常的：休眠的 box 不占内存，只占磁盘。
        Map<String, String> stored = result.components().stream()
                .filter((c) -> "已开电脑".equals(c.name()))
                .findFirst().orElseThrow().details();
        assertThat(stored).containsEntry("boxesTotal", "37");
    }

    @Test
    @DisplayName("余量归零：宿主活着也要判降级——再来一个人就用不了了")
    void degradesWhenFull() throws IOException {
        ProbeResult result = probe(stub("""
                {"ok":true,"headroom":0,"online":20,"maxOnline":20,
                 "boxesTotal":37,"freeMemMb":800,"boxMemMb":1200}"""));

        assertThat(result.state())
                .as("HTTP 200 且进程健在，但接不下新用户，看板不能报绿")
                .isEqualTo(HealthState.DEGRADED);
        assertThat(result.message()).contains("已满");
    }

    @Test
    @DisplayName("宿主不通：判 DOWN，不因为读不到容量就当成没事")
    void downWhenUnreachable() {
        // 端口上没有人监听
        ProbeResult result = probe("http://127.0.0.1:1/v1/capacity");
        assertThat(result.state()).isEqualTo(HealthState.DOWN);
    }
}
