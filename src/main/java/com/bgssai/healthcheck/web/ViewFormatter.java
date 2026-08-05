package com.bgssai.healthcheck.web;

import com.bgssai.healthcheck.domain.AppHealth;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 页面用到的格式化工具，作为模型属性 {@code fmt} 暴露给 Thymeleaf。
 *
 * <p>放在这里而不是塞进领域对象，是为了让 {@code AppHealth} 保持成纯粹的数据结构，
 * REST 接口返回的仍然是原始时间戳。</p>
 */
@Component
public class ViewFormatter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA);

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.CHINA);

    private final ZoneId zone = ZoneId.systemDefault();

    private final JsonMapper jsonMapper;

    public ViewFormatter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /** {@code HH:mm:ss}。 */
    public String time(Instant instant) {
        return (instant == null) ? "—" : TIME.format(instant.atZone(this.zone));
    }

    /** {@code MM-dd HH:mm:ss}。 */
    public String dateTime(Instant instant) {
        return (instant == null) ? "—" : DATE_TIME.format(instant.atZone(this.zone));
    }

    /** 相对时间，例如「12 秒前」。 */
    public String ago(Instant instant) {
        if (instant == null) {
            return "从未";
        }
        Duration elapsed = Duration.between(instant, Instant.now());
        long seconds = Math.max(0L, elapsed.getSeconds());
        if (seconds < 60L) {
            return seconds + " 秒前";
        }
        if (seconds < 3600L) {
            return (seconds / 60L) + " 分钟前";
        }
        if (seconds < 86400L) {
            return (seconds / 3600L) + " 小时前";
        }
        return (seconds / 86400L) + " 天前";
    }

    /** 耗时展示，超过一秒改用秒。 */
    public String latency(long millis) {
        if (millis <= 0L) {
            return "—";
        }
        return (millis < 1000L) ? millis + " ms" : "%.2f s".formatted(millis / 1000.0d);
    }

    /** 可用率，保留两位小数。 */
    public String percent(double value) {
        return "%.2f%%".formatted(value);
    }

    /**
     * 把原始应答里的 JSON 缩进后再展示。
     *
     * <p>放在视图层而不是探针里：明细存的必须是对端原样返回的那一串，一旦在采集时就美化，
     * 「原始响应」这四个字就名不副实了——比如对端返回的是压缩成一行还是本来就带缩进，
     * 排障时偶尔正是线索。解析不了就原样返回，绝不抛异常（对端返回什么都不该让页面挂掉）。</p>
     */
    public String prettyJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.stripLeading();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return raw;
        }
        try {
            JsonNode node = this.jsonMapper.readTree(raw);
            return this.jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        }
        catch (RuntimeException ex) {
            return raw;
        }
    }

    /**
     * 倒序副本，用于「由新到旧」展示历史采样。
     *
     * <p>Thymeleaf 的 {@code #lists} 工具没有 reverse，而 {@code AppHealth.history()} 返回的是
     * 不可变列表，模板里也不该去改它——所以在这里复制一份翻转，原始顺序（由旧到新）留给趋势条用。</p>
     */
    public <T> List<T> reversed(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> copy = new ArrayList<>(values);
        Collections.reverse(copy);
        return copy;
    }

    /** 字节数，用于提示原始应答的体量。 */
    public String bytes(int count) {
        if (count <= 0) {
            return "—";
        }
        if (count < 1024) {
            return count + " B";
        }
        if (count < 1024 * 1024) {
            return "%.1f KB".formatted(count / 1024.0d);
        }
        return "%.1f MB".formatted(count / (1024.0d * 1024.0d));
    }

    /**
     * 拼出卡片的搜索关键字串，供前端做客户端筛选。
     *
     * <p>放在这里而不是 {@code AppHealth} 上，是为了不让这个纯页面用的字段
     * 出现在 REST 接口的返回体里。</p>
     */
    public String searchKey(AppHealth app) {
        StringBuilder sb = new StringBuilder(app.name()).append(' ')
                .append(app.group())
                .append(' ')
                .append(app.url())
                .append(' ')
                .append(app.type().getLabel());
        app.tags().forEach(tag -> sb.append(' ').append(tag));
        if (app.description() != null) {
            sb.append(' ').append(app.description());
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
