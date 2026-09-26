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
     * <p><b>{@code countryCode} 仍不参与任何计算</b>，而 c-app 每一次都在发它
     * （{@code {countryCode: "+971", phone: "501234567"}}）。区号由两处提供：
     * {@code phone} 自带（{@code +971…} / {@code 00971…}），或由
     * {@code sharehub.security.default-calling-code} 补全 —— 于是
     * <b>+971 501234567 与 +966 501234567 仍是同一个账号</b>。
     *
     * <p>为什么没在归一那批里一起改：让 {@code countryCode} 参与派生会让惰性重键
     * 失去唯一性 —— 两个区号的旧 uid 是同一个 {@code sha256("501234567")}，
     * 会争抢同一条历史行、把账号在两个人之间来回搬。这一条要先拍板
     * （存量里真有跨市场撞号的账号时，它归谁？），见 {@code ConsumerPhoneIdentityTest}。
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
