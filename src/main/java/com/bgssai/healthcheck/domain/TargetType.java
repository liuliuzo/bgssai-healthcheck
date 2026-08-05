package com.bgssai.healthcheck.domain;

import java.util.List;
import java.util.Locale;

/**
 * 被监控目标的种类，决定用哪个探针去探测它。
 *
 * <p>平台最初只探 HTTP 健康接口，但应用「自报健康」这一条链路有个天然盲区：
 * 按 Standards §13.1，就绪探针只由 critical 组件（{@code db} / {@code mybatis}）决定结论，
 * Redis、Elasticsearch 这类非 critical 依赖既不参与判定、也不在就绪端点被检查。
 * 换句话说，中间件挂了应用照样报 UP。因此中间件与数据库必须由本平台直连探测，
 * 而不是指望从应用的健康接口里读出来。</p>
 */
public enum TargetType {

    /** 普通 HTTP / HTTPS 健康检查接口。 */
    HTTP("HTTP 接口", List.of("http", "https"), -1),

    /** Elasticsearch 集群健康接口，按 green / yellow / red 判定。 */
    ELASTICSEARCH("Elasticsearch", List.of("http", "https"), 9200),

    /** Redis，按 RESP 协议直连发 PING 与 INFO。 */
    REDIS("Redis", List.of("redis", "rediss"), 6379),

    /** MySQL，用 JDBC 建连接并跑校验语句。 */
    MYSQL("MySQL", List.of("mysql"), 3306),

    /** 只验证 TCP 端口可连通，用于没有专用探针的中间件。 */
    TCP("TCP 端口", List.of("tcp"), -1);

    private final String label;

    private final List<String> schemes;

    private final int defaultPort;

    TargetType(String label, List<String> schemes, int defaultPort) {
        this.label = label;
        this.schemes = schemes;
        this.defaultPort = defaultPort;
    }

    /** 中文展示名，供页面与报告使用。 */
    public String getLabel() {
        return this.label;
    }

    /** 小写形式，供 CSS class 与接口字段使用。 */
    public String getCode() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 该类型允许的 url scheme。 */
    public List<String> getSchemes() {
        return this.schemes;
    }

    /** url 未写端口时补上的默认端口，{@code -1} 表示由 scheme 自行决定（HTTP 80 / HTTPS 443）。 */
    public int getDefaultPort() {
        return this.defaultPort;
    }

    public boolean supportsScheme(String scheme) {
        return (scheme != null) && this.schemes.contains(scheme.toLowerCase(Locale.ROOT));
    }

    /** 走 HTTP 客户端的类型：HTTP 与 Elasticsearch 共用同一套请求与响应捕获逻辑。 */
    public boolean isHttpBased() {
        return this == HTTP || this == ELASTICSEARCH;
    }

    /**
     * 由 url 的 scheme 推导类型，未显式配置 {@code type} 时使用。
     *
     * <p>{@code https://host:9200/_cluster/health} 会被推导为 {@link #HTTP}——Elasticsearch
     * 与普通 HTTP 接口的 scheme 相同，无法区分，需要在配置里显式写 {@code type=elasticsearch}。</p>
     */
    public static TargetType fromScheme(String scheme) {
        if (scheme == null) {
            return null;
        }
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "http", "https" -> HTTP;
            case "redis", "rediss" -> REDIS;
            case "mysql" -> MYSQL;
            case "tcp" -> TCP;
            default -> null;
        };
    }
}
