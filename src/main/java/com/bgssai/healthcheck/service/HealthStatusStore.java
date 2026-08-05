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
 * 进程内的巡检结果存储：保存每个目标的最近一次结果、最近一次失败、历史采样和累计统计。
 *
 * <p>刻意不做持久化——平台重启后重新巡检即可恢复全部状态。</p>
 */
@Component
public class HealthStatusStore {

    private final int historySize;

    private final boolean keepLastFailure;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public HealthStatusStore(HealthCheckProperties properties) {
        this.historySize = properties.historySize();
        this.keepLastFailure = properties.detail().keepLastFailure();
    }

    /** 记录一次探测结果。 */
    public void record(String applicationId, ProbeResult result) {
        this.entries.computeIfAbsent(applicationId, key -> new Entry())
                .record(result, this.historySize, this.keepLastFailure);
    }

    /** 读取快照；从未巡检过的目标返回空。 */
    public Optional<Snapshot> snapshot(String applicationId) {
        return Optional.ofNullable(this.entries.get(applicationId)).map(Entry::snapshot);
    }

    /** 清空某个目标的历史（例如它被停用时）。 */
    public void clear(String applicationId) {
        this.entries.remove(applicationId);
    }

    /**
     * 某个目标的完整状态快照。
     *
     * @param lastFailure 最近一次非正常的探测结果，从未失败时为 {@code null}
     */
    public record Snapshot(ProbeResult latest, ProbeResult lastFailure, HealthStats stats, List<HealthSample> history) {
    }

    /**
     * 单个目标的可变状态。所有读写都在同一把内置锁下完成，写入频率很低（每轮巡检一次），
     * 不值得为它引入更复杂的无锁结构。
     */
    private static final class Entry {

        private final Deque<HealthSample> history = new ArrayDeque<>();

        private ProbeResult latest;

        private ProbeResult lastFailure;

        private int totalChecks;

        private int upChecks;

        private int consecutiveFailures;

        private int respondedChecks;

        private long latencySum;

        private long maxLatency;

        private Instant lastUpAt;

        private Instant lastDownAt;

        synchronized void record(ProbeResult result, int historySize, boolean keepLastFailure) {
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
                // 保留失败现场：目标恢复后 latest 就变成一次成功的探测，
                // 那时想知道刚才究竟错在哪里，只能靠这一份留档。
                if (keepLastFailure && state != HealthState.UNKNOWN) {
                    this.lastFailure = result;
                }
            }

            // 只有真正拿到应答的探测才计入耗时统计，否则超时会把平均值带偏。
            // HTTP 系以「有状态码」为准（503 也是应答）；Redis / MySQL / TCP 没有状态码，
            // 以「结论不是 DOWN / UNKNOWN」为准——它们只有连上并拿到应答才可能判 UP / DEGRADED。
            boolean responded = (result.httpStatus() != null)
                    || (state != HealthState.DOWN && state != HealthState.UNKNOWN);
            if (responded) {
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
            return new Snapshot(this.latest, this.lastFailure, stats, List.copyOf(this.history));
        }
    }
}
