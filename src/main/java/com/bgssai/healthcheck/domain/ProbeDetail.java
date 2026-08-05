package com.bgssai.healthcheck.domain;

import java.util.List;

/**
 * 一次探测的原始明细：发出去的是什么请求，对端原样回了什么。
 *
 * <p>存在的理由很直接——归一化后的 {@link HealthState} 只回答「是不是好的」，
 * 排障需要的却是「对端到底说了什么」。没有这份明细时，一个 DEGRADED 只能看到
 * 「自报状态 OUT_OF_SERVICE」，看不到究竟是哪个组件、什么指标把它拖下去的。</p>
 *
 * <p>凭据在写入前就已脱敏（见 {@code ProbeSecrets}）：请求头里的 {@code Authorization}、
 * Redis 的 {@code AUTH} 参数、JDBC 的口令都不会进入本记录，因此这份明细可以直接
 * 展示在看板上、写进可下载的报告里。</p>
 *
 * @param protocol       协议标识，例如 {@code HTTP} / {@code RESP} / {@code MySQL} / {@code TCP}
 * @param request        请求摘要（多行文本），凭据已脱敏
 * @param statusLine     应答首行，例如 {@code 200 OK} / {@code +PONG} / {@code 连接成功}
 * @param responseHeaders 响应头，非 HTTP 协议为空
 * @param body           原始响应正文，可能已按配置截断
 * @param bodyBytes      读取到的正文字节数（截断前的实际读取量）
 * @param truncated      正文是否被截断
 * @param error          失败原因，成功时为 {@code null}
 */
public record ProbeDetail(
        String protocol,
        String request,
        String statusLine,
        List<Header> responseHeaders,
        String body,
        int bodyBytes,
        boolean truncated,
        String error) {

    public ProbeDetail {
        responseHeaders = (responseHeaders == null) ? List.of() : List.copyOf(responseHeaders);
    }

    /** 是否有可展示的正文。 */
    public boolean hasBody() {
        return this.body != null && !this.body.isBlank();
    }

    /** 是否有可展示的响应头。 */
    public boolean hasHeaders() {
        return !this.responseHeaders.isEmpty();
    }

    /** HTTP 系探针的成功明细。 */
    public static ProbeDetail http(String request, String statusLine, List<Header> headers, String body,
            int bodyBytes, boolean truncated) {
        return new ProbeDetail("HTTP", request, statusLine, headers, body, bodyBytes, truncated, null);
    }

    /** 文本协议（RESP / MySQL / TCP）的明细，正文是探针整理出的应答文本。 */
    public static ProbeDetail text(String protocol, String request, String statusLine, String body) {
        int bytes = (body == null) ? 0 : body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return new ProbeDetail(protocol, request, statusLine, List.of(), body, bytes, false, null);
    }

    /** 探测失败时的明细：请求发了什么、失败在哪一步，正文可能为空。 */
    public static ProbeDetail failed(String protocol, String request, String body, String error) {
        int bytes = (body == null) ? 0 : body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return new ProbeDetail(protocol, request, null, List.of(), body, bytes, false, error);
    }

    /** 按配置的上限截断正文，返回截断后的新记录。 */
    public ProbeDetail truncateTo(int maxChars) {
        if (maxChars <= 0) {
            return new ProbeDetail(this.protocol, this.request, this.statusLine, this.responseHeaders, null,
                    this.bodyBytes, this.bodyBytes > 0, this.error);
        }
        if (this.body == null || this.body.length() <= maxChars) {
            return this;
        }
        return new ProbeDetail(this.protocol, this.request, this.statusLine, this.responseHeaders,
                this.body.substring(0, maxChars), this.bodyBytes, true, this.error);
    }

    /** 一个响应头，值可能已被脱敏。 */
    public record Header(String name, String value) {
    }
}
