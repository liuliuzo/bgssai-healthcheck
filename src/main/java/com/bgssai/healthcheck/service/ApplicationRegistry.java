package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 把配置里的应用列表解析成运行期的 {@link MonitoredApplication}，并保证 id 唯一。
 */
@Component
public class ApplicationRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApplicationRegistry.class);

    private static final Set<HttpMethod> SUPPORTED_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD);

    private final List<MonitoredApplication> applications;
    private final Map<String, MonitoredApplication> byId;

    public ApplicationRegistry(HealthCheckProperties properties) {
        this.applications = List.copyOf(resolve(properties));
        Map<String, MonitoredApplication> index = new LinkedHashMap<>();
        this.applications.forEach(app -> index.put(app.id(), app));
        this.byId = Map.copyOf(index);
        log.info("已加载 {} 个被监控应用（其中启用 {} 个）", this.applications.size(), enabled().size());
    }

    /** 配置顺序的全部应用，含已停用的。 */
    public List<MonitoredApplication> findAll() {
        return this.applications;
    }

    /** 参与巡检的应用。 */
    public List<MonitoredApplication> enabled() {
        return this.applications.stream().filter(MonitoredApplication::enabled).toList();
    }

    public Optional<MonitoredApplication> findById(String id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    private static List<MonitoredApplication> resolve(HealthCheckProperties properties) {
        List<HealthCheckProperties.Target> targets = properties.applications();
        HealthCheckProperties.Probe probe = properties.probe();
        List<MonitoredApplication> resolved = new ArrayList<>(targets.size());
        Set<String> usedIds = new java.util.HashSet<>();

        for (int i = 0; i < targets.size(); i++) {
            HealthCheckProperties.Target target = targets.get(i);
            String id = uniqueId(target, i, usedIds);
            resolved.add(new MonitoredApplication(
                    id,
                    target.name().trim(),
                    blankToDefault(target.group(), "未分组"),
                    trimToNull(target.description()),
                    parseUri(target),
                    parseMethod(target),
                    target.enabled(),
                    target.critical(),
                    normalizeTags(target.tags()),
                    target.headers(),
                    basicAuthHeader(target),
                    firstNonNull(target.connectTimeout(), probe.connectTimeout()),
                    firstNonNull(target.readTimeout(), probe.readTimeout()),
                    Set.copyOf(target.expectedStatuses())));
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
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalArgumentException("只支持 http / https，实际为 " + scheme);
            }
            return uri;
        }
        catch (URISyntaxException | IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "应用 [%s] 的健康检查地址 [%s] 不合法：%s".formatted(target.name(), target.url(), ex.getMessage()), ex);
        }
    }

    private static HttpMethod parseMethod(HealthCheckProperties.Target target) {
        HttpMethod method = HttpMethod.valueOf(target.method().trim().toUpperCase(Locale.ROOT));
        if (!SUPPORTED_METHODS.contains(method)) {
            throw new IllegalStateException(
                    "应用 [%s] 配置的请求方法 [%s] 不受支持，只允许 GET / HEAD".formatted(target.name(), method));
        }
        return method;
    }

    private static String basicAuthHeader(HealthCheckProperties.Target target) {
        if (target.username() == null || target.username().isBlank()) {
            return null;
        }
        String password = (target.password() == null) ? "" : target.password();
        String token = target.username() + ':' + password;
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
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
}
