package ai.neargo.sharehub.user.consumer;

/**
 * C 端统一登录入参（{@code POST /mp/auth/login}）。grantType 分发到对应策略；
 * 其余字段按渠道各取所需（App/小程序/H5 归一，见 TDD-认证鉴权-实现细节 §B2）。
 *
 * <p><b>2026-09-26</b>：原来第 5 位是 {@code regionCode} —— 全仓<b>无人读、也无人发</b>
 * （c-app 发的是 {@code countryCode}，那个名字在本 record 里根本不存在，于是被静默丢弃）。
 * 换成 {@code country}（ISO alpha-2），由 {@link LoginMethodGate} 真正读它。
 * 顶掉一个死字段而不是并排再加一个：两个都像「国家」的字段一定会有人填错那一个。
 */
public record ConsumerLoginReq(
        String grantType,     // phone_otp / wechat_miniapp / wechat_oauth / apple / google
        String tenantNo,
        String phone,         // phone_otp
        String otp,
        String country,       // ISO alpha-2，选登录方式开关用（原 regionCode 是死字段，无人读也无人发）
        String jsCode,        // wechat_miniapp
        String code,          // wechat_oauth
        String unionid,       // 微信 unionid（stub 归并用）
        String identityToken, // apple
        String idToken,       // google
        String nickname,
        String avatar) {
}
