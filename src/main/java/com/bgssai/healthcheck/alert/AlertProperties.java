package com.bgssai.healthcheck.alert;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * 告警的全部可配置项，前缀 {@code bgssai.healthcheck.alert}。
 *
 * <p>刻意不并进 {@link com.bgssai.healthcheck.config.HealthCheckProperties}：告警是巡检之外
 * 的一层，配置、通道与状态机都自成一体，单独一份配置类能让整个 {@code alert} 包不依赖巡检
 * 侧的任何配置结构。</p>
 */
@Validated
@ConfigurationProperties(prefix = "bgssai.healthcheck.alert")
public record AlertProperties(

        /*
         * 是否启用告警。默认开启：不配任何通道时也有日志通道在工作，
         * 而日志会被 bgssai-logs 采走，等于零配置就有一条可追溯的告警记录。
         */
        @DefaultValue("true") boolean enabled,

        /*
         * 连续多少次探测为异常才告警。
         *
         * 默认 2 而不是 1：跨境链路偶发一次超时是常态，单次失败就报会把告警变成噪音，
         * 而按 30s 的巡检间隔，2 次也只把发现时间推迟半分钟。
         */
        @Min(1) @Max(100) @DefaultValue("2") int failureThreshold,

        /* 目标恢复正常时是否补一条恢复通知。关掉之后只有「出事」没有「好了」，不推荐。 */
        @DefaultValue("true") boolean recoveryNotice,

        /*
         * 持续异常时的重复提醒间隔，0 表示只在状态变化时通知一次。
         *
         * 默认 0：一条永远修不好的告警每隔几分钟响一次，最后的结果是所有人都不看告警了。
         */
        @DefaultValue("0s") Duration repeatInterval,

        /*
         * UNKNOWN 是否计入异常。默认 false —— UNKNOWN 的含义是「说不准」
         * （对端自报了一个不认识的状态词），把说不准当故障报会经常误伤。
         */
        @DefaultValue("false") boolean includeUnknown,

        /* 是否只对 critical=true 的目标告警。默认 false，即所有启用的目标都告警。 */
        @DefaultValue("false") boolean onlyCritical,

        @DefaultValue @Valid Webhook webhook) {

    /**
     * 外发 Webhook 通道。{@code url} 留空即不启用该通道，其余键都不生效。
     */
    public record Webhook(

            /*
             * 接收告警的地址，留空表示不启用。
             *
             * 注意：钉钉 / 企业微信机器人的地址里带 access_token，它等同于一把口令。
             * 本类不会把 url 原样写进日志（见 WebhookAlertNotifier#safeUrl）。
             */
            String url,

            /*
             * 报文格式：
             *   generic   本平台自己的 JSON（字段最全，适合对接自研网关）
             *   wecom     企业微信机器人的 text 消息
             *   dingtalk  钉钉机器人的 text 消息（与 wecom 报文结构相同，分开写是为了配置可读）
             *   feishu    飞书机器人的 text 消息
             */
            @DefaultValue("generic") WebhookFormat format,

            @DefaultValue("3s") Duration connectTimeout,

            @DefaultValue("5s") Duration readTimeout,

            /* 附加请求头，例如自研网关要求的鉴权头。 */
            @DefaultValue Map<String, String> headers) {

        /** 配了地址才算启用。 */
        public boolean isEnabled() {
            return this.url != null && !this.url.isBlank();
        }
    }

    /** Webhook 报文格式。 */
    public enum WebhookFormat {

        /** 本平台自己的 JSON，字段最全。 */
        GENERIC,
        /** 企业微信机器人 text 消息。 */
        WECOM,
        /** 钉钉机器人 text 消息。 */
        DINGTALK,
        /** 飞书机器人 text 消息。 */
        FEISHU
    }
}
