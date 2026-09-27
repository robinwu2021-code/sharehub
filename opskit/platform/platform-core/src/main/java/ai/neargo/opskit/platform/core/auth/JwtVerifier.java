package ai.neargo.opskit.platform.core.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * JWT 校验：签名（JWKS）· 签发方 · 有效期 · 受众。解码器按签发方缓存，JWKS 由 Nimbus 自己缓存与轮换。
 * 签名算法不接受 none：NimbusJwtDecoder 只认 JWKS 里公钥对应的算法。
 */
final class JwtVerifier {

    private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

    Map<String, Object> verify(String token, String issuer, String jwksUrl, String audience) {
        JwtDecoder decoder = decoders.computeIfAbsent(issuer + "|" + jwksUrl + "|" + audience, k -> build(issuer, jwksUrl, audience));
        try {
            Jwt jwt = decoder.decode(token);
            return jwt.getClaims();
        } catch (JwtException e) {
            throw new BadToken(e);
        }
    }

    void clear() {
        decoders.clear();
    }

    private static JwtDecoder build(String issuer, String jwksUrl, String audience) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(jwksUrl).build();
        OAuth2TokenValidator<Jwt> base = JwtValidators.createDefaultWithIssuer(issuer);
        d.setJwtValidator(audience == null ? base : new DelegatingOAuth2TokenValidator<>(base,
                new JwtClaimValidator<java.util.List<String>>("aud", aud -> aud != null && aud.contains(audience))));
        return d;
    }

    /** 任何校验失败都归为同一种「坏令牌」，对外一律 401。 */
    static final class BadToken extends RuntimeException {
        BadToken(Throwable cause) {
            super(cause);
        }
    }
}
