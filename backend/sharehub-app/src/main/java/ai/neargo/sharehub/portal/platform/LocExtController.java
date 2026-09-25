package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.Lead;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.OnboardingReviewReq;
import ai.neargo.sharehub.report.dto.ReportDtos.SiteAnalysis;
import ai.neargo.sharehub.loc.dto.SiteDtos.FunnelStage;
import ai.neargo.sharehub.loc.dto.SiteDtos.LifecycleRow;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.VenueOnboarding;
import ai.neargo.sharehub.loc.ext.entity.LocLead;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.LeadConversion;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.LeadConvertReq;
import ai.neargo.sharehub.loc.ext.service.LeadOpsService;
import ai.neargo.sharehub.loc.ext.service.LeadService;
import ai.neargo.sharehub.report.service.SiteAnalysisService;
import ai.neargo.sharehub.loc.service.SiteService;
import ai.neargo.sharehub.loc.ext.service.VenueOnboardingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 场地域扩展端点（[api/README §3.4]）：BD 拓展 CRM / 门店 Onboarding / 门店生命周期 / 站点坪效。
 *
 * <p>与 {@link OpsController} 同为 {@code /api/ops} 前缀但**子路径不重叠**
 * （那边是 sites/locations/venues/contracts）。拆开是因为这四叶要么走通用 CRUD、
 * 要么有审核/留痕副作用，与 {@code LocService} 的四张主表不同源。
 * 新增控制器前务必核对已占用路径，重复映射会让 Spring 启动直接失败。
 *
 * <p>控制器只做路由 + 鉴权 + 调 service，**不写业务、不碰 mapper**。
 */
@RestController
@RequestMapping("/api/ops")
public class LocExtController {

    private final LeadService leadService;
    private final LeadOpsService leadOps;
    private final ai.neargo.sharehub.loc.ext.service.LeadFollowService leadFollowService;
    private final VenueOnboardingService onboardingService;
    private final SiteService siteService;
    private final SiteAnalysisService siteAnalysisService;

    public LocExtController(LeadService leadService,
                            VenueOnboardingService onboardingService,
                            SiteService siteService,
                            SiteAnalysisService siteAnalysisService,
                            ai.neargo.sharehub.loc.ext.service.LeadFollowService leadFollowService,
                            LeadOpsService leadOps) {
        this.leadFollowService = leadFollowService;
        this.leadOps = leadOps;
        this.leadService = leadService;
        this.onboardingService = onboardingService;
        this.siteService = siteService;
        this.siteAnalysisService = siteAnalysisService;
    }

    // —— BD 拓展 CRM（菜单叶：站点与点位 › BD 拓展 CRM）——

    @GetMapping("/leads")
    @PreAuthorize("@perm.can('location:crm:read')")
    public PageResult<Lead> leads(@RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String stage,
                                  @RequestParam(required = false) String owner,
                                  @RequestParam(required = false) Boolean inPool) {
        return leadService.page(page, size, keyword, Map.of("stage", nz(stage), "owner", nz(owner),
                "inPool", inPool == null ? "" : inPool ? "1" : "0"));
    }

    /** 从公共线索池认领（超 M 天无跟进的商机会被回收进池）。 */
    @PostMapping("/leads/{leadNo}/claim")
    @PreAuthorize("@perm.can('location:crm:update')")
    public Lead claimLead(@PathVariable String leadNo) {
        return leadOps.claim(leadNo);
    }

    /** 签约转化：场地方 + 站点（筹备中）+ 带谈判条款的合同草稿，一次生成，不重复录入。 */
    @PostMapping("/leads/{leadNo}/convert")
    @PreAuthorize("@perm.can('location:crm:update')")
    public LeadConversion convertLead(@PathVariable String leadNo, @RequestBody(required = false) LeadConvertReq r) {
        return leadOps.convert(leadNo, r);
    }

    @GetMapping("/leads/{leadNo}")
    @PreAuthorize("@perm.can('location:crm:read')")
    public Lead lead(@PathVariable String leadNo) {
        return leadService.get(leadNo);
    }

    @PostMapping("/leads")
    @PreAuthorize("@perm.can('location:crm:update')")
    public Lead createLead(@RequestBody LocLead body) {
        return leadService.save(body);
    }

    @PostMapping("/leads/{leadNo}")
    @PreAuthorize("@perm.can('location:crm:update')")
    public Lead updateLead(@PathVariable String leadNo, @RequestBody LocLead body) {
        body.setLeadNo(leadNo); // 路径为准，忽略 body 里的键，防越权改他人商机
        return leadService.save(body);
    }

