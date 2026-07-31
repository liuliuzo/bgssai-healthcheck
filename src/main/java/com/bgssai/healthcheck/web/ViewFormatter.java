package com.bgssai.healthcheck.web;

import com.bgssai.healthcheck.domain.AppHealth;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
     * 拼出卡片的搜索关键字串，供前端做客户端筛选。
     *
     * <p>放在这里而不是 {@code AppHealth} 上，是为了不让这个纯页面用的字段
     * 出现在 REST 接口的返回体里。</p>
     */
    public String searchKey(AppHealth app) {
        StringBuilder sb = new StringBuilder(app.name()).append(' ')
                .append(app.group())
                .append(' ')
                .append(app.url());
        app.tags().forEach(tag -> sb.append(' ').append(tag));
        if (app.description() != null) {
            sb.append(' ').append(app.description());
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
