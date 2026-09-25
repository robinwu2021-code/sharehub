package ai.neargo.sharehub.user.consumer;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.auth.DevMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手机 OTP（内存实现；落库 + 真实短信通道见 v4/06 的 A3）。
 *
 * <p><b>2026-09-23 安全止血</b>（TDD-auth-security-hotfix）：此前 {@code verify} 对任何手机号
 * 无条件接受固定码 {@code 000000}，且发码接口把码回传给调用方 —— 线上任何人可登录任意手机号。
 * 现在固定码只在 {@link DevMode} 开启时有效，生产发的是真随机码。
 *
 * <p>四条约束（都可配，见 {@code sharehub.auth.otp.*}）：有效期、重发间隔、单码最多校验次数、
 * 校验成功即失效。内存实现的已知边界：进程重启丢失、多实例不共享 —— 止血够用，落库属 A3。
 */
@Service
public class OtpService {

    /** 开发固定码：只在 dev-mode 开启时可通过校验，且不再作为「发出去的码」。 */
    static final String DEV_MASTER = "000000";

    private static final int CODE_DIGITS = 6;
    private static final int CODE_BOUND = 1_000_000;          // 6 位十进制的上界
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DevMode devMode;
    private final Duration ttl;
    private final Duration resendInterval;
    private final int maxAttempts;

    private final ConcurrentHashMap<String, Entry> codes = new ConcurrentHashMap<>();

    /** 一次发码：码本身、发出时刻、已校验次数。 */
    private static final class Entry {
        final String code;
        final Instant issuedAt;
        int attempts;

        Entry(String code, Instant issuedAt) {
            this.code = code;
            this.issuedAt = issuedAt;
        }
    }

    public OtpService(DevMode devMode,
                      @Value("${sharehub.auth.otp.ttl:5m}") Duration ttl,
                      @Value("${sharehub.auth.otp.resend-interval:60s}") Duration resendInterval,
                      @Value("${sharehub.auth.otp.max-attempts:5}") int maxAttempts) {
        this.devMode = devMode;
        this.ttl = ttl;
        this.resendInterval = resendInterval;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 发码。dev-mode 下发固定码便于联调，否则发真随机码。
     *
     * <p>返回值只供**调用方决定是否展示**（见 {@code ConsumerAuthController}：仅 dev-mode 回传）；
     * 生产应由短信通道送达，接口不回传。
     *
     * @throws IllegalStateException 距上次发码不足 {@code resend-interval}
     */
    public String issue(String phone) {
        Instant now = Instant.now();
        Entry prev = codes.get(phone);
        if (prev != null && now.isBefore(prev.issuedAt.plus(resendInterval))) {
            throw new IllegalStateException("发送过于频繁，请稍后再试");
        }
        String code = devMode.isEnabled() ? DEV_MASTER : randomCode();
        codes.put(phone, new Entry(code, now));
        return code;
    }

    /**
     * 校验；成功即失效（单次可用）。失败抛 {@link IllegalArgumentException}（→400）。
     *
     * <p>校验次数达上限即作废该码 —— 否则 6 位码在 5 分钟内可被暴力枚举。
     */
    public void verify(String phone, String otp) {
        if (otp == null || otp.isBlank()) {
            throw BizException.badRequest("error.otp.required");
        }
        // 固定码：仅 dev-mode。放在最前面是为了本机联调不依赖「先发码」这一步。
        if (devMode.isEnabled() && DEV_MASTER.equals(otp)) {
            codes.remove(phone);
            return;
        }
        Entry e = codes.get(phone);
        if (e == null) {
            throw BizException.badRequest("error.otp.invalid");
        }
        if (Instant.now().isAfter(e.issuedAt.plus(ttl))) {
            codes.remove(phone);
            throw BizException.badRequest("error.otp.invalid");
        }
        // 先累加再判断：第 maxAttempts 次失败后作废，避免「最后一次」还能再试
        if (++e.attempts > maxAttempts) {
            codes.remove(phone);
            throw BizException.badRequest("error.otp.too_many");
        }
        if (!e.code.equals(otp)) {
            throw BizException.badRequest("error.otp.invalid");
        }
        codes.remove(phone);
    }

    /** 定长 6 位随机码（不足位补零，保证位数一致）。 */
    private static String randomCode() {
        return String.format("%0" + CODE_DIGITS + "d", RANDOM.nextInt(CODE_BOUND));
    }
}
