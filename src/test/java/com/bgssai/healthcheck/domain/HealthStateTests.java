package com.bgssai.healthcheck.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class HealthStateTests {

    @ParameterizedTest
    @CsvSource({
            "UP, UP",
            "up, UP",
            "'  Up  ', UP",
            "OK, UP",
            "DOWN, DOWN",
            "error, DOWN",
            "OUT_OF_SERVICE, DEGRADED",
            "out-of-service, DEGRADED",
            "WARN, DEGRADED",
            "SOMETHING_ELSE, UNKNOWN"
    })
    @DisplayName("把各种写法的状态字符串映射到统一枚举")
    void mapsActuatorStatuses(String raw, HealthState expected) {
        assertThat(HealthState.fromActuator(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    @DisplayName("空状态视为未知")
    void mapsBlankToUnknown(String raw) {
        assertThat(HealthState.fromActuator(raw)).isEqualTo(HealthState.UNKNOWN);
    }

    @Test
    @DisplayName("聚合时取更严重的状态")
    void picksWorseState() {
        assertThat(HealthState.UP.worseOf(HealthState.DOWN)).isEqualTo(HealthState.DOWN);
        assertThat(HealthState.DOWN.worseOf(HealthState.DEGRADED)).isEqualTo(HealthState.DOWN);
        assertThat(HealthState.DEGRADED.worseOf(HealthState.UNKNOWN)).isEqualTo(HealthState.DEGRADED);
        assertThat(HealthState.UP.worseOf(HealthState.UNKNOWN)).isEqualTo(HealthState.UNKNOWN);
        assertThat(HealthState.UP.worseOf(null)).isEqualTo(HealthState.UP);
    }

    @Test
    @DisplayName("枚举同时提供中文标签和 CSS 用的小写编码")
    void exposesLabelAndCode() {
        assertThat(HealthState.DEGRADED.getLabel()).isEqualTo("降级");
        assertThat(HealthState.DEGRADED.getCode()).isEqualTo("degraded");
        assertThat(HealthState.UP.isHealthy()).isTrue();
        assertThat(HealthState.UNKNOWN.isHealthy()).isFalse();
    }
}
