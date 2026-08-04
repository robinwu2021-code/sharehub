package ai.neargo.sharehub.user.consumer;

import org.springframework.stereotype.Component;

/**
 * Apple 登录（App iOS / Web）。
 * <p>MVP stub：sub 由 identityToken 派生；真实实现校验 Apple JWKS（iss/aud/exp/nonce）取 sub。
 */
@Component
public class AppleStrategy implements ConsumerLoginStrategy {

    @Override
    public String grantType() {
        return "apple";
    }

    @Override
    public ResolvedIdentity authenticate(ConsumerLoginReq req) {
        if (req.identityToken() == null || req.identityToken().isBlank()) {
            throw new IllegalArgumentException("identityToken 为空");
        }
        String sub = "apple_" + Integer.toHexString(req.identityToken().trim().hashCode()); // TODO 真实 JWKS 校验
        return new ResolvedIdentity(Provider.APPLE, sub, "APPLE:" + sub, null, req.nickname(), req.avatar());
    }
}