    // —— 门店 Onboarding（菜单叶：站点与点位 › 门店 Onboarding）——

    @GetMapping("/venue-onboardings")
    @PreAuthorize("@perm.can('location:venue:read')")
    public PageResult<VenueOnboarding> onboardings(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String status) {
        return onboardingService.page(page, size, keyword, status);
    }

    @GetMapping("/venue-onboardings/{onboardingNo}")
    @PreAuthorize("@perm.can('location:venue:read')")
    public VenueOnboarding onboarding(@PathVariable String onboardingNo) {
        return onboardingService.get(onboardingNo);
    }

    /** 审核。通过则由 service 建 {@code loc_venue} 并回填 {@code venueNo}（[api/README §3.4]）。 */
    /**
     * 新建 / 修改进件（运营代录）。自助提交渠道未开之前，
     * 运营得能替客户把单子录进来 —— 否则这个菜单在真后端下只能看不能用。
     */
    @PostMapping("/venue-onboardings")
    @PreAuthorize("@perm.can('location:venue:create')")
    public VenueOnboarding createOnboarding(@RequestBody VenueOnboarding body) {
        return onboardingService.save(null, body);
    }

    @PostMapping("/venue-onboardings/{onboardingNo}")
    @PreAuthorize("@perm.can('location:venue:update')")
    public VenueOnboarding updateOnboarding(@PathVariable String onboardingNo,
                                            @RequestBody VenueOnboarding body) {
        return onboardingService.save(onboardingNo, body);
    }

    @PostMapping("/venue-onboardings/{onboardingNo}/review")
    @PreAuthorize("@perm.can('location:venue:create')")
    public VenueOnboarding review(@PathVariable String onboardingNo,
                                  @RequestBody OnboardingReviewReq body) {
        return onboardingService.review(onboardingNo, body);
    }

    // —— 门店生命周期（菜单叶：站点与点位 › 门店生命周期）——

    /**
     * 只读漏斗（2026-09-25：站点状态与门店生命周期合并）：签约前是商机阶段，签约后是站点状态。
     * 原「阶段流转」端点已删除 —— 阶段不再可任选，站点状态只经 SiteController 的动作改。
     */
    @GetMapping("/site-lifecycles")
    @PreAuthorize("@perm.can('location:venue:read')")
    public PageResult<LifecycleRow> siteLifecycles(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String phase) {
        return siteService.lifecycle(page, size, keyword, phase);
    }

    // 旧的 POST /site-lifecycles/{siteNo}/stage（409 桩）已删：运营端已改用只读漏斗（ops-web 346c631）。

    @GetMapping("/site-lifecycles/funnel")
    @PreAuthorize("@perm.can('location:venue:read')")
    public List<FunnelStage> siteLifecycleFunnel() {
        return siteService.funnel();
    }

    // —— 站点坪效（菜单叶：站点与点位 › 站点坪效）——

    /** 读模型：{@code loc_site ⋈ ord_order} 聚合，不落表。当前为空实现，见 service 的 TODO。 */
    @GetMapping("/site-analysis")
    @PreAuthorize("@perm.can('location:analysis:read')")
    public PageResult<SiteAnalysis> siteAnalysis(@RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        return siteAnalysisService.page(page, size, keyword, from, to);
    }

    /** {@code Map.of} 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ───────────── 线索跟进 / 合同附件 ─────────────

    /** 线索跟进记录（append，只增不改）。 */
    @GetMapping("/leads/{leadNo}/follow-ups")
    @PreAuthorize("@perm.can('location:lead:read')")
    public Object leadFollowUps(@PathVariable String leadNo,
                                @RequestParam(required = false) Integer page,
                                @RequestParam(required = false) Integer size) {
        return leadFollowService.pageFollowUps(leadNo, page, size);
    }

    /**
     * 记一条跟进，**可同时推进线索阶段**（同事务）。
     *
     * <p>拆成「记跟进」+「改阶段」两个接口的话，两者会不一致 ——
     * 有跟进记录但阶段没动，或阶段跳了却查不到是谁推的。
     */
    @PostMapping("/leads/{leadNo}/follow-ups")
    @PreAuthorize("@perm.can('location:lead:update')")
    public Object addLeadFollowUp(@PathVariable String leadNo,
                                  @RequestBody ai.neargo.sharehub.loc.ext.dto.LocExtDtos2.LeadFollowUpReq req) {
        return leadFollowService.addFollowUp(leadNo, req);
    }

    // 合同附件的增删已迁入 ContractController（2026-09-25：接入文件服务，入参改为 fileNos）。
}
