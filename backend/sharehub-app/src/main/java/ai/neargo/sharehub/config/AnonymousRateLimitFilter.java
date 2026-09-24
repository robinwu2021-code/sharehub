package ai.neargo.sharehub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 面向陌生人的匿名入口的单 IP 限流（ADR-030 §三 的第二道闸）。
 *
 * <p><b>2026-09-23 更名</b>（原 {@code ApplyRateLimitFilter}）：覆盖面已不止入驻申请 ——
 * 代理端登录发码 {@code POST /api/auth/otp} 同样匿名、同样是发短信的写入口，
 * 挂进来才不会留一个没人限速的刷短信口子。名字里的 Apply 会让人以为登录发码不在管辖内。
 *
 * <p>当前覆盖：入驻自助三个端点 + 登录发码。它们是全站
 * <b>仅有的面向陌生人的入口</b>，也因此是唯一的滥用面。三道闸：
 * OTP（证明持号）· <b>本过滤器</b>（限速）· {@code active_key} 生成列（同号至多一张在途）。
 *
 * <p><b>只拦匿名调用</b>：带了 STAFF 令牌的是运营代建，走员工自己的配额，不该被这里挡。
 *
 * <p>内存实现，与 {@code OtpService} 同一取舍：进程重启丢失、多实例不共享。
 * 挡得住脚本刷号，挡不住分布式刷 —— 真正的防护要等网关层限流。
 * <b>这一点必须说清楚，免得有人以为已经防住了。</b>
 */
@Component
public class AnonymousRateLimitFilter extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final int maxPerMinute;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public AnonymousRateLimitFilter(@Value("${sharehub.security.apply-rate-per-minute:10}") int maxPerMinute) {
        this.maxPerMinute = maxPerMinute;
    }

    private static final class Counter {
        volatile Instant windowStart = Instant.now();
        final AtomicInteger hits = new AtomicInteger();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        // 发码端点尤其要拦：OtpService 自己有重发间隔，但那是**按手机号**的 ——
        // 换个号就能接着发，单 IP 限流才拦得住批量刷号
        return !(p.equals("/api/agent/apply") || p.equals("/api/agent/apply/mine")
                || p.equals("/api/agent/apply/otp")
                // 登录发码：与入驻发码同性质 —— OtpGate 的重发间隔是**按手机号**的，
                // 换个号就能接着发，只有单 IP 限流拦得住批量刷短信
                || p.equals("/api/auth/otp"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        // 带令牌的是代建，不走这道闸
        String auth = req.getHeader("Authorization");
        if (auth != null && !auth.isBlank()) {
            chain.doFilter(req, resp);
            return;
        }

        if (!allow(clientIp(req))) {
            resp.setStatus(429);
            resp.setContentType("application/json;charset=UTF-8");
            // message 不是 msg —— 见 SecurityConfig 同处注释
            resp.getWriter().write("{\"code\":429,\"message\":\"操作过于频繁，请稍后再试\",\"data\":null}");
            return;
        }
        chain.doFilter(req, resp);
    }

    private boolean allow(String ip) {
        Counter c = counters.computeIfAbsent(ip, k -> new Counter());
        Instant now = Instant.now();
        synchronized (c) {
            if (Duration.between(c.windowStart, now).compareTo(WINDOW) >= 0) {
                c.windowStart = now;
                c.hits.set(0);
            }
            return c.hits.incrementAndGet() <= maxPerMinute;
        }
    }

    /**
     * 取调用方 IP。
     *
     * <p>⚠️ {@code X-Forwarded-For} 是<b>客户端可伪造</b>的：直连时随便填一个就能绕开限流。
     * 只在确实有反向代理时才该信它 —— 本项目线上是 nginx 转发，nginx 会覆写最后一跳。
     * 取<b>最后一个</b>而不是第一个，正是因为前面的几段可能是客户端自己塞的。
     */
    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            String[] parts = fwd.split(",");
            return parts[parts.length - 1].trim();
        }
        return req.getRemoteAddr();
    }
}
