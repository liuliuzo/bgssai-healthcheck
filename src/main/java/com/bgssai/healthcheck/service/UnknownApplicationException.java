package com.bgssai.healthcheck.service;

/**
 * 请求了一个未配置的应用 id。
 */
public class UnknownApplicationException extends RuntimeException {

    private final String applicationId;

    public UnknownApplicationException(String applicationId) {
        super("未找到 id 为 [%s] 的应用".formatted(applicationId));
        this.applicationId = applicationId;
    }

    public String getApplicationId() {
        return this.applicationId;
    }
}
