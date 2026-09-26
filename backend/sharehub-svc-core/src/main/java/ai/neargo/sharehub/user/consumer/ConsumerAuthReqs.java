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
     * <p><b>{@code countryCode} 目前不参与任何计算</b>，而 c-app 每一次都在发它
     * （登录/注册/找回三处都是 {@code {countryCode: "+971", phone: "501234567"}}）。
     * 后端只拿 {@code phone} 当键：{@code OtpService} 的码表按它索引，
     * {@code PhoneOtpStrategy} 的 {@code uid = sha256(phone)} 也按它派生 ——
     * 于是 <b>+971 501234567 与 +966 501234567 是同一个 OTP、同一个账号</b>
     * （钱包/订单/券/押金全都共用）。
     *
     * <p>{@code PhoneOtpStrategy} 里那句 {@code // 生产：E.164 归一} 是**没有实现的意图**：
     * 实际只有 {@code .trim()}。修它要改 {@code uid} 的组成，等于给存量账号改身份 ——
     * 属于要先拍板的迁移，见 {@code known-map-request-bodies.txt} 的说明与报告。
     * 字段先收进契约而不是继续丢掉：丢掉就没人看得见这件事。
     *
     * <p>{@code scene} 同理被忽略 —— 于是「找回密码」发的码可以用来登录。
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
