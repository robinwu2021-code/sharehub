package ai.neargo.sharehub.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * 登录标识的确定性哈希：{@code HMAC-SHA256(规范化值, pepper)} → 64 位小写十六进制。
 *
 * <p><b>为什么是 HMAC 而不是裸 SHA-256</b>：手机号的取值空间小到可以穷举
 * （一个国家的号段撑死十亿级），裸哈希等于没加密 —— 拿到库的人跑几分钟就能反查出全部手机号。
 * 加 pepper 之后，不掌握 pepper 就无法离线穷举。
 *
 * <p><b>pepper 的三条约束</b>：
 * <ol>
 *   <li><b>不配就启动失败</b>（fail-closed）。给个默认值等于所有部署共用一个 pepper，等于没有；</li>
 *   <li><b>不进数据库、不进日志、不进异常消息</b>；</li>
 *   <li><b>轮换靠 {@code *_enc} 全表重算</b>：解出明文 → 用新 pepper 重算 hash → 统一切 {@code hash_ver}。
 *       唯一键不动。这是 {@code *_enc} 必须可逆的唯一理由（ADR-030 §2.3）。</li>
 * </ol>
 *
 * <p>输出长度固定 64，与 {@code agt_principal.phone_hash VARCHAR(64)} 对齐。
 * ⚠️ 那两列<b>必须是 {@code VARCHAR} 不能是 {@code CHAR}</b> —— {@code agt_apply.active_key}
 * 生成列里的 {@code CASE} 两分支类型不同时，MariaDB 报 {@code ERROR 1901} 直接建不出表（V48 实测）。
 */
public final class IdentifierHasher {

    private static final String ALGO = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] pepper;
    private final int version;

    public IdentifierHasher(String pepper, int version) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException(
                    "sharehub.security.identity-pepper 未配置：登录标识哈希不能没有 pepper —— "
                            + "手机号取值空间小到可以穷举，裸哈希等于明文存储");
        }
        if (pepper.length() < 32) {
            throw new IllegalStateException(
                    "sharehub.security.identity-pepper 至少 32 字符（当前 " + pepper.length() + "）");
        }
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
        this.version = version;
    }

    /** 当前 pepper 版本，写进 {@code hash_ver}；轮换后据它判断哪些行还没重算。 */
    public int version() {
        return version;
    }

    /**
     * 对**已规范化**的值求 hash。
     *
     * <p>⚠️ 传进来的必须是 {@link IdentifierNormalizer} 的输出。直接传用户原始输入的话，
     * 同一个人换个写法就是另一个 hash —— 注册与登录对不上，且不会报任何错。
     */
    public String hash(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException("待哈希的值为空");
        }
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(pepper, ALGO));
            return hex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // 两者都是环境级故障（JCE 缺算法 / 密钥不合法），不是业务错
            throw new IllegalStateException("HMAC 不可用: " + e.getMessage(), e);
        }
    }

    private static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = HEX[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[b[i] & 0xF];
        }
        return new String(out);
    }
}
