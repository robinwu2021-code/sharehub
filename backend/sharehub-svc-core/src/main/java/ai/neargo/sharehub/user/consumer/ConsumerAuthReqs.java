package ai.neargo.sharehub.user.consumer;

/**
 * C 端认证的写入参（裸 {@code Map} → record，2026-09-26；见 backend/known-map-request-bodies.txt）。
 *
 * <p>裸 Map 当请求体 = 这个端点没有契约：契约抽取抽不出形状，拼错的键静默忽略。
 * 换成 record 的第一件事就是<b>把 c-app 一直在发、后端一直没读的两个键显出来</b>：
 * {@code countryCode} 与 {@code scene}。
 */
public final class ConsumerAuthReqs {

    private ConsumerAuthReqs() {
    }

    /**
     * 发送 OTP 入参。
     *
     * <p><b>{@code scene}</b>（2026-09-26 起生效）进模板号 {@code OTP_<scene>}，
     * 落 {@code notify_log} 便于分场景统计。⚠️ 但它<b>不进码表的键</b> ——
     * 码仍只按手机号索引，所以 scene=reset 发的码拿去 login 一样能过。
     * 现在不可利用（密码重置端点本身未开通、一律 400），真开通前必须先把 scene 并进键。
     *
     * <p><b>{@code countryCode} 参与身份派生</b>（2026-09-27 起）：{@code phone} 自带 {@code +} 以它为准，
     * 否则用 {@code countryCode}，都没有才用 {@code default-calling-code}
     * （{@code IdentifierNormalizer#phone(String, String)}）。此前它没人读，
     * +971 与 +966 的同一本地号是同一个账号。
     *
     * <p>惰性重键的唯一性靠「历史行一律归默认市场」保住（拍板）：只有归一后落在默认区号的号码
     * 才去认领旧 uid，见 {@code ConsumerPhoneIdentityTest}。
     *
     * <p>归一与 pepper 本身已经修了：{@code PhoneOtpStrategy} 改用公共的
     * {@code IdentifierNormalizer} + {@code IdentifierHasher}，与员工侧同一个方法
     * （ADR-030 §4.1），见 {@code ConsumerPhoneNormalizationTest}。
     */
    public record OtpReq(String countryCode, String phone, String scene) {
    }

    /**
     * 注册入参。
     *
     * <p><b>{@code password} 2026-09-26 起受理</b>（{@code cred_credential} 的
     * {@code realm=CONSUMER}）。空着就不设 —— 注册不强制设口令。
     * 出参 {@code passwordStored} 如实回「这次到底存了没有」。
     *
     * @param country ISO alpha-2（AE/SA…），判**密码登录开关**用；与 {@code countryCode}
     *                （拨号码 +971）不是一回事
     */
    public record RegisterReq(String countryCode, String country, String phone, String otp,
                              String nickname, String password) {
    }

    /**
     * 忘记密码入参：验 {@code otp} 通过后把 {@code password} 设为新口令。
     *
     * <p>原注释写着「能力未开通，端点如实 400」—— 那个状态 2026-09-26 结束了。
     *
     * @param country ISO alpha-2，判开关用
     */
    public record ResetPwdReq(String countryCode, String country, String phone,
                              String otp, String password) {
    }

    /**
     * 改密入参（已登录）。**故意不收 cUserNo** —— 它取自会话；
     * 收了就意味着任何登录用户都能改别人的口令，而那种参数一旦存在，早晚有人会读它。
     *
     * @param country ISO alpha-2，判开关用
     */
    public record ChangePwdReq(String country, String oldPassword, String newPassword) {
    }
}
