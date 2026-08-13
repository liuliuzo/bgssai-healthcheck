package com.bgssai.healthcheck.alert;

import com.bgssai.healthcheck.domain.HealthState;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 告警的当前状况：生效的配置、启用的通道，以及进行中的故障。
 *
 * <p>看板给的是「此刻每个目标是什么状态」，这里给的是「已经通知出去、还没恢复的有哪些」。
 * 两者会不一致，而且那种不一致恰恰是要看的：目标已经红了但不在这张表里，说明它还在抖动
 * 窗口内；在这张表里却已经绿了，说明恢复通知还没发出去。</p>
 *
 * @param repeatInterval 重复提醒间隔，{@code PT0S} 表示只在状态变化时通知
 * @param channels       当前启用的通道名；为空表示告警产生了也无处可发
 * @param firing         进行中且已通知过的故障，按发生先后排序
 */
public record AlertStatus(
        boolean enabled,
        int failureThreshold,
        Duration repeatInterval,
        boolean onlyCritical,
        List<String> channels,
        List<Firing> firing) {

    /**
     * 一条进行中的告警。
     *
     * @param since          首次探测到异常的时刻
     * @param lastNotifiedAt 最近一次发出通知的时刻
     * @param notifications  就这次故障已发出的通知条数
     */
    public record Firing(
            String applicationId,
            String applicationName,
            String group,
            boolean critical,
            HealthState state,
            Instant since,
            Instant lastNotifiedAt,
            int notifications) {
    }
}
