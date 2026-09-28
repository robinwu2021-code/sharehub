package ai.neargo.opskit.svc.web;

import ai.neargo.opskit.platform.core.idem.IdempotencyStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 写请求（POST / PUT / PATCH / DELETE）的公共前置（21/06 §一 · §二）：
 *
 * <ol>
 *   <li>先把请求体读进内存 —— 身份解析要看体里的 {@code onBehalfOf}，幂等要对体算哈希，控制器还要再读一遍；</li>
 *   <li>带 {@code Idempotency-Key} 时缓存响应：{@link CallerResolver} 认出调用方后占位（同键同内容重放首次结果、
 *       同键不同内容 409），这里在响应写出前按状态码决定记下（{@link IdempotencyStore#complete}）还是放弃占位允许重试。</li>
 * </ol>
 * 为什么占位不在这里做：幂等键按「调用方」隔离（应用 / 成员），调用方要等令牌解析完才知道。
 */
public class WriteRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WriteRequestFilter.class);

    static final String BODY = WriteRequestFilter.class.getName() + ".body";
    /** 由 {@link CallerResolver} 在 begin 返回 Proceed 后写入：值为 {caller, key}。 */
    static final String IDEM_CLAIM = WriteRequestFilter.class.getName() + ".idem";
    static final String IDEM_HEADER = "Idempotency-Key";
    static final int MAX_BODY = 1024 * 1024;
    /** 目录全量同步一批可能有上万成员：放宽到 16 MiB（仍建议宿主分批，见 21/06 §三）。 */
    static final int MAX_SYNC_BODY = 16 * 1024 * 1024;
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final IdempotencyStore idempotency;

    public WriteRequestFilter(IdempotencyStore idempotency) {
        this.idempotency = idempotency;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return !WRITES.contains(req.getMethod()) || !(p.startsWith("/v1/") || p.startsWith("/api/opskit/v1/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        int max = req.getRequestURI().endsWith("/directory/sync") ? MAX_SYNC_BODY : MAX_BODY;
        byte[] body = req.getInputStream().readNBytes(max + 1);
        if (body.length > max) {
            res.setStatus(413);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"opskit.bad_request\",\"message\":\"request body too large\"}");
            return;
        }
        CachedBodyRequest wrapped = new CachedBodyRequest(req, body);
        wrapped.setAttribute(BODY, body);
        if (req.getHeader(IDEM_HEADER) == null) {
            chain.doFilter(wrapped, res);
            return;
        }
        ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(res);
        boolean finished = false;
        try {
            chain.doFilter(wrapped, cached);
            finished = true;
        } finally {
            String[] claim = (String[]) wrapped.getAttribute(IDEM_CLAIM);
            if (claim != null) {
                int status = cached.getStatus();
                if (finished && remember(status)) {
                    byte[] out = cached.getContentAsByteArray();
                    idempotency.complete(claim[0], claim[1], status, out.length == 0 ? null : new String(out, StandardCharsets.UTF_8));
                } else {
                    idempotency.abandon(claim[0], claim[1]);
                    log.debug("幂等占位已放弃，允许同键重试 caller={} key={} status={}", claim[0], claim[1], status);
                }
            }
            cached.copyBodyToResponse();
        }
    }

    /** 确定的结果才记：成功与业务性 4xx。401（凭证问题）、409（并发 / 状态变了）、429、5xx 都允许同键重试。 */
    static boolean remember(int status) {
        return status >= 200 && status < 500 && status != 401 && status != 409 && status != 429;
    }

    static String requestHash(HttpServletRequest req, byte[] body) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update((req.getMethod() + " " + req.getRequestURI() + "\n").getBytes(StandardCharsets.UTF_8));
            d.update(body);
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest req, byte[] body) {
            super(req);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener l) {
                    throw new UnsupportedOperationException("synchronous only");
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
