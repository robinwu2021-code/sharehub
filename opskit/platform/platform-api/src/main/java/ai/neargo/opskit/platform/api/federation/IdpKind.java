package ai.neargo.opskit.platform.api.federation;

/** JWT = 自带签名，按 JWKS 校验；INTROSPECT = 不透明令牌，调宿主的内省端点（RFC 7662 形状）。 */
public enum IdpKind {
    JWT, INTROSPECT
}
