package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.domain.HealthSample;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.HealthStats;
import com.bgssai.healthcheck.domain.ProbeResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内的巡检结果存储：保存每个应用的最近一次结果、历史采样和累计统计。
 *
 * <p>刻意不做持久化——平台重启后重新巡检即可恢复全部状态。</p>
 */
@Component
public class HealthStatusStore {

    private final int historySize;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public HealthStatusStore(HealthCheckProperties properties) {
        this.historySize = properties.historySize();
    }

    /** 记录一次探测结果。 */
    public void record(String applicationId, ProbeResult result) {
        this.entries.computeIfAbsent(applicationId, key -> new Entry()).record(result, this.historySize);
    }

    /** 读取快照；从未巡检过的应用返回空。 */
    public Optional<Snapshot> snapshot(String applicationId) {
        return Optional.ofNullable(this.entries.get(applicationId)).map(Entry::snapshot);
    }

    /** 清空某个应用的历史（例如它被停用时）。 */
    public void clear(String applicationId) {
        this.entries.remove(applicationId);
    }

    /** 某个应用的完整状态快照。 */
    public record Snapshot(ProbeResult latest, HealthStats stats, List<HealthSample> history) {
    }

    /**
     * 单个应用的可变状态。所有读写都在同一把内置锁下完成，写入频率很低（每轮巡检一次），
     * 不值得为它引入更复杂的无锁结构。
     */
    private static final class Entry {

        private final Deque<HealthSample> history = new ArrayDeque<>();

        private ProbeResult latest;

        private int totalChecks;

        private int upChecks;

        private int consecutiveFailures;

        private int respondedChecks;

        private long latencySum;

        private long maxLatency;

        private Instant lastUpAt;

        private Instant lastDownAt;

        synchronized void record(ProbeResult result, int historySize) {
            this.latest = result;
            this.totalChecks++;

            HealthState state = result.state();
            if (state.isHealthy()) {
                this.upChecks++;
                this.consecutiveFailures = 0;
                this.lastUpAt = result.checkedAt();
            }
            else {
                this.consecutiveFailures++;
                if (state == HealthState.DOWN) {
                    this.lastDownAt = result.checkedAt();
                }
            }

            // 只有真正拿到响应的探测才计入耗时统计，否则超时会把平均值带偏
            if (result.httpStatus() != null) {
                this.respondedChecks++;
                this.latencySum += result.latencyMs();
                this.maxLatency = Math.max(this.maxLatency, result.latencyMs());
            }

            this.history.addLast(new HealthSample(result.checkedAt(), state, result.latencyMs()));
            while (this.history.size() > historySize) {
                this.history.removeFirst();
            }
        }

        synchronized Snapshot snapshot() {
            double uptime = (this.totalChecks == 0) ? 0.0d
                    : Math.round(this.upChecks * 10000.0d / this.totalChecks) / 100.0d;
            long avgLatency = (this.respondedChecks == 0) ? 0L : this.latencySum / this.respondedChecks;
            HealthStats stats = new HealthStats(this.totalChecks, this.upChecks, uptime, avgLatency, this.maxLatency,
                    this.consecutiveFailures, this.lastUpAt, this.lastDownAt);
            return new Snapshot(this.latest, stats, List.copyOf(this.history));
        }
    }
}
