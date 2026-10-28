package ai.neargo.sharehub.identity;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 敏感字段的可逆加密（AES-256-GCM）。用在「必须能还原明文」的列上 —— 第一个用处是收款账户的完整 IBAN：
 * 付款任务导出银行批量转账文件时要原文，而库里此前只存了掩码（sharehub_pii 未建）。
 *
 * <p>密文格式：{@code 版本(1) | IV(12) | 密文+认证标签}。版本位留给密钥轮换：
 * 轮换时新数据用新版本，旧数据按版本取旧密钥解，之后全表重加密再下线旧密钥。
 *
 * <p><b>没有密钥时是「关闭」而不是「起不来」</b>：与登录 pepper 不同，这里缺密钥的后果不是静默错误，
 * 而是「新录的账户不存原文、导出时被拦并说明原因」—— 显式、可补救。
 * 让应用直接起不来，会把一次部署配置遗漏放大成整站不可用。
 */
public final class FieldCipher {

    private static final int VERSION = 1;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /** @param key 32 字节；null = 未配置（加密关闭） */
    FieldCipher(byte[] key) {
        if (key != null && key.length != 32) {
            throw new IllegalStateException("sharehub.security.pii-key 必须是 32 字节（base64 编码后 44 个字符）");
        }
        this.key = key == null ? null : new SecretKeySpec(key, "AES");
    }

    /** 是否配置了密钥。未配置时 {@link #encrypt} 返回 null，调用方只落掩码。 */
    public boolean enabled() {
        return key != null;
    }

    /** @return 密文；未配置密钥或明文为空时返回 null */
    public byte[] encrypt(String plain) {
        if (key == null || plain == null || plain.isEmpty()) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + IV_LEN + body.length];
            out[0] = VERSION;
            System.arraycopy(iv, 0, out, 1, IV_LEN);
            System.arraycopy(body, 0, out, 1 + IV_LEN, body.length);
            return out;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("敏感字段加密失败", e);
        }
    }

    /** @return 明文；密文为空返回 null。未配置密钥或密文被篡改时抛异常 —— 宁可导出失败，也不导出错的账号 */
    public String decrypt(byte[] blob) {
        if (blob == null || blob.length == 0) return null;
        if (key == null) throw new IllegalStateException("未配置 sharehub.security.pii-key，无法还原敏感字段");
        if (blob[0] != VERSION || blob.length <= 1 + IV_LEN) {
            throw new IllegalStateException("敏感字段密文版本不支持：" + blob[0]);
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(blob, 1, 1 + IV_LEN)));
            return new String(c.doFinal(Arrays.copyOfRange(blob, 1 + IV_LEN, blob.length)), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("敏感字段解密失败（密钥不对或密文被改过）", e);
        }
    }
}
