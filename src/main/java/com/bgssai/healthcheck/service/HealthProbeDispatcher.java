package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.domain.ProbeDetail;
import com.bgssai.healthcheck.domain.ProbeResult;
import com.bgssai.healthcheck.domain.TargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按目标类型把探测请求分派给对应的 {@link HealthProbe}，并统一裁剪明细。
 *
 * <p>装配期就要求每种 {@link TargetType} 都恰好有一个探针：少一个是「配置里能写、
 * 运行时探不了」，多一个是「两份实现谁生效看注入顺序」，两种都在启动时直接失败，
 * 不留到巡检时才暴露。</p>
 */
@Component
public class HealthProbeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HealthProbeDispatcher.class);

    private final Map<TargetType, HealthProbe> probes = new EnumMap<>(TargetType.class);

    private final HealthCheckProperties.Detail detailSettings;

    public HealthProbeDispatcher(List<HealthProbe> probes, ApplicationRegistry registry,
            HealthCheckProperties properties) {
        this.detailSettings = properties.detail();
        for (HealthProbe probe : probes) {
            for (TargetType type : probe.supportedTypes()) {
                HealthProbe existing = this.probes.put(type, probe);
                if (existing != null) {
                    throw new IllegalStateException("目标类型 [%s] 有两个探针：%s 与 %s"
                            .formatted(type, existing.getClass().getSimpleName(), probe.getClass().getSimpleName()));
                }
            }
        }
        // 只要求「配置里真的用到的类型」有探针：漏装一个 @Component 会在启动时直接失败，
        // 而不是等到那一轮巡检才对着某个目标报一个看起来像「对端挂了」的 DOWN。
        for (TargetType type : registry.countByType().keySet()) {
            if (!this.probes.containsKey(type)) {
                throw new IllegalStateException("配置里有 %s 类型的目标，但没有对应的探针实现".formatted(type.getCode()));
            }
        }
        log.info("已装配 {} 个探针，覆盖目标类型：{}", probes.size(), this.probes.keySet());
    }

    /** 探测一个目标；探针内部已吞掉异常，这里只兜底意外的 RuntimeException。 */
    public ProbeResult probe(MonitoredApplication app) {
        HealthProbe probe = this.probes.get(app.type());
        try {
            return retainDetail(probe.probe(app));
        }
        catch (RuntimeException ex) {
            // 探针本应自己兜住失败，走到这里说明它有 bug；仍然要返回一个结果，不能让整轮巡检卡住
            log.warn("探针 [{}] 探测目标 [{}] 时抛出异常", probe.getClass().getSimpleName(), app.id(), ex);
            String reason = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : "：" + ex.getMessage());
            return ProbeResult.failure(0L, java.time.Instant.now(), "探针内部错误：" + reason,
                    ProbeDetail.failed(app.type().getLabel(), app.uri().toString(), null, reason));
        }
    }

    /** 按保留策略裁剪明细：关闭时整条丢弃，开启时按字符上限截断。 */
    private ProbeResult retainDetail(ProbeResult result) {
        if (result.detail() == null) {
            return result;
        }
        if (!this.detailSettings.enabled()) {
            return result.withDetail(null);
        }
        return result.withDetail(result.detail().truncateTo(this.detailSettings.maxBodyChars()));
    }
}
