package com.bgssai.healthcheck.domain;

import java.util.List;

/**
 * 看板数据：汇总信息 + 按分组归拢的应用列表。
 */
public record HealthDashboard(HealthSummary summary, List<Group> groups) {

    public HealthDashboard {
        groups = (groups == null) ? List.of() : List.copyOf(groups);
    }

    /** 一个分组内的应用集合。 */
    public record Group(String name, HealthState state, int total, int up, List<AppHealth> apps) {

        public Group {
            apps = (apps == null) ? List.of() : List.copyOf(apps);
        }
    }
}
