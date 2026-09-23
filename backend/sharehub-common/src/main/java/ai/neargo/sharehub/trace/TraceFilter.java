package ai.neargo.sharehub.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 入站链路：采纳请求里的 {@code traceparent}，没有就生成一个，并写进 MDC。
 *
 * <p>排在**最前面**（{@link Ordered#HIGHEST_PRECEDENCE}）：认证失败、参数非法这些
 * 最需要排查的请求，也必须带着链路标识进日志。若排在认证之后，恰恰是出问题的请求没有链路。
 *
 * <p>{@code finally} 里必须清理：Servlet 容器的线程是复用的，不清就会把上一个请求的
 * 链路标识带给下一个 —— 那比没有链路更糟，因为它是**错的**而不是空的。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String tp = TraceContext.adopt(req.getHeader(TraceContext.HEADER));
        // 回写响应：前端与 nginx 日志据此和后端对齐
        resp.setHeader(TraceContext.HEADER, tp);
        try {
            chain.doFilter(req, resp);
        } finally {
            TraceContext.clear();
        }
    }
}
