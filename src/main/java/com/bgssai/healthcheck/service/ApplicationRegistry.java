package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.domain.TargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 把配置里的目标列表解析成运行期的 {@link MonitoredApplication}，并保证 id 唯一。
 *
 * <p>解析期就把能发现的错误全抛出来（地址非法、类型与 scheme 不匹配、缺端口、
 * 方法不受支持），让应用直接起不来；否则这些错误要等到第一轮巡检才以「DOWN」
 * 的形式出现，而 DOWN 在看板上与「对端真的挂了」长得一模一样。</p>
 */
@Component
public class ApplicationRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApplicationRegistry.class);

    private static final Set<HttpMethod> SUPPORTED_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD);

    /** Elasticsearch 未写路径时默认探集群健康接口。 */
    private static final String ELASTICSEARCH_DEFAULT_PATH = "/_cluster/health";

    private final List<MonitoredApplication> applications;
    private final Map<String, MonitoredApplication> byId;

    public ApplicationRegistry(HealthCheckProperties properties) {
        this.applications = List.copyOf(resolve(properties));
        Map<String, MonitoredApplication> index = new LinkedHashMap<>();
        this.applications.forEach(app -> index.put(app.id(), app));
        this.byId = Map.copyOf(index);
        log.info("已加载 {} 个被监控目标（其中启用 {} 个）：{}", this.applications.size(), enabled().size(),
                countByType());
    }

    /** 配置顺序的全部目标，含已停用的。 */
    public List<MonitoredApplication> findAll() {
        return this.applications;
    }

    /** 参与巡检的目标。 */
    public List<MonitoredApplication> enabled() {
        return this.applications.stream().filter(MonitoredApplication::enabled).toList();
    }

    public Optional<MonitoredApplication> findById(String id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    /** 按类型统计目标数量，供启动日志与报告使用。 */
    public Map<TargetType, Integer> countByType() {
        Map<TargetType, Integer> counts = new EnumMap<>(TargetType.class);
        this.applications.forEach(app -> counts.merge(app.type(), 1, Integer::sum));
        return counts;
    }

    private static List<MonitoredApplication> resolve(HealthCheckProperties properties) {
        List<HealthCheckProperties.Target> targets = properties.applications();
        HealthCheckProperties.Probe probe = properties.probe();
        List<MonitoredApplication> resolved = new ArrayList<>(targets.size());
        Set<String> usedIds = new java.util.HashSet<>();

        for (int i = 0; i < targets.size(); i++) {
            HealthCheckProperties.Target target = targets.get(i);
            String id = uniqueId(target, i, usedIds);
            URI uri = parseUri(target);
            TargetType type = resolveType(target, uri);
            resolved.add(new MonitoredApplication(
                    id,
                    target.name().trim(),
                    blankToDefault(target.group(), "未分组"),
                    trimToNull(target.description()),
                    type,
                    normalizeUri(target, type, uri),
                    parseMethod(target, type),
                    target.enabled(),
                    target.critical(),
                    normalizeTags(target.tags()),
                    target.headers(),
                    trimToNull(target.username()),
                    target.password(),
                    basicAuthHeader(target, type),
                    firstNonNull(target.connectTimeout(), probe.connectTimeout()),
                    firstNonNull(target.readTimeout(), probe.readTimeout()),
                    Set.copyOf(target.expectedStatuses()),
                    expectedDatabases(target, type),
                    firstNonNull(target.skipTlsVerification(), probe.skipTlsVerification())));
        }
        return resolved;
    }

    private static String uniqueId(HealthCheckProperties.Target target, int index, Set<String> used) {
        String base = slugify(target.id());
        if (base.isEmpty()) {
            base = slugify(target.name());
        }
        if (base.isEmpty()) {
            base = "app-" + (index + 1);
        }
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + '-' + suffix++;
        }
        return candidate;
    }

    /**
     * 生成 URL 友好的标识：保留字母数字，其余字符折叠为 {@code -}。
     * 纯中文名称会得到空串，此时由调用方回退到 {@code app-N}。
     */
    private static String slugify(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        boolean pendingSeparator = false;
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (pendingSeparator && !sb.isEmpty()) {
                    sb.append('-');
                }
                pendingSeparator = false;
                sb.append(c);
            }
            else {
                pendingSeparator = true;
            }
        }
        return sb.toString();
    }

    private static URI parseUri(HealthCheckProperties.Target target) {
        try {
            URI uri = new URI(target.url().trim());
            if (!uri.isAbsolute() || uri.getHost() == null) {
                throw new IllegalArgumentException("必须是带主机名的绝对地址");
            }
            return uri;
        }
        catch (URISyntaxException | IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "目标 [%s] 的地址 [%s] 不合法：%s".formatted(target.name(), target.url(), ex.getMessage()), ex);
        }
    }

    /** 显式配置的 {@code type} 优先，否则由 scheme 推导；两者必须自洽。 */
    private static TargetType resolveType(HealthCheckProperties.Target target, URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        TargetType declared = target.type();
        if (declared == null) {
            TargetType derived = TargetType.fromScheme(scheme);
            if (derived == null) {
                throw new IllegalStateException(
                        "目标 [%s] 的地址 scheme [%s] 无法推导目标类型，支持的是 http / https / redis / rediss / mysql / tcp"
                                .formatted(target.name(), scheme));
            }
            return derived;
        }
        if (!declared.supportsScheme(scheme)) {
            throw new IllegalStateException("目标 [%s] 声明 type=%s，但地址 scheme 是 [%s]，该类型只接受 %s"
                    .formatted(target.name(), declared.getCode(), scheme, declared.getSchemes()));
        }
        return declared;
    }

    /**
     * 规范化地址：非 HTTP 系目标补齐默认端口，Elasticsearch 补齐默认路径。
     *
     * <p>HTTP 系目标刻意不补端口——{@code https://host/path} 补成 {@code https://host:443/path}
     * 会改变 Host 头，也让看板上的地址变得难读；它们的有效端口由
     * {@link MonitoredApplication#port()} 在需要时算出来。</p>
     */
    private static URI normalizeUri(HealthCheckProperties.Target target, TargetType type, URI uri) {
        int port = uri.getPort();
        if (port < 0 && !type.isHttpBased()) {
            port = type.getDefaultPort();
            if (port < 0) {
                throw new IllegalStateException(
                        "目标 [%s] 的地址 [%s] 必须写明端口".formatted(target.name(), target.url()));
            }
        }
        String path = uri.getPath();
        if (type == TargetType.ELASTICSEARCH && (path == null || path.isBlank() || "/".equals(path))) {
            path = ELASTICSEARCH_DEFAULT_PATH;
        }
        if (port == uri.getPort() && java.util.Objects.equals(path, uri.getPath())) {
            return uri;
        }
        try {
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), port, path, uri.getQuery(),
                    uri.getFragment());
        }
        catch (URISyntaxException ex) {
            throw new IllegalStateException(
                    "目标 [%s] 的地址 [%s] 规范化失败：%s".formatted(target.name(), target.url(), ex.getMessage()), ex);
        }
    }

    private static HttpMethod parseMethod(HealthCheckProperties.Target target, TargetType type) {
        HttpMethod method = HttpMethod.valueOf(target.method().trim().toUpperCase(Locale.ROOT));
        if (!SUPPORTED_METHODS.contains(method)) {
            throw new IllegalStateException(
                    "目标 [%s] 配置的请求方法 [%s] 不受支持，只允许 GET / HEAD".formatted(target.name(), method));
        }
        if (!type.isHttpBased() && !HttpMethod.GET.equals(method)) {
            throw new IllegalStateException("目标 [%s] 是 %s 类型，不接受 method 配置".formatted(target.name(),
                    type.getCode()));
        }
        return method;
    }

    /** HTTP 系目标才生成 Basic 头；Redis / MySQL 的账号口令由各自探针按协议使用。 */
    private static String basicAuthHeader(HealthCheckProperties.Target target, TargetType type) {
        if (!type.isHttpBased() || target.username() == null || target.username().isBlank()) {
            return null;
        }
        String password = (target.password() == null) ? "" : target.password();
        String token = target.username() + ':' + password;
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> expectedDatabases(HealthCheckProperties.Target target, TargetType type) {
        List<String> names = target.expectedDatabases().stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
        if (!names.isEmpty() && type != TargetType.MYSQL) {
            throw new IllegalStateException("目标 [%s] 是 %s 类型，expected-databases 只对 mysql 目标生效"
                    .formatted(target.name(), type.getCode()));
        }
        return names;
    }

    private static List<String> normalizeTags(Collection<String> tags) {
        return tags.stream().map(String::trim).filter(tag -> !tag.isEmpty()).distinct().toList();
    }

    private static String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Duration firstNonNull(Duration value, Duration fallback) {
        return (value != null) ? value : fallback;
    }

    /** 单条目未显式配置时沿用 probe 的全局默认值。 */
    private static boolean firstNonNull(Boolean value, boolean fallback) {
        return (value != null) ? value : fallback;
    }
}
