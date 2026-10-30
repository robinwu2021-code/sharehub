package ai.neargo.sharehub.user.consumer;

import ai.neargo.sharehub.api.platform.port.NotifyPort;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.auth.DevMode;
import ai.neargo.sharehub.identity.IdentifierNormalizer;   // javadoc 引用
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
 *
 * <p><b>2026-09-26 投递</b>：此前本类**只生成码、从不投递** —— 生产不回传码（对的），
 * 又没有任何一处把码交给短信通道，于是 C 端登录在线上是彻底走不通的死路（而不是「差个渠道商」）。
 * 现在发码即经 {@link NotifyPort#sendToTarget} 走通知唯一正门（黑名单在那里统一拦）。
 *
 * <p>⚠️ 边界：通道商尚未对接（{@code NotifySendServiceImpl} 的 {@code TODO(接入层)} 之后
 * 无条件置 {@code SENT}），所以 {@code delivered=true} 目前只表示「已交给正门且未被黑名单拦」，
 * <b>不表示用户真收到了</b>。这条边界在接入层落地后自动消失，不需要改这里。
 *
 * <p>码表的键是**归一后**的手机号（{@link IdentifierNormalizer#phone}），与
 * {@link PhoneOtpStrategy} 算 uid 用的是同一个方法 —— 否则「按 0501… 发码、按 +971501… 登录」
 * 会撞出一句无从解释的「验证码错误」。
 */
@Service
public class OtpService {

    /** 开发固定码：只在 dev-mode 开启时可通过校验，且不再作为「发出去的码」。 */
    static final String DEV_MASTER = "000000";

    /** 重发太频繁与当日已达上限说同一句话：对调用方都是「现在别再发了」。 */
    private static final String TOO_FREQUENT = "发送过于频繁，请稍后再试";

    private static final int CODE_DIGITS = 6;
    private static final int CODE_BOUND = 1_000_000;          // 6 位十进制的上界
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OtpService.class);

    private final DevMode devMode;
    private final NotifyPort notify;
    private final Duration ttl;
    private final Duration resendInterval;
    private final int maxAttempts;
    private final String smsTemplate;
    private final String emailTemplate;

    /** 邮箱验证码（商家一期 B2，R1 / R4）的码表键前缀 —— 与手机号分开命名空间，同一串字符不会互相顶掉。 */
    private static final String EMAIL_KEY = "email:";

    /**
     * 一次发码的结果。
     *
     * @param phone     归一后的手机号（调用方要再用它时别用原始输入）
     * @param code      码本身；**只允许 dev-mode 回传给端上**，见 {@code ConsumerAuthController}
     * @param delivered 是否已交给通知正门（见类注释的 ⚠️ 边界）
     * @param reason    未投递的原因（{@code UNSUPPORTED_CHANNEL}/{@code BLACKLISTED}/…），已投递则 null
     */
    public record Issued(String phone, String code, boolean delivered, String reason) {
    }

    private final ConcurrentHashMap<String, Entry> codes = new ConcurrentHashMap<>();
    /** 同一标识当天已发几次（需求方案 §四「同一标识每日上限」）。键 = 码表键，值 = [日期, 次数]。 */
    private final ConcurrentHashMap<String, long[]> daily = new ConcurrentHashMap<>();
    private final int dailyLimit;

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

    public OtpService(DevMode devMode, NotifyPort notify,
                      @Value("${sharehub.auth.otp.ttl:5m}") Duration ttl,
                      @Value("${sharehub.auth.otp.resend-interval:60s}") Duration resendInterval,
                      @Value("${sharehub.auth.otp.max-attempts:5}") int maxAttempts,
                      @Value("${sharehub.auth.otp.sms-template:Your ShareHub verification code is {code}. It expires in 5 minutes.}")
                      String smsTemplate,
                      @Value("${sharehub.auth.otp.email-template:Your ShareHub verification code is {code}. It expires in 5 minutes. If you did not request it, ignore this email.}")
                      String emailTemplate,
                      @Value("${sharehub.auth.otp.daily-limit:10}") int dailyLimit) {
        this.dailyLimit = dailyLimit;
        this.emailTemplate = emailTemplate;
        this.devMode = devMode;
        this.notify = notify;
        this.smsTemplate = smsTemplate;
        this.ttl = ttl;
        this.resendInterval = resendInterval;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 发码并投递。dev-mode 下发固定码便于联调，否则发真随机码。
     *
     * <p><b>投递失败会把刚存的码删掉</b>，这一步不是"顺手清理"而是必需的：码留在表里，
     * {@code resend-interval} 就会把用户锁在「重发太频繁」上 60 秒，去等一条永远不会到的短信 ——
     * 一个既收不到码、又不让重试的死局。删掉之后用户可以立刻重试。
     *
     * @param phone <b>必须是 {@link IdentifierNormalizer#phone} 的输出</b>，与 {@link #verify}
     *              和 {@code PhoneOtpStrategy} 算 uid 用的是同一个值。
     *              ⚠️ 本方法<b>不能</b>顺手再归一一次 —— {@code phone()} 不是幂等的：
     *              它的输出（{@code 971501234567}，不带 {@code +}）再喂回去会被当成本地号
     *              二次补区号成 {@code 971971501234567}，长度 15 仍在 E.164 上限内、
     *              <b>不报任何错</b>。代理侧（{@code OtpGate}）传进来的本来就是归一后的值。
     * @param scene login / register / reset，进模板号 {@code OTP_<scene>}，落 notify_log 便于分场景统计
     * @throws IllegalStateException 距上次发码不足 {@code resend-interval}
     */
    /** 重发间隔（秒）—— 端上的倒计时按它走，与服务端的「重发太频繁」同一个数。 */
    public long resendIntervalSeconds() {
        return resendInterval.toSeconds();
    }

    public Issued issue(String phone, String scene) {
        return issue(phone, phone, "SMS", smsTemplate, scene);
    }

    /**
     * 发邮箱验证码（商家一期 B2）。限流、有效期、次数上限、dev 固定码与手机号同一套。
     *
     * @param email <b>必须是 {@link IdentifierNormalizer#email} 的输出</b>（去空格、转小写），
     *              与 {@link #verifyEmail} 用同一个值
     */
    public Issued issueEmail(String email, String scene) {
        return issue(EMAIL_KEY + email, email, "EMAIL", emailTemplate, scene);
    }

    /** 校验邮箱验证码；语义同 {@link #verify}。 */
    public void verifyEmail(String email, String otp) {
        verify(EMAIL_KEY + email, otp);
    }

    private Issued issue(String key, String target, String channel, String template, String scene) {
        Instant now = Instant.now();
        Entry prev = codes.get(key);
        boolean tooSoon = prev != null && now.isBefore(prev.issuedAt.plus(resendInterval));
        // 每日上限：重发间隔只挡连点，挡不住「每分钟一条、发一整天」地轰炸同一个号或邮箱。
        // 「一天」按 UTC 切 —— 这是限流窗口，不是业务日期，不需要随业务时区走
        long today = now.getEpochSecond() / 86_400;
        long[] sentToday = daily.compute(key, (k, v) -> v == null || v[0] != today ? new long[]{today, 0} : v);
        synchronized (sentToday) {
            if (tooSoon || sentToday[1] >= dailyLimit) {
                throw new IllegalStateException(TOO_FREQUENT);
            }
            sentToday[1]++;
        }
        String code = devMode.isEnabled() ? DEV_MASTER : randomCode();
        codes.put(key, new Entry(code, now));

        String sc = (scene == null || scene.isBlank()) ? "login" : scene.trim().toLowerCase();
        NotifyPort.Delivery d = notify.sendToTarget(
                target, channel, "OTP_" + sc, template.replace("{code}", code));
        if (!d.sent()) {
            codes.remove(key);   // 见上：不删就是「收不到又不让重试」
            // 不打 phone/code：手机号与令牌都属敏感信息（CLAUDE.md 日志规范）。
            // 运维动作：查 notify_log 的 OTP_* 场景与渠道商状态 —— 该场景全量失败则 C 端登录已中断。
            log.error("OTP 投递失败 channel={} scene={} reason={}，该标识本次无法登录", channel, sc, d.reason());
        }
        return new Issued(target, code, d.sent(), d.reason());
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
