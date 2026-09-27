package ai.neargo.sharehub.user.consumer;

import ai.neargo.sharehub.identity.IdentifierHasher;
import ai.neargo.sharehub.identity.IdentifierNormalizer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 手机号 OTP 登录（App + H5 普通浏览器，MENA 主路径）。
 *
 * <p><b>2026-09-26</b>：此前这里自带一套 {@code sha256(phone.trim())}，与仓库既有的
 * {@link IdentifierNormalizer} + {@link IdentifierHasher} 并行存在，于是同时犯了那两个类的
 * javadoc 明确写着要避免的两件事：
 * <ol>
 *   <li><b>不归一</b>：{@code 0501234567} / {@code +971501234567} / {@code 00971501234567}
 *       是同一个人的三种写法，却是三个 uid → 三个账号。失败形态是「注册能成、登录查不到」，
 *       不报任何错（{@code IdentifierHasher#hash} 的 ⚠️ 段落）；</li>
 *   <li><b>裸哈希</b>：手机号取值空间穷举得完，无 pepper 的 hash 等于明文存储
 *       （{@code IdentifierHasher} 类注释）—— 而 pepper 是配置里强制项、员工侧已在用。</li>
 * </ol>
 * 现在两件事都交给那两个公共类，与员工侧（{@code StaffIdentityBackfillJob}）走同一个方法，
 * 满足 ADR-030 §4.1「注册与登录必须调同一个方法」。
 *
 * <p>存量账号不会因此丢：{@link #legacyUid} 把旧算法的 uid 一并带出去，
 * {@code ConsumerAuthService} 查不到新 uid 时按它兜底并就地重键。
 */
@Component
public class PhoneOtpStrategy implements ConsumerLoginStrategy {

    private final OtpService otp;
    private final IdentifierNormalizer normalizer;
    private final IdentifierHasher hasher;

    public PhoneOtpStrategy(OtpService otp, IdentifierNormalizer normalizer, IdentifierHasher hasher) {
        this.otp = otp;
        this.normalizer = normalizer;
        this.hasher = hasher;
    }

    @Override
    public String grantType() {
        return "phone_otp";
    }

    @Override
    public ResolvedIdentity authenticate(ConsumerLoginReq req) {
        if (req.phone() == null || req.phone().isBlank()) {
            throw new IllegalArgumentException("手机号为空");
        }
        // 归一必须在校验之前：发码时存的键也是归一后的值（见 OtpService），
        // 否则「按 0501… 发码、按 +971501… 登录」会得到一句无从解释的「验证码错误」。
        String phone = normalizer.phone(req.phone(), req.countryCode());
        otp.verify(phone, req.otp());
        String uid = hasher.hash(phone);
        // 历史行一律归默认市场：别的区号的同号不去认领（见 IdentifierNormalizer#isDefaultMarket）
        String legacy = normalizer.isDefaultMarket(phone) ? legacyUid(req.phone()) : null;
        return new ResolvedIdentity(Provider.PHONE, uid, "PHONE:" + uid,
                phone, req.nickname(), req.avatar(), legacy);
    }

    /**
     * 旧算法的 uid：{@code sha256(raw.trim())} 取前 32 位。
     *
     * <p>**刻意保留原样**（包括不归一、不加 pepper）—— 它的用途只是去库里找那条旧行，
     * 复现不了旧算法就找不到，账号就真丢了。重键成功后这个值再也不会被写入任何地方。
     */
    static String legacyUid(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
