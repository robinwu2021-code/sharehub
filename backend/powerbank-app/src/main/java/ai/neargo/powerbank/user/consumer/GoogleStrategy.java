package ai.neargo.powerbank.user.consumer;

import org.springframework.stereotype.Component;

/**
 * Google 登录（App Android / Web）。
 * <p>MVP stub：sub 由 idToken 派生；真实实现校验 Google JWKS（iss/aud/exp）取 sub。
 */
@Component
public class GoogleStrategy implements ConsumerLoginStrategy {

    @Override
    public String grantType() {
        return "google";
    }

    @Override
    public ResolvedIdentity authenticate(ConsumerLoginReq req) {
        if (req.idToken() == null || req.idToken().isBlank()) {
            throw new IllegalArgumentException("idToken 为空");
        }
        String sub = "google_" + Integer.toHexString(req.idToken().trim().hashCode());  // TODO 真实 JWKS 校验
        return new ResolvedIdentity(Provider.GOOGLE, sub, "GOOGLE:" + sub, null, req.nickname(), req.avatar());
    }
}
