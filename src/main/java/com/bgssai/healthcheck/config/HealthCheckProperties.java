package com.bgssai.healthcheck.config;

import com.bgssai.healthcheck.domain.TargetType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 巡检平台的全部可配置项，前缀 {@code bgssai.healthcheck}。
 */
@Validated
@ConfigurationProperties(prefix = "bgssai.healthcheck")
public record HealthCheckProperties(

        /* 是否启用后台定时巡检；关闭后只能通过接口手动触发。 */
        @DefaultValue("true") boolean scheduled,

        /* 两轮巡检之间的间隔（上一轮结束到下一轮开始）。 */
        @DefaultValue("30s") Duration refreshInterval,

        /* 应用启动后首轮巡检的延迟。 */
        @DefaultValue("3s") Duration initialDelay,

        /* 单轮巡检允许的最大并发探测数。 */
        @Min(1) @Max(512) @DefaultValue("16") int concurrency,

        /* 每个应用保留的历史采样点数量，用于计算可用率和绘制趋势条。 */
        @Min(1) @Max(2000) @DefaultValue("60") int historySize,

        /* 页面自动刷新间隔（秒），0 表示不自动刷新。 */
        @Min(0) @Max(3600) @DefaultValue("10") int uiRefreshSeconds,

        @DefaultValue @Valid Probe probe,

        @DefaultValue @Valid Detail detail,

        @DefaultValue @Valid Redis redis,

        @DefaultValue @Valid Mysql mysql,

        @DefaultValue List<@Valid Target> applications) {

    /** 探测行为的全局默认值。 */
    public record Probe(
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout,
            /* 是否跟随 3xx 跳转；健康检查通常不希望跳转到登录页后被判定为正常。 */
            @DefaultValue("false") boolean followRedirects,
            /* 读取响应体的最大字节数，防止对端返回超大内容拖垮巡检。 */
            @Min(0) @DefaultValue("65536") int maxBodyBytes,

            /*
             * 探测 HTTPS 目标时是否跳过证书链与主机名校验的全局默认值。
             *
             * 默认 false，即按标准校验。之所以需要这个开关：BGSSAI 各后端在 dev / prod 都以
             * TLS 监听 443，证书是签给业务域名的（如 www.bgssai-blog.com），而巡检按机器 IP
             * 直连——TLS 握手会因主机名不匹配失败，健康的应用会被误判为 DOWN。同样的问题也
             * 出现在 Elasticsearch 上：三台实例都是自签证书（curl 需要 -k）。
             *
             * 打开它不会削弱被监控方的安全性：三个健康端点本就是公开的，响应体按 Standards §13.4
             * 只含白名单字段，不含凭据、主机、连接串或堆栈；探针也只做 GET、不带业务令牌，
             * 因此中间人能看到 / 篡改的信息与它直接自己请求该公开端点等价。
             *
             * 若不希望放开，正确的替代做法是给每台机器配域名并按域名探测（见 README）。
             */
            @DefaultValue("false") boolean skipTlsVerification) {
    }

    /**
     * 原始明细的保留策略。
     *
     * <p>明细是排障时唯一能回答「对端到底说了什么」的东西，但它按目标常驻内存，
     * 所以要给上限：{@code maxBodyChars} 只影响「保留多少」，不影响「读取多少」
     * （后者由 {@link Probe#maxBodyBytes()} 控制，判定状态用的是完整响应体）。</p>
     */
    public record Detail(

            /* 是否保留原始明细；关闭后看板与报告只展示归一化结果。 */
            @DefaultValue("true") boolean enabled,

            /* 单条明细保留的最大字符数，超出部分截断并在页面上标注。 */
            @Min(0) @Max(1048576) @DefaultValue("16384") int maxBodyChars,

            /* 是否额外保留最近一次失败的明细，便于目标恢复后回溯当时的应答。 */
            @DefaultValue("true") boolean keepLastFailure) {
    }

    /** Redis 探针的判定阈值。 */
    public record Redis(

            /* 已用内存占 maxmemory 的比例达到该百分比时判定为降级；未设置 maxmemory 时不判定。 */
            @Min(1) @Max(100) @DefaultValue("90") int memoryWarnPercent) {
    }

    /** MySQL 探针的判定阈值。 */
    public record Mysql(

            /* 当前连接数占 max_connections 的比例达到该百分比时判定为降级。 */
            @Min(1) @Max(100) @DefaultValue("90") int connectionWarnPercent,

            /* 单条校验语句的超时（秒），到点仍未返回即判定为异常。 */
            @Min(1) @Max(60) @DefaultValue("3") int queryTimeoutSeconds) {
    }

    /** 一个被监控的目标：应用、中间件或数据库。 */
    public record Target(

            /* 唯一标识；留空时由名称自动推导。 */
            String id,

            @NotBlank String name,

            @DefaultValue("未分组") String group,

            /*
             * 目标地址，scheme 决定用哪个探针：
             *   http / https  HTTP 健康接口（type=elasticsearch 时按集群颜色判定）
             *   redis / rediss Redis
             *   mysql          MySQL
             *   tcp            只验证端口可连通
             */
            @NotBlank String url,

            /*
             * 目标种类；留空时由 url 的 scheme 推导。
             * Elasticsearch 的 scheme 与普通 HTTP 相同，必须显式写 elasticsearch。
             */
            TargetType type,

            /* 只支持 GET / HEAD，且只对 HTTP 系目标生效。 */
            @DefaultValue("GET") String method,

            @DefaultValue("true") boolean enabled,

            /* 关键目标异常时，平台自身的 /actuator/health 也会变为 DOWN。 */
            @DefaultValue("false") boolean critical,

            @DefaultValue List<String> tags,

            @DefaultValue Map<String, String> headers,

            /*
             * 账号口令。HTTP 系目标用它生成 Basic 认证头（Elasticsearch 的 elastic 账号即走这里），
             * Redis 用作 AUTH 参数，MySQL 用作 JDBC 登录账号。
             */
            String username,
            String password,

            /* 未配置时回退到 probe 的全局默认值。 */
            Duration connectTimeout,
            Duration readTimeout,

            /* 判定为「接口调用成功」的 HTTP 状态码；留空表示任意 2xx。只对 HTTP 系目标生效。 */
            @DefaultValue List<Integer> expectedStatuses,

            /*
             * 期望存在的数据库名，只对 MySQL 目标生效。
             * 配置后探针会核对 information_schema，缺哪个库就判降级并写进说明——
             * bgssai-database 的 clean 流水线会 DROP DATABASE，库被清掉时应用要到下次
             * 访问才报错，这里能提前发现。
             */
            @DefaultValue List<String> expectedDatabases,

            /* 备注，展示在看板上。 */
            String description,

            /* 覆盖 probe.skip-tls-verification；留空表示沿用全局默认值。 */
            Boolean skipTlsVerification) {
    }
}
