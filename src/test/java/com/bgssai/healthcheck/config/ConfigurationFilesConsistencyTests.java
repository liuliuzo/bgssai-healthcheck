package com.bgssai.healthcheck.config;

import com.bgssai.healthcheck.config.HealthCheckProperties.Probe;
import com.bgssai.healthcheck.config.HealthCheckProperties.Target;
import com.bgssai.healthcheck.service.ApplicationRegistry;
import com.bgssai.healthcheck.service.MonitoredApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四份 {@code .properties} 里 {@code bgssai.healthcheck.applications} 的一致性守护。
 *
 * <p>为什么需要这组用例：Spring Boot 绑定集合时<strong>不跨 property source 合并</strong>，
 * 只从优先级最高的那个源整份取。所以主配置与三份 profile 文件必须各自写一份完整的 19 条，
 * 谁也不能只写差异——代价是同一批应用在四个文件里各有一份，改了一处忘了另一处不会有任何
 * 编译期或启动期报错，只会在切换 profile 后悄悄探测到过时的地址。这组用例把那个「悄悄」
 * 变成构建失败。</p>
 *
 * <p>用例只读配置、不发任何网络请求：绑定与校验走 {@link Binder} 和 {@link ApplicationRegistry}，
 * 不启动 Spring 应用上下文，因此也不会触发定时巡检去连生产地址。</p>
 */
class ConfigurationFilesConsistencyTests {

    private static final String MAIN = "application.properties";

    private static final String PROD = "application-prod.properties";

    private static final String DEV = "application-dev.properties";

    private static final String LOCAL = "application-local.properties";

    private static final String PREFIX = "bgssai.healthcheck.applications";

    private static final Pattern INDEXED_KEY = Pattern.compile("^\\Q" + PREFIX + "\\E\\[(\\d+)]\\.(.+)$");

    /** 平台自身 + 9 个产品 × 管理端 / 用户端，顺序即看板上的顺序。 */
    private static final List<String> EXPECTED_IDS = List.of(
            "healthcheck-platform",
            "blog-admin", "blog-user",
            "builder-admin", "builder-user",
            "geo-cn-admin", "geo-cn-user",
            "geo-global-admin", "geo-global-user",
            "marklens-admin", "marklens-user",
            "publish-admin", "publish-user",
            "saas-admin", "saas-user",
            "voiceunion-admin", "voiceunion-user",
            "vpn-admin", "vpn-user");

    private static final Probe PROBE = new Probe(Duration.ofSeconds(3), Duration.ofSeconds(5), false, 65536, false);

