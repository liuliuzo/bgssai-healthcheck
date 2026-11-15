package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.alert.AlertService;
import com.bgssai.healthcheck.alert.AlertStatus;
import com.bgssai.healthcheck.config.HealthCheckProperties;
import com.bgssai.healthcheck.domain.AppHealth;
import com.bgssai.healthcheck.domain.HealthSample;
import com.bgssai.healthcheck.domain.HealthState;
import com.bgssai.healthcheck.domain.HealthSummary;
import com.bgssai.healthcheck.domain.ProbeDetail;
import com.bgssai.healthcheck.domain.ProbeResult;
import com.bgssai.healthcheck.domain.TargetType;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 渲染可下载的 Markdown 巡检报告。
 */
@Service
public class HealthReportService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss",
            Locale.CHINA);

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.CHINA);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA);

    private static final int HISTORY_LIMIT = 20;

    private final HealthCheckService healthCheckService;

    private final ApplicationRegistry registry;

    private final HealthCheckProperties properties;

    private final AlertService alertService;

    private final ZoneId zone = ZoneId.systemDefault();

    public HealthReportService(HealthCheckService healthCheckService, ApplicationRegistry registry,
            HealthCheckProperties properties, AlertService alertService) {
        this.healthCheckService = healthCheckService;
        this.registry = registry;
        this.properties = properties;
        this.alertService = alertService;
    }

    public String fileName() {
        return "bgssai-healthcheck-report-" + FILE_STAMP.format(Instant.now().atZone(this.zone)) + ".md";
    }

    public String render(boolean includeRaw) {
        List<AppHealth> all = this.healthCheckService.findAll();
        HealthSummary summary = this.healthCheckService.summary();

        StringBuilder md = new StringBuilder(16384);
        md.append("# BGSSAI 健康巡检报告\n\n");
        renderMetadata(md, summary, all);
        renderSummary(md, summary, all);
        renderAttention(md, all);
        renderInventory(md, all);
        renderPerTarget(md, all, includeRaw);
        renderConfiguration(md);
        return md.toString();
    }

    /* ---------- 一、报告元信息 ---------- */

    private void renderMetadata(StringBuilder md, HealthSummary summary, List<AppHealth> all) {
        md.append("## 一、报告元信息\n\n");
        md.append("| 项目 | 值 |\n| --- | --- |\n");
        row(md, "生成时间", timestamp(summary.generatedAt()));
        row(md, "整体状态", summary.overall().getLabel() + "（" + summary.overall().name() + "）");
        row(md, "目标总数", String.valueOf(all.size()));
        row(md, "最近一轮巡检", timestamp(summary.lastCheckedAt()));
        row(md, "定时巡检", this.properties.scheduled() ? "已启用" : "已关闭");
        row(md, "巡检间隔", describe(this.properties.refreshInterval()));
        row(md, "首轮延迟", describe(this.properties.initialDelay()));
        row(md, "最大并发探测数", String.valueOf(this.properties.concurrency()));
        row(md, "历史采样点数", String.valueOf(this.properties.historySize()));
        row(md, "原始明细保留", this.properties.detail().enabled()
                ? "开启，单条上限 " + this.properties.detail().maxBodyChars() + " 字符"
                : "关闭");
        row(md, "保留最近一次失败明细", this.properties.detail().keepLastFailure() ? "是" : "否");
        md.append('\n');
    }

    /* ---------- 二、状态汇总 ---------- */

    private void renderSummary(StringBuilder md, HealthSummary summary, List<AppHealth> all) {
        md.append("## 二、状态汇总\n\n### 按状态\n\n");
        md.append("| 状态 | 数量 | 占比 |\n| --- | --- | --- |\n");
        int total = Math.max(1, all.size());
        stateRow(md, "正常", summary.up(), total);
        stateRow(md, "降级", summary.degraded(), total);
        stateRow(md, "异常", summary.down(), total);
        stateRow(md, "未知", summary.unknown(), total);
        stateRow(md, "已停用", summary.disabled(), total);

        md.append("\n### 按目标类型\n\n");
        md.append("| 类型 | 总数 | 正常 | 降级 | 异常 | 未知 |\n| --- | --- | --- | --- | --- | --- |\n");
        Map<TargetType, List<AppHealth>> byType = new EnumMap<>(TargetType.class);
        all.forEach(app -> byType.computeIfAbsent(app.type(), key -> new ArrayList<>()).add(app));
        byType.forEach((type, apps) -> md.append("| ")
                .append(type.getLabel())
                .append(" | ")
                .append(apps.size())
                .append(" | ")
                .append(count(apps, HealthState.UP))
                .append(" | ")
                .append(count(apps, HealthState.DEGRADED))
                .append(" | ")
                .append(count(apps, HealthState.DOWN))
                .append(" | ")
                .append(count(apps, HealthState.UNKNOWN))
                .append(" |\n"));
        md.append('\n');
    }

    /* ---------- 三、需要处理的目标 ---------- */

    private void renderAttention(StringBuilder md, List<AppHealth> all) {
        md.append("## 三、需要处理的目标\n\n");
        List<AppHealth> attention = all.stream()
                .filter(app -> app.state() == HealthState.DOWN || app.state() == HealthState.DEGRADED)
                .sorted(Comparator.comparingInt((AppHealth app) -> -app.state().getSeverity())
                        .thenComparing(AppHealth::group)
                        .thenComparing(AppHealth::name))
                .toList();
        if (attention.isEmpty()) {
            md.append("无\n\n");
            return;
        }
        md.append("| 目标 | id | 类型 | 分组 | 状态 | 地址 | 连续失败 | 说明 |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (AppHealth app : attention) {
            md.append("| ").append(cell(app.name()))
                    .append(" | ").append(cell(app.id()))
                    .append(" | ").append(cell(app.type().getLabel()))
                    .append(" | ").append(cell(app.group()))
                    .append(" | ").append(app.state().getLabel())
                    .append(" | ").append(cell(app.url()))
                    .append(" | ").append(app.stats().consecutiveFailures())
                    .append(" | ").append(cell(app.message()))
                    .append(" |\n");
        }
        md.append('\n');
    }

    /* ---------- 四、全部目标一览 ---------- */

    private void renderInventory(StringBuilder md, List<AppHealth> all) {
        md.append("## 四、全部目标一览\n\n");
        Map<String, List<AppHealth>> grouped = new LinkedHashMap<>();
        all.forEach(app -> grouped.computeIfAbsent(app.group(), key -> new ArrayList<>()).add(app));
        grouped.forEach((group, apps) -> {
            md.append("### ").append(group).append('\n').append('\n');
            md.append("| 目标 | id | 类型 | 状态 | HTTP | 耗时 | 可用率 | 最近检查 | 地址 |\n");
            md.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
            for (AppHealth app : apps) {
                md.append("| ").append(cell(app.name()))
                        .append(" | ").append(cell(app.id()))
                        .append(" | ").append(cell(app.type().getLabel()))
                        .append(" | ").append(app.state().getLabel())
                        .append(" | ").append(app.httpStatus() == null ? "-" : app.httpStatus())
                        .append(" | ").append(latency(app.latencyMs()))
                        .append(" | ").append(uptime(app))
                        .append(" | ").append(timestamp(app.checkedAt()))
                        .append(" | ").append(cell(app.url()))
                        .append(" |\n");
            }
            md.append('\n');
        });
    }

    /* ---------- 五、逐目标明细 ---------- */

    private void renderPerTarget(StringBuilder md, List<AppHealth> all, boolean includeRaw) {
        md.append("## 五、逐目标明细\n\n");
        int index = 0;
        for (AppHealth app : all) {
            index++;
            md.append("### ").append(index).append(". ").append(app.name())
                    .append("（").append(app.id()).append("）\n\n");

            md.append("| 基本信息 | 值 |\n| --- | --- |\n");
            row(md, "类型", app.type().getLabel());
            row(md, "分组", app.group());
            row(md, "地址", app.url());
            row(md, "标签", app.tags().isEmpty() ? "-" : String.join(", ", app.tags()));
            row(md, "备注", app.description());
            row(md, "关键目标", app.critical() ? "是" : "否");
            row(md, "已启用", app.enabled() ? "是" : "否");

            md.append("\n| 运行状况 | 值 |\n| --- | --- |\n");
            row(md, "当前状态", app.state().getLabel() + "（" + app.state().name() + "）");
            row(md, "HTTP 状态码", app.httpStatus() == null ? "-" : String.valueOf(app.httpStatus()));
            row(md, "响应耗时", latency(app.latencyMs()));
            row(md, "最近检查", timestamp(app.checkedAt()));
            row(md, "说明", app.message());
            row(md, "累计探测次数", String.valueOf(app.stats().totalChecks()));
            row(md, "其中正常", String.valueOf(app.stats().upChecks()));
            row(md, "可用率", uptime(app));
            row(md, "平均耗时", latency(app.stats().avgLatencyMs()));
            row(md, "最大耗时", latency(app.stats().maxLatencyMs()));
            row(md, "连续失败次数", String.valueOf(app.stats().consecutiveFailures()));
            row(md, "最近正常时刻", timestamp(app.stats().lastUpAt()));
            row(md, "最近异常时刻", timestamp(app.stats().lastDownAt()));
            md.append('\n');

            if (!app.components().isEmpty()) {
                md.append("| 子组件 | 状态 | 明细 |\n| --- | --- | --- |\n");
                app.components().forEach(component -> md.append("| ").append(cell(component.name()))
                        .append(" | ").append(component.state().getLabel())
                        .append(" | ").append(cell(flatten(component.details())))
                        .append(" |\n"));
                md.append('\n');
            }

            if (includeRaw && app.detail() != null) {
                renderDetail(md, "#### 最近一次原始应答", app.detail(), null);
            }
            if (includeRaw && app.hasRecoveredFailure()) {
                ProbeResult failure = app.lastFailure();
                renderDetail(md, "#### 最近一次失败的原始应答", failure.detail(),
                        "失败时刻 " + timestamp(failure.checkedAt()) + "，当时判定为 " + failure.state().getLabel()
                                + (failure.message() == null ? "" : "，说明：" + failure.message()));
            }

            if (!app.history().isEmpty()) {
                List<HealthSample> history = new ArrayList<>(app.history());
                java.util.Collections.reverse(history);
                md.append("最近 ").append(Math.min(HISTORY_LIMIT, history.size())).append(" 次采样：");
                List<String> samples = new ArrayList<>();
                history.stream().limit(HISTORY_LIMIT).forEach(sample -> samples.add(
                        clock(sample.at()) + " " + sample.state().getLabel() + "(" + latency(sample.latencyMs())
                                + ")"));
                md.append(String.join(" | ", samples)).append("\n\n");
            }
        }
    }

    private void renderDetail(StringBuilder md, String heading, ProbeDetail detail, String note) {
        md.append(heading).append("\n\n");
        if (note != null) {
            md.append(note).append("\n\n");
        }
        if (detail == null) {
            md.append("（未保留明细）\n\n");
            return;
        }
        md.append("| 明细 | 值 |\n| --- | --- |\n");
        row(md, "协议", detail.protocol());
        row(md, "请求", detail.request());
        row(md, "应答首行", detail.statusLine());
        if (detail.error() != null) {
            row(md, "错误", detail.error());
        }
        md.append('\n');

        if (detail.hasHeaders()) {
            md.append("| 响应头 | 值 |\n| --- | --- |\n");
            detail.responseHeaders().forEach(header -> md.append("| ").append(cell(header.name()))
                    .append(" | ").append(cell(header.value())).append(" |\n"));
            md.append('\n');
        }

        if (detail.hasBody()) {
            String body = detail.body();
            String trimmed = body.stripLeading();
            String language = (trimmed.startsWith("{") || trimmed.startsWith("[")) ? "json" : "text";
            // 正文里若本来就有三个反引号，用四个才不会把代码块提前收掉
            String fence = body.contains("```") ? "````" : "```";
            md.append(fence).append(language).append('\n').append(body);
            if (!body.endsWith("\n")) {
                md.append('\n');
            }
            md.append(fence).append("\n\n");
            if (detail.truncated()) {
                md.append("（正文已截断，完整长度 ")
                        .append(detail.bodyBytes())
                        .append(" 字节）\n\n");
            }
        }
    }

    /* ---------- 六、当前巡检配置 ---------- */

    private void renderConfiguration(StringBuilder md) {
        HealthCheckProperties.Probe probe = this.properties.probe();
        md.append("## 六、当前巡检配置\n\n### 探测参数\n\n");
        md.append("| 参数 | 值 |\n| --- | --- |\n");
        row(md, "连接超时", describe(probe.connectTimeout()));
        row(md, "读取超时", describe(probe.readTimeout()));
        row(md, "跟随 3xx 跳转", probe.followRedirects() ? "是" : "否");
        row(md, "响应体读取上限", probe.maxBodyBytes() + " 字节");
        row(md, "全局跳过证书校验", probe.skipTlsVerification() ? "是" : "否");
        row(md, "Redis 内存告警阈值", this.properties.redis().memoryWarnPercent() + "%");
        row(md, "MySQL 连接数告警阈值", this.properties.mysql().connectionWarnPercent() + "%");
        row(md, "MySQL 查询超时", this.properties.mysql().queryTimeoutSeconds() + " 秒");

        renderAlertConfiguration(md);

        md.append("\n### 目标清单\n\n");
        md.append("| id | 类型 | 地址 | 启用 | 关键 | 连接超时 | 读取超时 | 跳过证书校验 | 期望状态码 | 期望数据库 |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (MonitoredApplication app : this.registry.findAll()) {
            md.append("| ").append(cell(app.id()))
                    .append(" | ").append(cell(app.type().getLabel()))
                    .append(" | ").append(cell(app.uri().toString()))
                    .append(" | ").append(app.enabled() ? "是" : "否")
                    .append(" | ").append(app.critical() ? "是" : "否")
                    .append(" | ").append(describe(app.connectTimeout()))
                    .append(" | ").append(describe(app.readTimeout()))
                    .append(" | ").append(app.skipTlsVerification() ? "是" : "否")
                    .append(" | ").append(app.expectedStatuses().isEmpty() ? "任意 2xx"
                            : app.expectedStatuses().stream().sorted().map(String::valueOf)
                                    .collect(java.util.stream.Collectors.joining(", ")))
                    .append(" | ").append(app.expectedDatabases().isEmpty() ? "-"
                            : String.join(", ", app.expectedDatabases()))
                    .append(" |\n");
        }
        md.append('\n');
    }

    private void renderAlertConfiguration(StringBuilder md) {
        AlertStatus alert = this.alertService.status();
        md.append("\n### 告警\n\n");
        md.append("| 参数 | 值 |\n| --- | --- |\n");
        row(md, "启用", alert.enabled() ? "是" : "否");
        row(md, "连续失败阈值", String.valueOf(alert.failureThreshold()));
        row(md, "重复提醒间隔", alert.repeatInterval().isZero() ? "不重复"
                : describe(alert.repeatInterval()));
        row(md, "只对关键目标告警", alert.onlyCritical() ? "是" : "否");
        row(md, "启用的通道", alert.channels().isEmpty() ? "无"
                : String.join(", ", alert.channels()));

        if (alert.firing().isEmpty()) {
            md.append("\n无进行中的告警\n\n");
            return;
        }
        md.append("\n#### 进行中的告警\n\n");
        md.append("| 目标 | 分组 | 状态 | 首次异常 | 最近通知 | 已通知次数 |\n");
        md.append("| --- | --- | --- | --- | --- | --- |\n");
        for (AlertStatus.Firing firing : alert.firing()) {
            md.append("| ").append(cell(firing.applicationId()))
                    .append(" | ").append(cell(firing.group()))
                    .append(" | ").append(firing.state().getLabel())
                    .append(" | ").append(timestamp(firing.since()))
                    .append(" | ").append(timestamp(firing.lastNotifiedAt()))
                    .append(" | ").append(firing.notifications())
                    .append(" |\n");
        }
        md.append('\n');
    }

    /* ---------- 格式化辅助 ---------- */

    private static void row(StringBuilder md, String name, String value) {
        md.append("| ").append(name).append(" | ").append(cell(value)).append(" |\n");
    }

    private static void stateRow(StringBuilder md, String name, int value, int total) {
        md.append("| ").append(name).append(" | ").append(value).append(" | ").append(percent(value, total))
                .append(" |\n");
    }

    /** 表格单元格里的竖线会切断列，换行会切断行，两者都要先中和掉。 */
    private static String cell(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    private static String flatten(Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        details.forEach((key, value) -> parts.add(key + "=" + value));
        return String.join("; ", parts);
    }

    private static long count(List<AppHealth> apps, HealthState state) {
        return apps.stream().filter(app -> app.state() == state).count();
    }

    private static String percent(int value, int total) {
        return "%.1f%%".formatted(value * 100.0d / total);
    }

    private static String uptime(AppHealth app) {
        return (app.stats().totalChecks() == 0) ? "-" : "%.2f%%".formatted(app.stats().uptimePercent());
    }

    private static String latency(long millis) {
        if (millis <= 0L) {
            return "-";
        }
        return (millis < 1000L) ? millis + " ms" : "%.2f s".formatted(millis / 1000.0d);
    }

    private static String describe(Duration duration) {
        if (duration == null) {
            return "-";
        }
        return (duration.toMillis() % 1000L == 0L) ? duration.toSeconds() + " 秒" : duration.toMillis() + " 毫秒";
    }

    private String timestamp(Instant instant) {
        return (instant == null) ? "-" : TIMESTAMP.format(instant.atZone(this.zone));
    }

    private String clock(Instant instant) {
        return (instant == null) ? "-" : CLOCK.format(instant.atZone(this.zone));
    }
}
