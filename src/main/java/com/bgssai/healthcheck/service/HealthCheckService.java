package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.domain.AppHealth;
import com.bgssai.healthcheck.domain.HealthDashboard;
import com.bgssai.healthcheck.domain.HealthSample;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.HealthStats;
import com.bgssai.healthcheck.domain.HealthSummary;
import com.bgssai.healthcheck.domain.ProbeResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 巡检编排：并发探测所有启用的应用，把结果写入 {@link HealthStatusStore}，
 * 并对外提供查询视图。
 */
@Service
public class HealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckService.class);

    private final ApplicationRegistry registry;

    private final HttpHealthProbe probe;

    private final HealthStatusStore store;

    private final int uiRefreshSeconds;

    /** 虚拟线程：探测几乎全是等待 IO，用平台线程池只会白占内存。 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 限制同时在途的探测数，避免一次性打爆被监控方或本机的连接数。 */
    private final Semaphore permits;

    /** 保证同一时刻只有一轮全量巡检在跑。 */
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile Instant lastRefreshAt;

    public HealthCheckService(ApplicationRegistry registry, HttpHealthProbe probe, HealthStatusStore store,
            HealthCheckProperties properties) {
        this.registry = registry;
        this.probe = probe;
        this.store = store;
        this.permits = new Semaphore(properties.concurrency());
        this.uiRefreshSeconds = properties.uiRefreshSeconds();
    }

    /** 全部应用的当前健康视图，顺序与配置一致。 */
    public List<AppHealth> findAll() {
        return this.registry.findAll().stream().map(this::toAppHealth).toList();
    }

    /** 单个应用的当前健康视图。 */
    public AppHealth findById(String applicationId) {
        return this.registry.findById(applicationId)
                .map(this::toAppHealth)
                .orElseThrow(() -> new UnknownApplicationException(applicationId));
    }

    /** 汇总视图。 */
    public HealthSummary summary() {
        return summaryOf(findAll());
    }

    /** 看板视图：汇总 + 按分组归拢。 */
    public HealthDashboard dashboard() {
        List<AppHealth> all = findAll();
        Map<String, List<AppHealth>> grouped = new LinkedHashMap<>();
        all.forEach(app -> grouped.computeIfAbsent(app.group(), key -> new ArrayList<>()).add(app));

        List<HealthDashboard.Group> groups = grouped.entrySet().stream().map(entry -> {
            List<AppHealth> apps = List.copyOf(entry.getValue());
            List<AppHealth> active = apps.stream().filter(AppHealth::enabled).toList();
            HealthState worst = active.stream()
                    .map(AppHealth::state)
                    .reduce(HealthState.UP, HealthState::worseOf);
            int up = (int) active.stream().filter(app -> app.state().isHealthy()).count();
            return new HealthDashboard.Group(entry.getKey(), active.isEmpty() ? HealthState.UNKNOWN : worst,
                    apps.size(), up, apps);
        }).toList();

        return new HealthDashboard(summaryOf(all), groups);
    }

    /**
     * 立刻巡检所有启用的应用并返回最新视图。
     *
     * <p>若已有一轮巡检在进行中，则不重复触发，直接返回当前结果。</p>
     */
    public List<AppHealth> refreshAll() {
        if (!this.refreshLock.tryLock()) {
            log.debug("上一轮巡检尚未结束，本次触发被合并");
            return findAll();
        }
        try {
            List<MonitoredApplication> targets = this.registry.enabled();
            if (targets.isEmpty()) {
                this.lastRefreshAt = Instant.now();
                return findAll();
            }
            long startNanos = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>(targets.size());
            for (MonitoredApplication app : targets) {
                futures.add(this.executor.submit(() -> checkOne(app)));
            }
            for (Future<?> future : futures) {
                awaitQuietly(future);
            }
            this.lastRefreshAt = Instant.now();
            log.debug("本轮巡检完成，{} 个应用，耗时 {} ms", targets.size(),
                    (System.nanoTime() - startNanos) / 1_000_000L);
            return findAll();
        }
        finally {
            this.refreshLock.unlock();
        }
    }

    /** 立刻巡检单个应用。 */
    public AppHealth refresh(String applicationId) {
        MonitoredApplication app = this.registry.findById(applicationId)
                .orElseThrow(() -> new UnknownApplicationException(applicationId));
        if (app.enabled()) {
            checkOne(app);
            this.lastRefreshAt = Instant.now();
        }
        return toAppHealth(app);
    }

    /** 最近一次全量巡检完成的时刻，尚未巡检时为 {@code null}。 */
    public Instant lastRefreshAt() {
        return this.lastRefreshAt;
    }

    private void checkOne(MonitoredApplication app) {
        boolean acquired = false;
        try {
            this.permits.acquire();
            acquired = true;
            this.store.record(app.id(), this.probe.probe(app));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        catch (RuntimeException ex) {
            // 探测本身已经吞掉了异常，这里兜底存储层的意外
            log.warn("记录应用 [{}] 的巡检结果时出错", app.id(), ex);
        }
        finally {
            if (acquired) {
                this.permits.release();
            }
        }
    }

    private static void awaitQuietly(Future<?> future) {
        try {
            future.get();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        catch (Exception ex) {
            log.warn("等待巡检任务结束时出错：{}", ex.toString());
        }
    }

    private AppHealth toAppHealth(MonitoredApplication app) {
        HealthStatusStore.Snapshot snapshot = this.store.snapshot(app.id()).orElse(null);
        ProbeResult latest = (snapshot != null) ? snapshot.latest() : null;
        HealthStats stats = (snapshot != null) ? snapshot.stats() : HealthStats.empty();
        List<HealthSample> history = (snapshot != null) ? snapshot.history() : List.of();

        HealthState state;
        String message;
        Integer httpStatus = null;
        long latency = 0L;
        Instant checkedAt = null;
        List<ProbeResult.ComponentStatus> components = List.of();

        if (!app.enabled()) {
            state = HealthState.UNKNOWN;
            message = "已停用，不参与巡检";
        }
        else if (latest == null) {
            state = HealthState.UNKNOWN;
            message = "尚未巡检";
        }
        else {
            state = latest.state();
            message = latest.message();
            httpStatus = latest.httpStatus();
            latency = latest.latencyMs();
            checkedAt = latest.checkedAt();
            components = latest.components();
        }

        return new AppHealth(app.id(), app.name(), app.group(), app.uri().toString(), app.description(), app.tags(),
                app.critical(), app.enabled(), state, httpStatus, latency, checkedAt, message, components, stats,
                history);
    }

    private HealthSummary summaryOf(List<AppHealth> all) {
        int up = 0;
        int degraded = 0;
        int down = 0;
        int unknown = 0;
        int disabled = 0;
        HealthState overall = HealthState.UP;
        Instant lastChecked = this.lastRefreshAt;

        for (AppHealth app : all) {
            if (!app.enabled()) {
                disabled++;
                continue;
            }
            overall = overall.worseOf(app.state());
            switch (app.state()) {
                case UP -> up++;
                case DEGRADED -> degraded++;
                case DOWN -> down++;
                case UNKNOWN -> unknown++;
            }
        }
        int active = all.size() - disabled;
        return new HealthSummary(active == 0 ? HealthState.UNKNOWN : overall, all.size(), up, degraded, down, unknown,
                disabled, Instant.now(), lastChecked, this.uiRefreshSeconds);
    }

    @PreDestroy
    void shutdown() {
        this.executor.shutdownNow();
    }
}
