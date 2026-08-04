package ai.neargo.sharehub.user.consumer;

/**
 * 各端登录策略解析出的**规范身份**（TDD-认证鉴权-实现细节 §B1）。
 * 差异只在 openid/sub/phone 的获取；之后建户/归并/签发统一。
 *
 * @param unionKey 跨渠道归并键：微信 unionid（有则用），否则 'PROVIDER:uid'
 */
public record ResolvedIdentity(
        Provider provider,
        String providerUid,
        String unionKey,
        String phone,
        String nickname,
        String avatar) {
}
