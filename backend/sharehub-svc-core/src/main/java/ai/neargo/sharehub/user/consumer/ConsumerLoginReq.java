package ai.neargo.sharehub.user.consumer;

/**
 * C 端统一登录入参（{@code POST /mp/auth/login}）。grantType 分发到对应策略；
 * 其余字段按渠道各取所需（App/小程序/H5 归一，见 TDD-认证鉴权-实现细节 §B2）。
 */
public record ConsumerLoginReq(
        String grantType,     // phone_otp / wechat_miniapp / wechat_oauth / apple / google
        String tenantNo,
        String phone,         // phone_otp
        String otp,
        String regionCode,
        String jsCode,        // wechat_miniapp
        String code,          // wechat_oauth
        String unionid,       // 微信 unionid（stub 归并用）
        String identityToken, // apple
        String idToken,       // google
        String nickname,
        String avatar) {
}
