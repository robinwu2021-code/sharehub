package ai.neargo.opskit.platform.core.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 需要能还原的密钥（webhook 签名密钥、内省客户端密钥）用 AES-256-GCM 加密后入库。
 * 存储格式：{@code v1:} + Base64(12 字节随机 IV ‖ 密文含 16 字节认证标签)。带版本前缀，将来换密钥 / 算法可以并存。
 *
 * <p>只做「能还原」的密钥；调用凭证只存哈希，不经过这里。
 */
public final class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /** @param base64Key 32 字节密钥的 Base64（配置项 opskit.crypto.key），长度不对直接拒绝启动 */
    public SecretCipher(String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key);
        if (raw.length != 32) {
            throw new IllegalArgumentException("opskit.crypto.key must be 32 bytes base64-encoded, got " + raw.length + " bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalArgumentException("unsupported secret format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // 认证标签校验失败：密文被改过，或用错了密钥
            throw new IllegalStateException("decrypt failed (tampered or wrong key)", e);
        }
    }
}
