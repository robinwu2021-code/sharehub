package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.Lead;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.OnboardingReviewReq;
import ai.neargo.sharehub.report.dto.ReportDtos.SiteAnalysis;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.SiteLifecycle;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.StageChangeReq;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.VenueOnboarding;
import ai.neargo.sharehub.loc.ext.entity.LocLead;
import ai.neargo.sharehub.loc.ext.service.LeadService;
import ai.neargo.sharehub.report.service.SiteAnalysisService;
import ai.neargo.sharehub.loc.ext.service.SiteLifecycleService;
import ai.neargo.sharehub.loc.ext.service.VenueOnboardingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    private final ai.neargo.sharehub.loc.ext.service.LeadFollowService leadFollowService;
    private final VenueOnboardingService onboardingService;
    private final SiteLifecycleService lifecycleService;
    private final SiteAnalysisService siteAnalysisService;

    public LocExtController(LeadService leadService,
                            VenueOnboardingService onboardingService,
                            SiteLifecycleService lifecycleService,
                            SiteAnalysisService siteAnalysisService,
                            ai.neargo.sharehub.loc.ext.service.LeadFollowService leadFollowService) {
        this.leadFollowService = leadFollowService;
        this.leadService = leadService;
        this.onboardingService = onboardingService;
        this.lifecycleService = lifecycleService;
        this.siteAnalysisService = siteAnalysisService;
    }

    // —— BD 拓展 CRM（菜单叶：站点与点位 › BD 拓展 CRM）——

    @GetMapping("/leads")
    @PreAuthorize("@perm.can('location:crm:read')")
    public PageResult<Lead> leads(@RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String stage,
                                  @RequestParam(required = false) String owner) {
        return leadService.page(page, size, keyword, Map.of("stage", nz(stage), "owner", nz(owner)));
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

    @GetMapping("/site-lifecycles")
    @PreAuthorize("@perm.can('location:venue:read')")
    public PageResult<SiteLifecycle> siteLifecycles(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String stage) {
        return lifecycleService.page(page, size, keyword, stage);
    }

    /** 阶段流转，留痕到 {@code loc_site_lifecycle_log}。 */
    @PostMapping("/site-lifecycles/{siteNo}/stage")
    @PreAuthorize("@perm.can('location:venue:update')")
    public SiteLifecycle changeStage(@PathVariable String siteNo, @RequestBody StageChangeReq body) {
        return lifecycleService.changeStage(siteNo, body);
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

    /** 添加合同附件元数据。**不接收字节流** —— 接对象存储前不编假地址。 */
    @PostMapping("/contracts/{contractNo}/attachments")
    @PreAuthorize("@perm.can('location:contract:update')")
    public Object addContractAttachment(@PathVariable String contractNo,
                                        @RequestBody java.util.Map<String, Object> body) {
        Object fn = body == null ? null : body.get("fileName");
        Object sz = body == null ? null : body.get("size");
        return leadFollowService.addAttachment(contractNo,
                fn == null ? null : String.valueOf(fn),
                sz == null ? 0L : Long.valueOf(String.valueOf(sz)));
    }

    /** 移除合同附件。软删 —— 「曾经有过一个附件后来被删了」本身是信息。 */
    @PostMapping("/contracts/{contractNo}/attachments/{attachNo}/remove")
    @PreAuthorize("@perm.can('location:contract:update')")
    public Object removeContractAttachment(@PathVariable String contractNo,
                                           @PathVariable String attachNo) {
        return java.util.Map.of("ok", leadFollowService.removeAttachment(contractNo, attachNo));
    }
}
