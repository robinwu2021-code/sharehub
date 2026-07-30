package ai.neargo.powerbank.portal.mp;

import ai.neargo.powerbank.auth.ConsumerContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * C 端受保护业务端点样例（{@code /mp/**} 非 auth，需 CONSUMER 会话）。
 * 演示**属主鉴权**：只经 {@link ConsumerContext} 取自己的 c_user_no / 断言属主，业务不碰 SecurityContext。
 */
@RestController
public class MpController {

    /** 我的资料：证明 C 端会话与 ConsumerContext 生效。 */
    @GetMapping("/mp/user/profile")
    public Map<String, Object> profile() {
        var u = ConsumerContext.require();
        return Map.of("cUserNo", u.userNo(), "tenantNo", u.tenantId(), "nickname", u.username());
    }

    /** 属主鉴权样例：查看某用户订单 —— 非本人 → 403（防横向越权 IDOR）。 */
    @GetMapping("/mp/orders/{ownerNo}")
    public Map<String, Object> orderOf(@PathVariable String ownerNo) {
        ConsumerContext.assertOwner(ownerNo);
        return Map.of("owner", ownerNo, "viewer", ConsumerContext.userNo());
    }
}
