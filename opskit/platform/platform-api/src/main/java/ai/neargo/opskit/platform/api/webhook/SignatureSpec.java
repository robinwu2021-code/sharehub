package ai.neargo.opskit.platform.api.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * webhook 签名（21/06 §六）：头 {@code X-OpsKit-Signature: t=<毫秒>,v1=<hex>}，
 * {@code v1 = HMAC-SHA256(secret, t + "." + body)}。放在 api 里，接入方的接收骨架与服务端共用同一份实现。
 */
public final class SignatureSpec {

    public static final String HEADER = "X-OpsKit-Signature";
    public static final long TOLERANCE_MS = 5 * 60 * 1000L;

    private SignatureSpec() {
    }

    public static String sign(String secret, long timestampMs, String body) {
        return "t=" + timestampMs + ",v1=" + hmac(secret, timestampMs + "." + body);
    }

    /** 常量时间比较；时间戳超出 ±5 分钟视为重放。 */
    public static boolean verify(String secret, String header, String body, long nowMs) {
        if (header == null) {
            return false;
        }
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
        if (t < 0 || v1 == null || Math.abs(nowMs - t) > TOLERANCE_MS) {
            return false;
        }
        return MessageDigest.isEqual(v1.getBytes(StandardCharsets.US_ASCII), hmac(secret, t + "." + body).getBytes(StandardCharsets.US_ASCII));
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
