package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.StubRedisServer;
import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.config.HealthCheckProperties.Detail;
import com.bgssai.healthcheck.config.HealthCheckProperties.Mysql;
import com.bgssai.healthcheck.config.HealthCheckProperties.Probe;
import com.bgssai.healthcheck.config.HealthCheckProperties.Redis;
import com.bgssai.healthcheck.config.HealthCheckProperties.Target;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.ProbeResult;
import com.bgssai.healthcheck.domain.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.TestSocketUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis / MySQL / TCP 三个直连探针的行为。
 *
 * <p>Redis 用 {@link StubRedisServer} 说真的 RESP 协议，因此覆盖的是完整链路
 * （编码请求、解析应答、解析 INFO、判定降级），不是对着 mock 断言。MySQL 没有可用的
 * 真实实例，只能覆盖不依赖对端的部分：JDBC 地址拼装与连不上时的失败路径。</p>
 */
class MiddlewareProbeTests {

    private static final Probe PROBE = new Probe(Duration.ofSeconds(2), Duration.ofSeconds(2), false, 65536, false);

    private static final String HEALTHY_INFO = """
            # Server
            redis_version:6.0.12
            redis_mode:standalone
            os:Linux 4.18.0
            uptime_in_days:37
            # Clients
            connected_clients:12
            blocked_clients:0
            maxclients:10000
            # Memory
            used_memory:104857600
            used_memory_human:100.00M
            maxmemory:1073741824
            maxmemory_human:1.00G
            used_memory_peak_human:120.00M
            mem_fragmentation_ratio:1.12
            # Persistence
            rdb_last_bgsave_status:ok
            aof_enabled:0
            aof_last_write_status:ok
            rdb_changes_since_last_save:4
            # Replication
            role:master
            connected_slaves:1
            """;

    private static final String TIGHT_MEMORY_INFO = HEALTHY_INFO
            .replace("used_memory:104857600", "used_memory:1020000000")
            .replace("rdb_last_bgsave_status:ok", "rdb_last_bgsave_status:err");

    @Test
    @DisplayName("Redis：PING 通过且 INFO 各项正常时判 UP，并把 INFO 原文留进明细")
    void redisHealthyPath() {
        try (StubRedisServer stub = new StubRedisServer("s3cret", HEALTHY_INFO)) {
            ProbeResult result = probeRedis(stub, "s3cret");

            assertThat(result.state()).isEqualTo(HealthState.UP);
            assertThat(result.message()).isNull();
            assertThat(result.components()).extracting(component -> component.name())
                    .containsExactlyInAnyOrder("server", "clients", "memory", "persistence", "replication");
            assertThat(result.components()).filteredOn(component -> "server".equals(component.name()))
                    .singleElement()
                    .satisfies(component -> assertThat(component.details()).containsEntry("redis_version", "6.0.12"));

            // 明细里是 INFO 的原文，这正是用户要的「原始响应」
            assertThat(result.detail()).isNotNull();
            assertThat(result.detail().protocol()).isEqualTo("RESP");
            assertThat(result.detail().statusLine()).isEqualTo("+PONG");
            assertThat(result.detail().body()).contains("redis_version:6.0.12").contains("# Memory");

            // 口令绝不能出现在明细里
            assertThat(result.detail().request()).contains("AUTH ******").doesNotContain("s3cret");
            assertThat(stub.received()).contains("AUTH s3cret", "PING", "INFO");
        }
    }

    @Test
    @DisplayName("Redis：内存逼近 maxmemory、持久化报错时判降级并说明原因")
    void redisDegradesOnMemoryAndPersistence() {
        try (StubRedisServer stub = new StubRedisServer("s3cret", TIGHT_MEMORY_INFO)) {
            ProbeResult result = probeRedis(stub, "s3cret");

            assertThat(result.state()).isEqualTo(HealthState.DEGRADED);
            assertThat(result.message()).contains("已用内存").contains("持久化异常");
            assertThat(result.components()).filteredOn(component -> "memory".equals(component.name()))
                    .singleElement()
                    .satisfies(component -> {
                        assertThat(component.state()).isEqualTo(HealthState.DEGRADED);
                        assertThat(component.details()).containsKey("used_memory_percent");
                    });
        }
    }

    @Test
    @DisplayName("Redis：口令不对时判 DOWN，错误原文里的口令被脱敏")
    void redisAuthFailure() {
        try (StubRedisServer stub = new StubRedisServer("s3cret", HEALTHY_INFO)) {
            ProbeResult result = probeRedis(stub, "wrong-password");

            assertThat(result.state()).isEqualTo(HealthState.DOWN);
            assertThat(result.message()).startsWith("认证失败：");
            assertThat(result.detail().request()).doesNotContain("wrong-password");
        }
    }

