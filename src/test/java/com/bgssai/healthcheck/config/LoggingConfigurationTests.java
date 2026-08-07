package com.bgssai.healthcheck.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志配置的一致性守护：本平台的日志口径必须与产品线其余 9 个仓一致。
 *
 * <p>为什么需要这组用例：{@code src/main/resources/log/} 下那两份 logback 配置，只有在某档
 * {@code .properties} 写了 {@code logging.config} 指向它时才会被读。漏掉那一行不会有任何
 * 编译期或启动期报错——应用照常起来、控制台照常有日志，只是文件里空空如也，
 * {@code bgssai-logs} 仓那边采集到的永远是一个不存在的路径。这组用例把那种「静默失效」
 * 变成构建失败。</p>
 *
 * <p>本仓此前正是踩了这个坑：两份 logback 配置是从 geo-cn 抄来的，配置照抄进来了，
 * 指向它们的 {@code logging.config} 却一行没写；抄来的 {@code <logger>} 还留着 geo-cn 的
 * 包名 {@code com.bgssai.geo.cn}，pattern 里的 MDC 位也还是 geo-cn 的 {@code reqId} /
 * {@code uid}，而本仓没有任何地方往这两个键里写值。</p>
 *
 * <p>用例只读打包进 classpath 的资源文件，不启动 Spring 应用上下文，也不写任何日志文件。</p>
 */
class LoggingConfigurationTests {

    /** 三个跑在服务器 / 笔记本上的档，都必须落文件——否则 bgssai-logs 采不到东西。 */
    private static final List<String> FILE_LOGGING_PROFILES =
            List.of("application-dev.properties", "application-local.properties", "application-prod.properties");

    private static final String FILE_CONFIG = "classpath:log/logback-spring_file.xml";

    private static final String STDOUT_CONFIG = "classpath:log/logback-spring_stdout.xml";

    private static final String APPLOG_PATH = "/opt/bgssai/log";

    @Test
    @DisplayName("dev / local / prod 三档都把 logging.config 指向落文件的 logback 配置，并落在采集路径下")
    void runtimeProfilesWireTheFileAppender() {
        for (String file : FILE_LOGGING_PROFILES) {
            Properties properties = read(file);

            assertThat(properties.getProperty("logging.config"))
                    .as("%s 没写 logging.config，log/ 下的 logback 配置根本不会被读，"
                            + "应用会退回 Spring Boot 默认的「只打控制台、不落文件」", file)
                    .isEqualTo(FILE_CONFIG);
            assertThat(properties.getProperty("logging.applog.path"))
                    .as("%s 的 logging.applog.path 必须是 %s——bgssai-logs 仓 inventory 就是按这个路径去拉日志的",
                            file, APPLOG_PATH)
                    .isEqualTo(APPLOG_PATH);
        }
    }

    @Test
    @DisplayName("测试档只打控制台：跑一遍 mvn test 不该在开发机上凭空生成日志文件")
    void testProfileStaysOnStdout() {
        Properties properties = read("application-test.properties");

        assertThat(properties.getProperty("logging.config"))
                .as("测试档若也用 file 档，CI 容器里 /opt/bgssai/log 不可写会直接报错")
                .isEqualTo(STDOUT_CONFIG);
    }

    @Test
    @DisplayName("落盘文件名与 bgssai-logs 仓登记的一致：<spring.application.name>_unstrct.log")
    void rollingFileNameMatchesTheCollectorInventory() {
        assertThat(read("application.properties").getProperty("spring.application.name"))
                .as("应用名变了，日志文件名跟着变，bgssai-logs 仓 inventory 里那行也要同步改")
                .isEqualTo("bgssai-healthcheck");

        String fileConfig = readText("log/logback-spring_file.xml");
        assertThat(fileConfig)
                .as("滚动文件名必须由 application.name 拼出，不能写死")
                .contains("${log.path}/${application.name}_unstrct.log");
        assertThat(fileConfig)
                .as("归档文件名同样按 application.name 拼")
                .contains("${log.path}/${application.name}_unstrct.%d{yyyy-MM-dd}.%i.zip");
    }

    @Test
    @DisplayName("两份 logback 配置的 pattern 逐字一致，且带 traceId 而非抄来的 reqId / uid")
    void bothLogbackConfigsShareTheSamePattern() {
        String expectedPattern =
                "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId:-}] %-5level %logger{100} - %msg%n";

        for (String resource : List.of("log/logback-spring_file.xml", "log/logback-spring_stdout.xml")) {
            String xml = readText(resource);

            assertThat(xml)
                    .as("%s 的 pattern 与约定不符；两份配置必须逐字一致，"
                            + "否则同一条日志在 dev 与 prod 下长相不同，排障要先分辨自己在看哪一档", resource)
                    .contains(expectedPattern);
            assertThat(xml)
                    .as("%s 仍留着从 geo-cn 抄来的 MDC 键——本仓没有任何地方往 reqId / uid 里写值，"
                            + "Standards §6.1.7 规定的键名是 traceId", resource)
                    .doesNotContain("%X{reqId").doesNotContain("%X{uid");
        }
    }

    @Test
    @DisplayName("stdout 档里的 <logger> 指向本仓包名，不是抄来的 com.bgssai.geo.cn")
    void stdoutConfigTargetsThisRepositoryPackage() {
        String xml = readText("log/logback-spring_stdout.xml");

        assertThat(xml)
                .as("<logger> 得指向本仓的根包，否则这条配置对本仓一行日志都不生效")
                .contains("<logger name=\"com.bgssai.healthcheck\"");
        assertThat(xml)
                .as("抄来的 geo-cn 包名必须清掉")
                .doesNotContain("com.bgssai.geo");
    }

    private static Properties read(String resource) {
        Properties properties = new Properties();
        try (InputStream in = openOrFail(resource)) {
            // .properties 按 java.util.Properties 规范以 ISO-8859-1 解码，与运行期口径一致；
            // 这里只读 ASCII 值（路径 / classpath 引用），中文注释不参与断言。
            properties.load(in);
        } catch (IOException ex) {
            throw new UncheckedIOException("读取 " + resource + " 失败", ex);
        }
        return properties;
    }

    private static String readText(String resource) {
        try (InputStream in = openOrFail(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("读取 " + resource + " 失败", ex);
        }
    }

    private static InputStream openOrFail(String resource) {
        InputStream in = LoggingConfigurationTests.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("classpath 上找不到 " + resource);
        }
        return in;
    }
}
