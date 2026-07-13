package ai.neargo.powerbank.user.consumer;

import org.springframework.stereotype.Component;

/**
 * H5 微信网页授权（公众号 OAuth2）：code → openid/unionid。
 * <p>MVP stub：openid 由 code 派生；真实实现调微信 {@code sns/oauth2/access_token}，并校验 state 防重放。
 * 与小程序共 unionid 时归并同一 usr_user。
 */
@Component
public class WechatOauthStrategy implements ConsumerLoginStrategy {

    @Override
    public String grantType() {
        return "wechat_oauth";
    }

    @Override
    public ResolvedIdentity authenticate(ConsumerLoginReq req) {
        if (req.code() == null || req.code().isBlank()) {
            throw new IllegalArgumentException("code 为空");
        }
        String openid = "oa_" + req.code().trim();                   // TODO 真实网页授权换取
        String union = (req.unionid() != null && !req.unionid().isBlank())
                ? req.unionid().trim() : "WXOA:" + openid;
        return new ResolvedIdentity(Provider.WECHAT_OA, openid, union, null, req.nickname(), req.avatar());
    }
}
