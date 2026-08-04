package ai.neargo.sharehub.user.consumer;

import org.springframework.stereotype.Component;

/**
 * 微信小程序登录：{@code wx.login} 的 jsCode → code2session 换 openid/unionid。
 * <p>MVP stub：openid 由 jsCode 派生；真实实现调微信 {@code code2session}（appid/secret 走租户配置）。
 */
@Component
public class WechatMiniappStrategy implements ConsumerLoginStrategy {

    @Override
    public String grantType() {
        return "wechat_miniapp";
    }

    @Override
    public ResolvedIdentity authenticate(ConsumerLoginReq req) {
        if (req.jsCode() == null || req.jsCode().isBlank()) {
            throw new IllegalArgumentException("jsCode 为空");
        }
        String openid = "mp_" + req.jsCode().trim();                 // TODO 真实 code2session
        String union = (req.unionid() != null && !req.unionid().isBlank())
                ? req.unionid().trim() : "WXMP:" + openid;           // unionid 归并（跨小程序/公众号）
        return new ResolvedIdentity(Provider.WECHAT_MP, openid, union, null, req.nickname(), req.avatar());
    }
}
