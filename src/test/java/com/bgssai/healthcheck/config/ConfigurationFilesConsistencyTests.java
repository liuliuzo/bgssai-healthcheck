package com.bgssai.healthcheck.config;

import com.bgssai.healthcheck.config.HealthCheckProperties.Detail;
import com.bgssai.healthcheck.config.HealthCheckProperties.Mysql;
import com.bgssai.healthcheck.config.HealthCheckProperties.Probe;
import com.bgssai.healthcheck.config.HealthCheckProperties.Redis;
import com.bgssai.healthcheck.config.HealthCheckProperties.Target;
import com.bgssai.healthcheck.domain.TargetType;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四份 {@code .properties} 里 {@code bgssai.healthcheck.applications} 的一致性守护。
 *
 * <p>为什么需要这组用例：Spring Boot 绑定集合时<strong>不跨 property source 合并</strong>，
 * 只从优先级最高的那个源整份取。所以主配置与三份 profile 文件必须各自写一份完整的清单，
 * 谁也不能只写差异——代价是同一批目标在四个文件里各有一份，改了一处忘了另一处不会有任何
 * 编译期或启动期报错，只会在切换 profile 后悄悄探测到过时的地址。这组用例把那个「悄悄」
 * 变成构建失败。</p>
 *
 * <p>清单分两段：前 19 条是平台自身与 9 个产品的 18 个后端，四份文件完全相同；后面是
 * 中间件与数据库，<strong>生产两地各一套（6 条）、开发只有一套（3 条）</strong>，因此
 * prod 家族与 dev 家族的条数本就不同，不能要求四份文件的 id 清单全等——能要求的是
 * 主配置 = prod 档、local 档 = dev 档。</p>
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

    /** 平台自身 + 9 个产品 × 管理端 / 用户端，顺序即看板上的顺序，四份文件相同。 */
    private static final List<String> EXPECTED_APP_IDS = List.of(
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

    /** 生产的中间件与数据库：境内 / 境外各一套。 */
    private static final List<String> EXPECTED_PROD_INFRA_IDS = List.of(
            "mysql-cn", "mysql-global",
            "redis-cn", "redis-global",
            "elasticsearch-cn", "elasticsearch-global");

    /** 开发只有境外一套中间件与数据库。 */
    private static final List<String> EXPECTED_DEV_INFRA_IDS = List.of(
            "mysql-dev", "redis-dev", "elasticsearch-dev");

    /** 与中间件目标配套的阈值与明细开关，四份文件必须给同样的值。 */
    private static final List<String> SHARED_TUNING_KEYS = List.of(
            "bgssai.healthcheck.detail.enabled",
            "bgssai.healthcheck.detail.max-body-chars",
            "bgssai.healthcheck.detail.keep-last-failure",
            "bgssai.healthcheck.redis.memory-warn-percent",
            "bgssai.healthcheck.mysql.connection-warn-percent",
            "bgssai.healthcheck.mysql.query-timeout-seconds");

    private static final Probe PROBE = new Probe(Duration.ofSeconds(3), Duration.ofSeconds(5), false, 65536, false);

    private static final Detail DETAIL = new Detail(true, 16384, true);

    private static final Redis REDIS = new Redis(90);

    private static final Mysql MYSQL = new Mysql(90, 3);

    @Test
    @DisplayName("主配置自带整份基线：19 条应用 + 6 条中间件与数据库，不指定 profile 也不会是空看板")
    void mainConfigCarriesTheProductionBaseline() {
        List<Target> baseline = bindFile(MAIN);

        assertThat(baseline).as("主配置里的 applications 为空，看板会显示「还没有配置被监控的应用」")
                .hasSize(expectedIds(MAIN).size());
        assertThat(baseline).extracting(Target::id).containsExactlyElementsOf(expectedIds(MAIN));

        Target platform = baseline.getFirst();
        assertThat(platform.url()).isEqualTo("http://127.0.0.1:8080/actuator/health");
        assertThat(platform.critical()).as("平台自身若标成 critical，报过一次 DOWN 就再也回不到 UP").isFalse();

        assertThat(baseline.subList(1, EXPECTED_APP_IDS.size())).allSatisfy(target -> {
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
    @DisplayName("每份文件都写了自洽的整份清单：下标连续、必填字段齐全、能被真正解析")
    void everyFileRepeatsTheWholeList() {
        for (String file : List.of(MAIN, PROD, DEV, LOCAL)) {
            List<String> expected = expectedIds(file);
            Map<Integer, Map<String, String>> byIndex = groupByIndex(applicationKeys(file));

            assertThat(byIndex.keySet())
                    .as("%s 的 applications 下标必须连续，缺一个绑定器就会报 unbound", file)
                    .containsExactlyElementsOf(java.util.stream.IntStream.range(0, expected.size()).boxed().toList());

            byIndex.forEach((index, entry) -> assertThat(entry)
                    .as("%s 的 applications[%d] 缺少必填字段", file, index)
                    .containsKeys("id", "name", "group", "url"));

            List<Target> targets = bindFile(file);
            assertThat(targets).extracting(Target::id)
                    .as("%s 的目标清单与预期对不上", file)
                    .containsExactlyElementsOf(expected);

            // 走一遍真正的解析逻辑：地址非法、类型与 scheme 不匹配、缺端口、id 重复都会在这里抛出
            List<MonitoredApplication> resolved = registryOf(targets).findAll();
            assertThat(resolved).extracting(MonitoredApplication::id).containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("四份文件都覆盖了数据库、Redis 与 Elasticsearch，且凭据与证书开关配齐")
    void everyProfileCoversDatabaseAndMiddleware() {
        for (String file : List.of(MAIN, PROD, DEV, LOCAL)) {
            List<MonitoredApplication> resolved = registryOf(bindFile(file)).findAll();
            Map<TargetType, Integer> counts = registryOf(bindFile(file)).countByType();

            assertThat(counts.getOrDefault(TargetType.MYSQL, 0))
                    .as("%s 没有数据库巡检目标——应用的就绪探针只由 critical 组件决定结论，"
                            + "库挂了要等应用自己报出来，中间可能已经积压了很久", file)
                    .isGreaterThanOrEqualTo(1);
            assertThat(counts.getOrDefault(TargetType.REDIS, 0)).as("%s 没有 Redis 巡检目标", file)
                    .isGreaterThanOrEqualTo(1);
            assertThat(counts.getOrDefault(TargetType.ELASTICSEARCH, 0)).as("%s 没有 Elasticsearch 巡检目标", file)
                    .isGreaterThanOrEqualTo(1);

            resolved.stream().filter(app -> app.type() == TargetType.MYSQL).forEach(app -> {
                assertThat(app.username()).as("%s 的 %s 缺账号，JDBC 连不上", file, app.id()).isNotBlank();
                assertThat(app.password()).as("%s 的 %s 缺口令", file, app.id()).isNotBlank();
                assertThat(app.port()).isEqualTo(3306);
                assertThat(app.expectedDatabases())
                        .as("%s 的 %s 没写 expected-databases，库被 clean 掉也发现不了", file, app.id())
                        .isNotEmpty();
                assertThat(app.critical()).as("中间件挂了不该把本平台自己拖成 DOWN").isFalse();
            });

            resolved.stream().filter(app -> app.type() == TargetType.REDIS).forEach(app -> {
                assertThat(app.password()).as("%s 的 %s 缺口令，AUTH 会被拒", file, app.id()).isNotBlank();
                assertThat(app.port()).isEqualTo(6379);
                assertThat(app.critical()).isFalse();
            });

            resolved.stream().filter(app -> app.type() == TargetType.ELASTICSEARCH).forEach(app -> {
                assertThat(app.uri().getPath()).isEqualTo("/_cluster/health");
                assertThat(app.port()).isEqualTo(9200);
                assertThat(app.authorization()).as("%s 的 %s 缺 Basic 认证头", file, app.id()).isNotBlank();
                assertThat(app.skipTlsVerification())
                        .as("三台 Elasticsearch 都是自签证书，不放开校验会被误判为 DOWN")
                        .isTrue();
                assertThat(app.critical()).isFalse();
            });
        }
    }

    @Test
    @DisplayName("明细开关与中间件阈值写在主配置里，profile 不各写一份")
    void tuningKeysLiveInTheMainConfigOnly() {
        Map<String, String> main = allKeys(MAIN);
        for (String key : SHARED_TUNING_KEYS) {
            assertThat(main.get(key)).as("主配置缺少 %s", key).isNotNull();
        }
        // 这些是标量键，Spring Boot 跨 property source 逐键合并，不像 applications 那样整份取代，
        // 因此只在主配置写一份即可——profile 文件复制一份反而多一处会漂移的地方。
        // 真要按环境调阈值时，profile 里覆盖单个键是允许的，但值必须与主配置有意不同才有意义。
        for (String file : List.of(PROD, DEV, LOCAL)) {
            Map<String, String> profile = allKeys(file);
            Map<String, String> redundant = new LinkedHashMap<>();
            SHARED_TUNING_KEYS.stream()
                    .filter(key -> java.util.Objects.equals(profile.get(key), main.get(key)))
                    .forEach(key -> redundant.put(key, profile.get(key)));
            assertThat(redundant)
                    .as("%s 里这些键与主配置取值相同，属多余的副本，删掉即可继承主配置", file)
                    .isEmpty();
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
        // 境外 Redis 其实也是 dev / prod 同机，但两档的条目 id 不同（redis-dev / redis-global），
        // 因此不会落进这个按 id 比对的集合里。
        Set<String> sharedAddresses = baseline.entrySet().stream()
                .filter(entry -> entry.getValue().equals(dev.get(entry.getKey())))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        assertThat(sharedAddresses).containsExactly("healthcheck-platform", "saas-admin", "saas-user");
    }

    /** 该文件应当有的完整 id 清单：19 条应用 + 本环境的中间件与数据库。 */
    private static List<String> expectedIds(String file) {
        List<String> infra = List.of(MAIN, PROD).contains(file) ? EXPECTED_PROD_INFRA_IDS : EXPECTED_DEV_INFRA_IDS;
        return Stream.concat(EXPECTED_APP_IDS.stream(), infra.stream()).toList();
    }

    /**
     * 两份文件里对不上的 applications 键。断言写成「差异集为空」而不是「两个 Map 相等」，
     * 是为了让失败信息只列出真正漂移的那几行，而不是把两百多个键整片打印出来。
     */
    private static Map<String, String> differences(String left, String right) {
        Map<String, String> leftKeys = applicationKeys(left);
        Map<String, String> rightKeys = applicationKeys(right);
        Map<String, String> diff = new TreeMap<>();
        Stream.concat(leftKeys.keySet().stream(), rightKeys.keySet().stream())
                .distinct()
                .filter(key -> !java.util.Objects.equals(leftKeys.get(key), rightKeys.get(key)))
                .forEach(key -> diff.put(key,
                        "%s=%s / %s=%s".formatted(left, leftKeys.get(key), right, rightKeys.get(key))));
        return diff;
    }

    /** 读一份配置文件里 {@code bgssai.healthcheck.applications} 开头的全部键值。 */
    private static Map<String, String> applicationKeys(String file) {
        Map<String, String> result = new TreeMap<>();
        allKeys(file).forEach((key, value) -> {
            if (key.startsWith(PREFIX)) {
                result.put(key, value);
            }
        });
        return result;
    }

    /** 读一份配置文件的全部键值。 */
    private static Map<String, String> allKeys(String file) {
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
        properties.stringPropertyNames().forEach(key -> result.put(key, properties.getProperty(key)));
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
                Duration.ofSeconds(3), 16, 60, 10, PROBE, DETAIL, REDIS, MYSQL, targets);
        return new ApplicationRegistry(properties);
    }
}
