package ai.neargo.sharehub.portal.platform;

import ai.neargo.sharehub.integration.opskit.OpsKitWebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * OpsKit webhook 接收（M5，方案 23 §3.2）：{@code POST /internal/opskit/webhook}。
 *
 * <h3>三道关</h3>
 * <ol>
 *   <li><b>只本机可达</b>：nginx 不转发 {@code /internal/}，8082 外网不通（与内省端点同一原则）；</li>
 *   <li><b>验签</b>：{@code X-OpsKit-Signature: t=<毫秒>,v1=HMAC-SHA256(secret, t + "." + body)}，±5 分钟
 *       （与 OpsKit {@code SignatureSpec} 同一算法）。密钥没配一律 401 —— fail-closed；</li>
 *   <li><b>幂等</b>：按 OpsKit 的 {@code idemKey} 去重（{@link OpsKitWebhookService}）。</li>
 * </ol>
 *
 * <p>应答<b>不包</b> {@code Result}：OpsKit 只看状态码（2xx = 送达，其余按退避重投）。
 * 业务推进失败回 500 让它重投；「不关心 / 重复」回 200，不重投。
 */
@RestController
@RequestMapping("/internal/opskit")
public class OpsKitWebhookController {

    private static final Logger log = LoggerFactory.getLogger(OpsKitWebhookController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String HEADER = "X-OpsKit-Signature";
    static final long TOLERANCE_MS = 5 * 60 * 1000L;

    private final OpsKitWebhookService service;
    private final String secret;

    public OpsKitWebhookController(OpsKitWebhookService service,
                                   @Value("${sharehub.opskit.webhook-secret:}") String secret) {
        this.service = service;
        this.secret = secret;
    }

    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SuppressWarnings("unchecked")
    public void receive(@RequestBody String body, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!verify(secret, req.getHeader(HEADER), body, System.currentTimeMillis())) {
            // 谁该做什么：核对 OpsKit 的 webhook 订阅密钥与 SHAREHUB_OPSKIT_WEBHOOK_SECRET 是否一致、两台机器时钟是否偏差 > 5 分钟
            log.warn("OpsKit webhook 验签失败 remote={} configured={}（核对订阅密钥与 SHAREHUB_OPSKIT_WEBHOOK_SECRET、时钟偏差）",
                    req.getRemoteAddr(), !secret.isBlank());
            write(resp, 401, Map.of("error", "invalid_signature"));
            return;
        }
        Map<String, Object> env = JSON.readValue(body, Map.class);
        String type = env.get("eventType") == null ? null : String.valueOf(env.get("eventType"));
        String idemKey = env.get("idemKey") == null ? null : String.valueOf(env.get("idemKey"));
        Map<String, Object> data = env.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        try {
            boolean done = service.handle(type, idemKey, data);
            write(resp, 200, Map.of("handled", done));
        } catch (RuntimeException e) {
            // 业务推进失败：回 500 让 OpsKit 退避重投；带上事件键，排错可直接对上 OpsKit 的投递记录
            log.error("OpsKit 事件回写失败，等待重投 type={} idemKey={}", type, idemKey, e);
            write(resp, 500, Map.of("error", "handler_failed"));
        }
    }

    /** 与 OpsKit {@code SignatureSpec.verify} 同一算法；常量时间比较，时间戳超窗视为重放 */
    static boolean verify(String secret, String header, String body, long nowMs) {
        if (secret == null || secret.isBlank() || header == null) return false;
        long t = -1;
        String v1 = null;
        for (String part : header.split(",")) {
            if (part.startsWith("t=")) {
                try {
                    t = Long.parseLong(part.substring(2));
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (part.startsWith("v1=")) {
                v1 = part.substring(3);
            }
        }
        if (t < 0 || v1 == null || Math.abs(nowMs - t) > TOLERANCE_MS) return false;
        return MessageDigest.isEqual(v1.getBytes(StandardCharsets.US_ASCII),
                hmac(secret, t + "." + body).getBytes(StandardCharsets.US_ASCII));
    }

    static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(HttpServletResponse resp, int status, Map<String, Object> body) throws IOException {
        resp.setStatus(status);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        JSON.writeValue(resp.getOutputStream(), body);
    }
}
