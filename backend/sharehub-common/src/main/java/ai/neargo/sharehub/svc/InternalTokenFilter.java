package ai.neargo.sharehub.svc;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@code /internal/**} 的服务凭证闸门 —— 与 {@link InternalClient} 是同一条链路的两端。
 *
 * <h2>三条</h2>
 * <ol>
 *   <li><b>没配密钥 = 全部拒绝</b>，不是「没配就不校验」。后者的表现是内部口对任何人开放，
 *       而且**没有任何症状** —— 与 B1 的 fail-closed 同一个道理；</li>
 *   <li><b>常量时间比较</b>：`equals` 会在第一个不同的字节上返回，理论上可用于逐字节猜测密钥。
 *       这条链路在内网，风险不高，但正确写法不比错误写法难；</li>
 *   <li><b>不认用户身份</b>：通过后授予的是 {@code ROLE_INTERNAL}，不是某个人。
 *       内部调用代表「服务 A 要做这件事」，把它伪装成某个用户会让审计失真。</li>
 * </ol>
 *
 * <h2>只管真正跨进程的路径，不是所有 /internal/**</h2>
 * {@code /internal/} 这个前缀今天混着两类东西：
 * <ul>
 *   <li><b>真正的跨进程调用</b>（{@code /internal/events/**}）—— 服务之间投递事件，本过滤器管这些；</li>
 *   <li><b>历史遗留的业务端点</b>（{@code /internal/gw/**} · {@code /internal/user/**} ·
 *       {@code /internal/trade/**} 等）—— ops-web **今天就在用员工令牌调它们**。
 *       按 v4/04 §三这批要改名到 {@code /api/platform/...}，在那之前维持员工令牌。</li>
 * </ul>
 * 一刀切要求服务凭证会立刻打断前者之外的所有调用方。前缀因此可配
 * （{@code sharehub.services.internal-prefixes}），迁移一批就加一批。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);

    private final String token;
    private final List<String> prefixes;

    public InternalTokenFilter(
            @Value("${sharehub.services.internal-token:}") String token,
            @Value("${sharehub.services.internal-prefixes:/internal/events/,/internal/ping}") List<String> prefixes) {
        this.token = token == null ? "" : token.trim();
        this.prefixes = prefixes;
    }

    /**
     * 这条路径是否由服务凭证把守。
     *
     * <p>公开是为了能被直接断言 —— 「圈了哪些路径」是本类最容易出事也最该被测住的决定
     * （一刀切 {@code /internal/**} 会立刻打断 ops-web 在用的历史端点）。
     * 让测试去 mock 一个 {@code HttpServletRequest} 只为绕过 {@code protected}，
     * 是把可见性问题伪装成测试问题。
     */
    public boolean appliesTo(String uri) {
        return uri != null && prefixes.stream().anyMatch(uri::startsWith);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !appliesTo(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {

        if (token.isBlank()) {
            // 没配密钥：拒绝，并且**说清楚原因** —— 否则运维会以为是调用方的问题
            log.warn("拒绝内部调用 {}：sharehub.services.internal-token 未配置", req.getRequestURI());
            deny(resp, "内部调用未开通：sharehub.services.internal-token 未配置");
            return;
        }

        String given = req.getHeader(InternalClient.TOKEN_HEADER);
        if (given == null || !constantTimeEquals(given.trim(), token)) {
            deny(resp, "内部凭证无效");
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "internal", null, AuthorityUtils.createAuthorityList("ROLE_INTERNAL")));
        try {
            chain.doFilter(req, resp);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void deny(HttpServletResponse resp, String msg) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"code\":10401,\"message\":\"" + msg + "\",\"data\":null}");
    }

    /** 长度不同也走完全程，避免用长度差异旁推。 */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i % Math.max(y.length, 1)];
        }
        return diff == 0;
    }
}
