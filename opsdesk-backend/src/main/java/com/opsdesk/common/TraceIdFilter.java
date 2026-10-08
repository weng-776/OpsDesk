package com.opsdesk.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId 与访问日志（规格基线 §23.6 可观测性）
 *
 * <p>职责：
 * <ol>
 *   <li>为每个请求生成（或沿用上游传入的）{@code traceId}，写入 MDC → 随日志输出</li>
 *   <li>回写响应头 {@code X-Trace-Id}，便于前端/网关把问题反馈对应到日志</li>
 *   <li>按统一格式打访问日志：{@code traceId | userId | method | uri | cost | result}</li>
 * </ol>
 *
 * <p><b>MDC 生命周期由本过滤器独占</b>（它是请求链最外层）：
 * 这里负责 put({@link #TRACE_ID}) 与最终 {@code MDC.clear()}；
 * 后续 auth 模块的鉴权拦截器只需 {@code MDC.put(USER_ID, ...)}，<b>不要自己 clear</b>，
 * 否则访问日志里的 userId 会丢。
 *
 * <p>MQ 侧：生产者把 traceId 放进消息头，消费者取出后重新 {@code MDC.put}，保证异步链路可串联。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC key：链路 id */
    public static final String TRACE_ID = "traceId";

    /** MDC key：当前用户 id（由 auth 模块的拦截器写入） */
    public static final String USER_ID = "userId";

    /** 上下游传递 traceId 的请求/响应头 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        }
        finally {
            long cost = System.currentTimeMillis() - start;
            String userId = MDC.get(USER_ID);
            // 统一日志格式：traceId | userId | method | uri | cost | result
            log.info("{} | {} | {} {} | {}ms | {}",
                    traceId,
                    userId == null ? "-" : userId,
                    request.getMethod(),
                    request.getRequestURI(),
                    cost,
                    response.getStatus());
            // MDC 生命周期归本过滤器：一次性清掉 traceId 与 userId
            MDC.clear();
        }
    }
}
