package com.bgssai.healthcheck.service;

import java.util.Locale;
import java.util.Set;

/**
 * 明细里的凭据脱敏。
 *
 * <p>原始明细会出现在看板、REST 接口与可下载报告三处，而这三处都没有鉴权
 * （本平台是内网运维视图）。因此凡是能直接拿去登录的东西——Basic 认证头、
 * Redis 的 AUTH 参数、JDBC 口令——都在写入明细之前就被替换掉，而不是等展示时再过滤：
 * 只要它进了 {@code ProbeDetail}，就一定已经脱敏。</p>
 */
public final class ProbeSecrets {

    /** 脱敏后的占位符。 */
    public static final String MASK = "******";

    /** 值本身即凭据、一律不回显的请求 / 响应头。 */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization",
            "proxy-authorization",
            "www-authenticate",
            "proxy-authenticate",
            "cookie",
            "set-cookie",
            "x-api-key",
            "x-auth-token",
            "x-access-token",
            "jwttoken");

    private ProbeSecrets() {
    }

    /** 头名命中敏感清单时返回占位符，否则原样返回。 */
    public static String maskHeader(String name, String value) {
        if (name == null) {
            return value;
        }
        return SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.ROOT)) ? MASK : value;
    }

    public static boolean isSensitiveHeader(String name) {
        return name != null && SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    /** 有值就换成占位符，没值返回 {@code null}——用于口令类字段。 */
    public static String maskValue(String value) {
        return (value == null || value.isEmpty()) ? null : MASK;
    }

    /**
     * 把文本里出现的口令原文替换成占位符。
     *
     * <p>兜底手段：JDBC 驱动与 Redis 服务端的报错信息偶尔会把参数原样带回来，
     * 这些文本要进明细，先过一遍这里。</p>
     */
    public static String scrub(String text, String... secrets) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) {
                result = result.replace(secret, MASK);
            }
        }
        return result;
    }
}
