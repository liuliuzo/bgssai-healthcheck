package com.bgssai.healthcheck.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId 链路追踪过滤器（Standards §6.1.7），与产品线其余 9 个仓的同名过滤器同一口径。
 *
 * <p>把 traceId 注入 MDC，logback pattern 以 {@code %X{traceId}} 自动输出到每一行日志；
 * 业务代码不得再手工把 traceId 拼进日志文案。这是既有基础设施，不用于打接口出入口日志，
 * 因此不与 §6.1.2「不为接口日志新建集中式拦截器 / 过滤器」冲突。</p>
 *
 * <p>本平台的日志有两类来源，只有前一类能拿到 traceId：</p>
 * <ul>
 *   <li><strong>请求线程</strong>（看板页、{@code /api/**}、{@code /actuator/**}）——经过本过滤器，
 *       每行日志都带 traceId，可按 traceId 把一次页面刷新涉及的日志串起来。</li>
 *   <li><strong>后台巡检线程</strong>（{@code HealthCheckScheduler} 定时轮 + 探针的并发子任务）——
 *       不经过任何请求，MDC 为空，pattern 里的 {@code :-} 缺省值使其渲染为空串。这是符合预期的：
 *       巡检日志本就按目标 id 而非 traceId 检索。刻意不给巡检线程编一个假 traceId——那只会让
 *       「有 traceId 就代表有对应请求」这个排障前提失真。</li>
 * </ul>
 *
 * <p>traceId 同时回写到响应头 {@code X-Trace-Id}：用户在看板上截到一次异常，凭响应头即可直接
 * 定位到日志行，不必按时间戳翻。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_KEY = "traceId";

    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须在 finally 里清：Tomcat 线程会被复用，残留的 traceId 会串到下一个请求上；
            // 本平台还开了 spring.threads.virtual.enabled，虚拟线程虽不复用，但 MDC 的
            // InheritableThreadLocal 语义仍会把残值带进探针子任务，一样要清干净。
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
