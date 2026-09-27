package ai.neargo.opskit.platform.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 应用凭证：{@code okk_} + 32 字节随机（Base64url）。前缀便于在日志、密钥扫描器里识别泄露。
 *
 * <p>只存 SHA-256：凭证是 256 位随机串，不是人记的口令，暴力枚举不可行，慢哈希（bcrypt 等）只会拖慢每次调用。
 */
public final class Credentials {

    static final String PREFIX = "okk_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Credentials() {
    }

    public static String newCredential() {
        return PREFIX + randomToken(32);
    }

    public static String newSigningSecret() {
        return "oks_" + randomToken(32);
    }

    public static String hash(String credential) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(credential.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hint(String credential) {
        return credential.substring(credential.length() - 4);
    }

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
