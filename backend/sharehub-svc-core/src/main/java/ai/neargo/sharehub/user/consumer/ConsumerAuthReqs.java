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

    /** 注册入参。{@code password} **不受理**（凭据库 pb_auth 未建），出参 {@code passwordStored=false} 如实声明。 */
    public record RegisterReq(String countryCode, String phone, String otp,
                              String nickname, String password) {
    }

    /** 密码重置入参 —— 能力未开通，端点如实 400。字段照 c-app 的 {@code ResetPwdParams} 收全，便于开通时不改契约。 */
    public record ResetPwdReq(String countryCode, String phone, String otp, String password) {
    }
}
