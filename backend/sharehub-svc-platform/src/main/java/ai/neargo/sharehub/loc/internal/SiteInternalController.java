package ai.neargo.sharehub.loc.internal;

import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@link SiteQueryPort} 的服务端（`/internal/**`，仅服务间调用，不对外暴露）。
 *
 * <p><b>它与远程实现是一对</b>：`RemotePorts.RemoteSiteQuery` 调这里。
 * 单体形态下这个端点也存在但没人调 —— 调用方直接走本地实现，省掉一次网络往返。
 *
 * <p><b>控制器住在 svc 而非 portal</b>：`/internal` 是**服务自身的对外契约**，
 * 拆分后必须随服务走；而 `portal` 是 BFF 层，按端点前缀分派给各 app。
 */
@RestController
public class SiteInternalController {

    private final SiteQueryPort siteQuery;

    public SiteInternalController(SiteQueryPort siteQuery) {
        this.siteQuery = siteQuery;
    }

    /** 批量取站点摘要。**用 POST 因为编号可能几百个** —— 塞 query string 会超长度限制。 */
    @PostMapping("/internal/platform/sites/briefs")
    public List<SiteBrief> briefs(@RequestBody List<String> siteNos) {
        return siteQuery.briefsByNos(siteNos);
    }
}
