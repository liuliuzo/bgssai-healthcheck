package com.bgssai.healthcheck;

import com.bgssai.healthcheck.domain.TargetType;
import com.bgssai.healthcheck.service.ApplicationRegistry;
import com.bgssai.healthcheck.service.HealthProbeDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不指定 profile 直接启动（即 {@code java -jar app.jar}）时，整个上下文能不能装配起来。
 *
 * <p>{@code ConfigurationFilesConsistencyTests} 只读配置、不启动上下文，因此发现不了
 * 「某个探针忘了加 @Component」这类装配问题——那会等到真正部署时才以启动失败的形式出现。
 * 这个用例补上那一段：用真实的主配置起一次上下文，只是把定时巡检关掉，
 * 所以不会向任何生产地址发起探测。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "bgssai.healthcheck.scheduled=false")
class BaselineStartupTests {

    @Autowired
    private ApplicationRegistry registry;

    @Autowired
    private HealthProbeDispatcher dispatcher;

    @Test
    @DisplayName("默认档启动即加载整份基线，且每种目标类型都有探针")
    void defaultProfileBootsWithTheFullBaseline() {
        assertThat(this.dispatcher).as("探针分派器装配失败，说明有探针没被注册").isNotNull();

        assertThat(this.registry.findAll())
                .as("默认档没有加载到目标，看板会显示「还没有配置被监控的目标」")
                .isNotEmpty();

        // 中间件与数据库必须在默认档里就存在：应用的就绪探针只由 critical 组件决定结论，
        // 不直连探就等于这几台机器根本没被监控。
        assertThat(this.registry.countByType())
                .containsKeys(TargetType.HTTP, TargetType.ELASTICSEARCH, TargetType.REDIS, TargetType.MYSQL);
    }
}
