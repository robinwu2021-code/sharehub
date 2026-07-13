package ai.neargo.powerbank.user.consumer;

/**
 * C 端登录策略：每渠道一实现，把渠道凭据校验为规范 {@link ResolvedIdentity}。
 * 端差异全封装在此；登录后（建户/归并/签发/鉴权）完全统一。
 */
public interface ConsumerLoginStrategy {

    /** grantType 标识（与 {@link ConsumerLoginReq#grantType()} 对应）。 */
    String grantType();

    /** 校验凭据 → 规范身份；失败抛 {@link IllegalArgumentException}（→400）。 */
    ResolvedIdentity authenticate(ConsumerLoginReq req);
}
