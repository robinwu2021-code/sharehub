package ai.neargo.sharehub.user.consumer;

/**
 * 各端登录策略解析出的**规范身份**（TDD-认证鉴权-实现细节 §B1）。
 * 差异只在 openid/sub/phone 的获取；之后建户/归并/签发统一。
 *
 * @param unionKey  跨渠道归并键：微信 unionid（有则用），否则 'PROVIDER:uid'
 * @param legacyUid 同一个人在**旧算法**下的 {@code providerUid}，没有历史包袱的渠道传 {@code null}。
 *                  存在的唯一目的是惰性重键：新算法查不到时按它兜底命中并就地改写
 *                  （见 {@code ConsumerAuthService#upsertByIdentity}）。不参与写入。
 */
public record ResolvedIdentity(
        Provider provider,
        String providerUid,
        String unionKey,
        String phone,
        String nickname,
        String avatar,
        String legacyUid) {

    /** 无历史包袱的渠道用这个（openid / sub 的算法从未变过）。 */
    public ResolvedIdentity(Provider provider, String providerUid, String unionKey,
                            String phone, String nickname, String avatar) {
        this(provider, providerUid, unionKey, phone, nickname, avatar, null);
    }
}