    @Test
    @DisplayName("Redis：端口没人监听时判 DOWN 且带上失败明细")
    void redisUnreachable() {
        int closedPort = TestSocketUtils.findAvailableTcpPort();
        MonitoredApplication app = resolve(target("cache", "redis://127.0.0.1:" + closedPort, null, null,
                TargetType.REDIS));

        ProbeResult result = new RedisHealthProbe(propertiesOf()).probe(app);

        assertThat(result.state()).isEqualTo(HealthState.DOWN);
        assertThat(result.message()).contains("无法建立连接");
        assertThat(result.detail().error()).isNotBlank();
    }

    @Test
    @DisplayName("TCP：端口能连上即 UP，连不上则 DOWN")
    void tcpProbeDistinguishesOpenAndClosedPorts() {
        try (StubRedisServer stub = new StubRedisServer("x", HEALTHY_INFO)) {
            MonitoredApplication open = resolve(target("mq", "tcp://127.0.0.1:" + stub.port(), null, null,
                    TargetType.TCP));
            ProbeResult result = new TcpHealthProbe().probe(open);

            assertThat(result.state()).isEqualTo(HealthState.UP);
            assertThat(result.components()).singleElement()
                    .satisfies(component -> assertThat(component.details()).containsKey("connect_ms"));
            assertThat(result.detail().statusLine()).isEqualTo("连接成功");
        }

        int closedPort = TestSocketUtils.findAvailableTcpPort();
        MonitoredApplication closed = resolve(target("mq", "tcp://127.0.0.1:" + closedPort, null, null,
                TargetType.TCP));

        assertThat(new TcpHealthProbe().probe(closed).state()).isEqualTo(HealthState.DOWN);
    }

    @Test
    @DisplayName("MySQL：由 mysql:// 地址拼出带超时参数的 JDBC 地址，已有参数不被覆盖")
    void mysqlBuildsJdbcUrl() {
        MonitoredApplication plain = resolve(target("db", "mysql://db.internal:3306/", "root", "pw",
                TargetType.MYSQL));
        String url = MysqlHealthProbe.jdbcUrl(plain);

        assertThat(url).startsWith("jdbc:mysql://db.internal:3306/?")
                .contains("connectTimeout=2000")
                .contains("socketTimeout=2000")
                .contains("serverTimezone=Asia/Shanghai");

        MonitoredApplication overridden = resolve(target("db", "mysql://db.internal:3306/bgssai_blog?useSSL=true",
                "root", "pw", TargetType.MYSQL));
        String custom = MysqlHealthProbe.jdbcUrl(overridden);

        assertThat(custom).startsWith("jdbc:mysql://db.internal:3306/bgssai_blog?")
                .contains("useSSL=true")
                .doesNotContain("useSSL=false");
    }

    @Test
    @DisplayName("MySQL：连不上时判 DOWN，明细里不出现口令")
    void mysqlUnreachable() {
        int closedPort = TestSocketUtils.findAvailableTcpPort();
        MonitoredApplication app = resolve(target("db", "mysql://127.0.0.1:" + closedPort + "/bgssai_blog", "root",
                "2aB?fG9*nL", TargetType.MYSQL));

        MysqlHealthProbe probe = new MysqlHealthProbe(propertiesOf());
        ProbeResult result = probe.probe(app);

        assertThat(result.state()).isEqualTo(HealthState.DOWN);
        assertThat(result.message()).isNotBlank();
        assertThat(result.detail()).isNotNull();
        assertThat(result.detail().request()).doesNotContain("2aB?fG9*nL").contains("******");
    }

    private static ProbeResult probeRedis(StubRedisServer stub, String password) {
        MonitoredApplication app = resolve(target("cache", stub.url(), null, password, TargetType.REDIS));
        return new RedisHealthProbe(propertiesOf()).probe(app);
    }

    private static MonitoredApplication resolve(Target target) {
        return new ApplicationRegistry(propertiesOf(target)).findAll().getFirst();
    }

    private static Target target(String id, String url, String username, String password, TargetType type) {
        return new Target(id, id, "测试", url, type, "GET", true, false, List.of(), Map.of(), username, password,
                null, null, List.of(), List.of(), null, null);
    }

    private static HealthCheckProperties propertiesOf(Target... targets) {
        return new HealthCheckProperties(false, Duration.ofSeconds(30), Duration.ofSeconds(3), 8, 20, 0, PROBE,
                new Detail(true, 16384, true), new Redis(90), new Mysql(90, 2), List.of(targets));
    }
}
