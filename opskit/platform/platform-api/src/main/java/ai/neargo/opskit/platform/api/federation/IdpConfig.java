package ai.neargo.opskit.platform.api.federation;

/**
 * 账户登记的身份提供方。
 *
 * @param issuer        JWT：令牌的 {@code iss}；INTROSPECT：一个全局唯一的逻辑名
 * @param tokenPrefix   仅 INTROSPECT：不透明令牌没有 iss，按前缀路由到本 IdP（如 {@code stk_}），全局唯一
 * @param clientSecret  仅 INTROSPECT：调内省端点的凭据明文（入库前加密，读不回）
 */
public record IdpConfig(String issuer, IdpKind kind, String jwksUrl, String audience, String introspectUrl, String clientId,
        String clientSecret, String tokenPrefix, ClaimMapping claimMapping, boolean jitEnabled) {
}