    @Test
    @DisplayName("主配置自带整份基线：19 条生产目标，不指定 profile 也不会是空看板")
    void mainConfigCarriesTheProductionBaseline() {
        List<Target> baseline = bindFile(MAIN);

        assertThat(baseline).as("主配置里的 applications 为空，看板会显示「还没有配置被监控的应用」")
                .hasSize(EXPECTED_IDS.size());
        assertThat(baseline).extracting(Target::id).containsExactlyElementsOf(EXPECTED_IDS);

        Target platform = baseline.getFirst();
        assertThat(platform.url()).isEqualTo("http://127.0.0.1:8080/actuator/health");
        assertThat(platform.critical()).as("平台自身若标成 critical，报过一次 DOWN 就再也回不到 UP").isFalse();

        assertThat(baseline.subList(1, baseline.size())).allSatisfy(target -> {
            assertThat(target.url()).startsWith("https://").endsWith("/bgssai/health/readiness");
            assertThat(target.enabled()).isTrue();
            assertThat(target.critical()).as("下游应用挂了不该把本平台自己拖成 DOWN").isFalse();
            assertThat(target.skipTlsVerification())
                    .as("按 IP 直连时证书主机名对不上，不放开校验会被整片误判为 DOWN")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("主配置与 prod 档逐条一致，local 档与 dev 档逐条一致")
    void duplicatedListsStayInSync() {
        assertThat(differences(MAIN, PROD))
                .as("主配置的基线就是生产目标：改了一处必须同步另一处")
                .isEmpty();
        assertThat(differences(LOCAL, DEV))
                .as("local 档只是在笔记本上跑，探测目标与 dev 完全相同")
                .isEmpty();
    }

    @Test
    @DisplayName("每份文件都写了自洽的整份 19 条：下标 0..18 连续、必填字段齐全")
    void everyFileRepeatsTheWholeList() {
        for (String file : List.of(MAIN, PROD, DEV, LOCAL)) {
            Map<Integer, Map<String, String>> byIndex = groupByIndex(applicationKeys(file));

            assertThat(byIndex.keySet())
                    .as("%s 的 applications 下标必须是连续的 0..18，缺一个绑定器就会报 unbound", file)
                    .containsExactlyElementsOf(java.util.stream.IntStream.range(0, EXPECTED_IDS.size()).boxed().toList());

            byIndex.forEach((index, entry) -> assertThat(entry)
                    .as("%s 的 applications[%d] 缺少必填字段", file, index)
                    .containsKeys("id", "name", "group", "url"));

            List<Target> targets = bindFile(file);
            assertThat(targets).extracting(Target::id)
                    .as("%s 的应用清单与其它文件对不上", file)
                    .containsExactlyElementsOf(EXPECTED_IDS);

            // 走一遍真正的解析逻辑：地址非法、方法不支持、id 重复都会在这里抛出
            List<MonitoredApplication> resolved = registryOf(targets).findAll();
            assertThat(resolved).extracting(MonitoredApplication::id).containsExactlyElementsOf(EXPECTED_IDS);
        }
    }

    @Test
    @DisplayName("profile 覆盖生效：prod 拿到基线地址，dev 拿到开发地址")
    void profileOverridesReplaceTheWholeBaseline() {
        Map<String, String> baseline = urlsById(bindEnvironment());
        Map<String, String> prod = urlsById(bindEnvironment("prod"));
        Map<String, String> dev = urlsById(bindEnvironment("dev"));
        Map<String, String> local = urlsById(bindEnvironment("local"));

        assertThat(prod).as("prod 档与主配置基线同为生产目标").isEqualTo(baseline);
        assertThat(local).as("local 档与 dev 档同为开发目标").isEqualTo(dev);
        assertThat(dev).as("dev 档若与基线全等，说明生产地址被误抄进了开发档").isNotEqualTo(baseline);

        // 开发与生产地址相同的只有三条，且都是有据可查的：平台自身探本机，
        // SaaS 两端 dev / prod 共用同一台腾讯云机器（腾讯云只有公网 IP，且两档同机）。
        Set<String> sharedAddresses = baseline.entrySet().stream()
                .filter(entry -> entry.getValue().equals(dev.get(entry.getKey())))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        assertThat(sharedAddresses).containsExactly("healthcheck-platform", "saas-admin", "saas-user");
    }

    /**
     * 两份文件里对不上的 applications 键。断言写成「差异集为空」而不是「两个 Map 相等」，
     * 是为了让失败信息只列出真正漂移的那几行，而不是把 190 个键整片打印出来。
     */
    private static Map<String, String> differences(String left, String right) {
        Map<String, String> leftKeys = applicationKeys(left);
        Map<String, String> rightKeys = applicationKeys(right);
        Map<String, String> diff = new TreeMap<>();
        java.util.stream.Stream.concat(leftKeys.keySet().stream(), rightKeys.keySet().stream())
                .distinct()
                .filter(key -> !java.util.Objects.equals(leftKeys.get(key), rightKeys.get(key)))
                .forEach(key -> diff.put(key,
                        "%s=%s / %s=%s".formatted(left, leftKeys.get(key), right, rightKeys.get(key))));
        return diff;
    }

    /** 读一份配置文件里 {@code bgssai.healthcheck.applications} 开头的全部键值。 */
    private static Map<String, String> applicationKeys(String file) {
        Properties properties = new Properties();
        try (InputStream in = ConfigurationFilesConsistencyTests.class.getResourceAsStream("/" + file)) {
            assertThat(in).as("类路径上找不到 %s", file).isNotNull();
            // Properties.load 按 ISO-8859-1 解码并还原 \\uXXXX 转义，与 Spring Boot 读 .properties 的口径一致
            properties.load(in);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        Map<String, String> result = new TreeMap<>();
        properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith(PREFIX))
                .forEach(key -> result.put(key, properties.getProperty(key)));
        return result;
    }

    /** 把扁平的 {@code applications[n].xxx} 键按下标归拢。 */
    private static Map<Integer, Map<String, String>> groupByIndex(Map<String, String> keys) {
        Map<Integer, Map<String, String>> byIndex = new TreeMap<>();
        keys.forEach((key, value) -> {
            Matcher matcher = INDEXED_KEY.matcher(key);
            if (matcher.matches()) {
                byIndex.computeIfAbsent(Integer.parseInt(matcher.group(1)), index -> new LinkedHashMap<>())
                        .put(matcher.group(2), value);
            }
        });
        return byIndex;
    }

    /** 只绑定单个文件，用来验证「这份文件自己是不是完整的一份」。 */
    private static List<Target> bindFile(String file) {
        MockEnvironment environment = new MockEnvironment();
        applicationKeys(file).forEach(environment::withProperty);
        return bind(environment);
    }

    /**
     * 按 Spring Boot 真实的 ConfigData 加载顺序装配环境（主配置 + profile 文件、profile 优先），
     * 用来验证覆盖语义本身，而不只是各文件的内容。
     */
    private static List<Target> bindEnvironment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment, new DefaultResourceLoader(),
                new DefaultBootstrapContext(), profiles);
        return bind(environment);
    }

    private static List<Target> bind(ConfigurableEnvironment environment) {
        return Binder.get(environment)
                .bind(PREFIX, Bindable.listOf(Target.class))
                .orElse(List.of());
    }

    private static Map<String, String> urlsById(List<Target> targets) {
        Map<String, String> urls = new LinkedHashMap<>();
        targets.forEach(target -> urls.put(target.id(), target.url()));
        return urls;
    }

    private static ApplicationRegistry registryOf(List<Target> targets) {
        HealthCheckProperties properties = new HealthCheckProperties(false, Duration.ofSeconds(30),
                Duration.ofSeconds(3), 16, 60, 10, PROBE, targets);
        return new ApplicationRegistry(properties);
    }
}
