package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.domain.TargetType;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置解析后的被监控目标：所有可选项都已经填上最终值，端口也已补全。
 */
public record MonitoredApplication(
        String id,
        String name,
        String group,
        String description,
        TargetType type,
        /** 已规范化的地址：端口一定存在，Elasticsearch 的默认路径也已补上。 */
        URI uri,
        HttpMethod method,
        boolean enabled,
        boolean critical,
        List<String> tags,
        Map<String, String> headers,
        /** HTTP Basic 的账号口令原文，同时供 Redis AUTH 与 JDBC 登录使用；未配置时为 {@code null}。 */
        String username,
        String password,
        /** 已经编码好的 Authorization 头，未配置认证时为 {@code null}。 */
        String authorization,
        Duration connectTimeout,
        Duration readTimeout,
        /** 空集合表示「任意 2xx 都算成功」。 */
        Set<Integer> expectedStatuses,
        /** 期望存在的数据库名，空集合表示不做核对。 */
        List<String> expectedDatabases,

        /** 探测 HTTPS 目标时是否跳过证书链与主机名校验（已合并全局默认值与本条覆盖值）。 */
        boolean skipTlsVerification) {

    public MonitoredApplication {
        tags = (tags == null) ? List.of() : List.copyOf(tags);
        headers = (headers == null) ? Map.of() : Map.copyOf(headers);
        expectedStatuses = (expectedStatuses == null) ? Set.of() : Set.copyOf(expectedStatuses);
        expectedDatabases = (expectedDatabases == null) ? List.of() : List.copyOf(expectedDatabases);
    }

    /** 目标主机。 */
    public String host() {
        return this.uri.getHost();
    }

    /**
     * 有效端口。非 HTTP 系目标在解析阶段已把端口写进 uri；HTTP 系刻意不写
     * （补成 {@code https://host:443/} 会改变 Host 头），这里按 scheme 算出来。
     */
    public int port() {
        int explicit = this.uri.getPort();
        if (explicit >= 0) {
            return explicit;
        }
        return "https".equalsIgnoreCase(this.uri.getScheme()) ? 443 : 80;
    }

    /** {@code host:port}，用于日志与明细展示。 */
    public String hostPort() {
        return host() + ':' + port();
    }
}
