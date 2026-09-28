package ai.neargo.opskit.svc.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 追踪号：接受 W3C {@code traceparent}（取其中的 trace-id），没有就生成；放进 MDC 与响应头 {@code traceId}，错误体也带它。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String traceId = fromTraceparent(req.getHeader("traceparent"));
        if (traceId == null) {
            byte[] b = new byte[16];
            ThreadLocalRandom.current().nextBytes(b);
            traceId = HexFormat.of().formatHex(b);
        }
        MDC.put(MDC_KEY, traceId);
        res.setHeader("traceId", traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove("actor");
            MDC.remove("workspace");
        }
    }

    /** {@code 00-<32 hex trace-id>-<16 hex parent-id>-<flags>}。 */
    static String fromTraceparent(String h) {
        if (h == null) {
            return null;
        }
        String[] p = h.strip().split("-");
        return p.length == 4 && p[1].matches("[0-9a-f]{32}") && !p[1].matches("0{32}") ? p[1] : null;
    }
}
