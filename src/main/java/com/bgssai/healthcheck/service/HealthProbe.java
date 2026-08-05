package com.bgssai.healthcheck.service;

import com.bgssai.healthcheck.domain.ProbeResult;
import com.bgssai.healthcheck.domain.TargetType;

import java.util.Set;

/**
 * 一种协议的探针。
 *
 * <p>新增一种被监控目标就是新增一个实现类，由 Spring 整体注入给
 * {@link HealthProbeDispatcher}，别处不做任何 if-else 分发。</p>
 *
 * <p>实现约定（三条，缺一不可）：</p>
 * <ol>
 *   <li><strong>不抛异常</strong>：任何失败都要转换成一个 DOWN 的 {@link ProbeResult}，
 *       并把原因写进 {@code message}。探针抛异常会让整轮巡检的编排逻辑去兜底，
 *       那时已经丢掉了失败现场。</li>
 *   <li><strong>有界耗时</strong>：连接与读取都必须带超时，取自
 *       {@link MonitoredApplication#connectTimeout()} / {@link MonitoredApplication#readTimeout()}。</li>
 *   <li><strong>明细已脱敏</strong>：写进 {@code ProbeDetail} 的内容一律先过
 *       {@link ProbeSecrets}，因为明细会原样出现在看板、接口与报告里。</li>
 * </ol>
 */
public interface HealthProbe {

    /** 本探针负责的目标类型，同一类型只能有一个探针。 */
    Set<TargetType> supportedTypes();

    /** 探测一个目标，无论成功失败都要返回结果。 */
    ProbeResult probe(MonitoredApplication app);
}
