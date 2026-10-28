package ai.neargo.sharehub.portal.platform;

import ai.neargo.sharehub.agent.service.impl.MerchantProfileService;
import ai.neargo.sharehub.agent.service.impl.MerchantProfileService.ContactReq;
import ai.neargo.sharehub.agent.service.impl.MerchantProfileService.MerchantProfile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家门户「商家资料」（V175）。商家号取自会话，路径里没有它 —— 见 {@link MerchantProfileService}。
 */
@RestController
@RequestMapping("/api/agent/me")
public class MerchantProfileController {

    private final MerchantProfileService profiles;

    public MerchantProfileController(MerchantProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/profile")
    @PreAuthorize("@perm.can('agent:profile:read')")
    public MerchantProfile profile() {
        return profiles.mine();
    }

    /** 只改联系人（姓名、电话、邮箱）；名称、位置、行业由运营维护。 */
    @PostMapping("/contact")
    @PreAuthorize("@perm.can('agent:profile:update')")
    public MerchantProfile updateContact(@RequestBody ContactReq req) {
        return profiles.updateContact(req);
    }
}
