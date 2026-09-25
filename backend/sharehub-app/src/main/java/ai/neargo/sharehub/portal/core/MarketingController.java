package ai.neargo.sharehub.portal.core;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.ad.dto.AdDtos.AdCampaignVO;
import ai.neargo.sharehub.user.ad.dto.AdDtos.AdDeliveryVO;
import ai.neargo.sharehub.user.ad.dto.AdDtos.AdSlotVO;
import ai.neargo.sharehub.user.ad.entity.AdCampaign;
import ai.neargo.sharehub.user.ad.entity.AdSlot;
import ai.neargo.sharehub.user.ad.service.AdCampaignService;
import ai.neargo.sharehub.user.ad.service.AdDeliveryService;
import ai.neargo.sharehub.user.ad.service.AdSlotService;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.CampaignVO;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.CouponTplReq;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.CouponTplVO;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.NoticeVO;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.PushMessageVO;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ReferralVO;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.UserCouponVO;
import ai.neargo.sharehub.user.marketing.entity.CouponTpl;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos;
import ai.neargo.sharehub.user.ad.dto.AdDtos;
import ai.neargo.sharehub.user.marketing.entity.MktCampaign;
import ai.neargo.sharehub.user.marketing.entity.MktNotice;
import ai.neargo.sharehub.user.marketing.entity.MktPush;
import ai.neargo.sharehub.user.marketing.service.CampaignService;
import ai.neargo.sharehub.user.marketing.service.CouponTplService;
import ai.neargo.sharehub.user.marketing.service.NoticeService;
import ai.neargo.sharehub.user.marketing.service.PushService;
import ai.neargo.sharehub.user.marketing.service.ReferralService;
import ai.neargo.sharehub.user.marketing.service.UserCouponService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 营销与广告的运营端端点（[api/README §5.2 / §3.8 / §3.9]）。
 *
 * <p><b>没有类级 {@code @RequestMapping}</b>：本控制器横跨两个前缀 —— 券/活动/推送/裂变/广告活动
 * 在 {@code /api/user}，公告与广告位在 {@code /api/ops}。这不是随意摆放：
 * <ul>
 *   <li>公告落 {@code /api/ops/marketing/notices} 是**沿用前端现状**（[api/README §3.8] 备注），
 *       文档 §十三 把「是否迁到 /api/user/notices」列为待确认项，迁移需前后端同改；</li>
 *   <li>广告位落 {@code /api/ops/ad-slots} 是因为它挂在机柜上，本质是设备资产的一个面（[§3.9]）。</li>
 * </ul>
 *
 * <p><b>{@code GET /api/user/coupons} 不在这里</b>：该路径已被 {@link UserController} 占用，
 * 重复映射会让 Spring 启动直接失败。本控制器只补 POST。
 */
@RestController
public class MarketingController {

    private final CouponTplService couponTplService;
    private final UserCouponService userCouponService;
    private final CampaignService campaignService;
    private final PushService pushService;
    private final ReferralService referralService;
    private final NoticeService noticeService;
    private final AdSlotService adSlotService;
    private final AdCampaignService adCampaignService;
    private final AdDeliveryService adDeliveryService;

    public MarketingController(CouponTplService couponTplService,
                               UserCouponService userCouponService,
                               CampaignService campaignService,
                               PushService pushService,
                               ReferralService referralService,
                               NoticeService noticeService,
                               AdSlotService adSlotService,
                               AdCampaignService adCampaignService,
                               AdDeliveryService adDeliveryService) {
        this.couponTplService = couponTplService;
        this.userCouponService = userCouponService;
        this.campaignService = campaignService;
        this.pushService = pushService;
        this.referralService = referralService;
        this.noticeService = noticeService;
        this.adSlotService = adSlotService;
        this.adCampaignService = adCampaignService;
        this.adDeliveryService = adDeliveryService;
    }

    // ——————————————— 优惠券（菜单叶：营销管理 › 优惠券）———————————————

    @PostMapping("/api/user/coupons")
    @PreAuthorize("@perm.can('marketing:coupon:create')")
    public CouponTplVO createCoupon(@RequestBody CouponTplReq body) {
        return couponTplService.save(body.toEntity());
    }

    @PostMapping("/api/user/coupons/{couponNo}")
    @PreAuthorize("@perm.can('marketing:coupon:create')")
    public CouponTplVO updateCoupon(@PathVariable String couponNo, @RequestBody CouponTplReq body) {
        CouponTpl e = body.toEntity();
        e.setTplNo(couponNo); // 路径为准，忽略 body 里的键，防越权改他单
        return couponTplService.save(e);
    }

    /** 定向发券。{@code cUserNos} 是收券人列表，{@code expireAt} 可空（走模板 validRule）。 */
    @PostMapping("/api/user/coupons/{tplNo}/issue")
    @PreAuthorize("@perm.can('marketing:coupon:issue')")
    public List<UserCouponVO> issueCoupon(@PathVariable String tplNo, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> cUserNos = (List<String>) body.get("cUserNos");
        Object expireAt = body.get("expireAt");
        return userCouponService.issue(tplNo, cUserNos, expireAt == null ? null : String.valueOf(expireAt));
    }

    // ——————————————— 活动（菜单叶：营销管理 › 活动）———————————————

    @GetMapping("/api/user/campaigns")
    @PreAuthorize("@perm.can('marketing:campaign:read')")
    public PageResult<CampaignVO> campaigns(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String kind,
                                            @RequestParam(required = false) String status) {
        return campaignService.page(page, size, keyword, Map.of("kind", nz(kind), "status", nz(status)));
    }

    @PostMapping("/api/user/campaigns")
    @PreAuthorize("@perm.can('marketing:campaign:update')")
    public CampaignVO createCampaign(@RequestBody MarketingDtos.CampaignReq body) {
        return campaignService.save(body.toEntity());
    }

    @PostMapping("/api/user/campaigns/{campaignNo}")
    @PreAuthorize("@perm.can('marketing:campaign:update')")
    public CampaignVO updateCampaign(@PathVariable String campaignNo,
                                     @RequestBody MarketingDtos.CampaignReq body) {
        MktCampaign e = body.toEntity();
        e.setCampaignNo(campaignNo);
        return campaignService.save(e);
    }

    // ——————————————— 推送触达（菜单叶：营销管理 › 推送触达）———————————————

    @GetMapping("/api/user/push-messages")
    @PreAuthorize("@perm.can('marketing:push:send')")
    public PageResult<PushMessageVO> pushMessages(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String channel,
                                                  @RequestParam(required = false) String status) {
        return pushService.page(page, size, keyword, Map.of("channel", nz(channel), "status", nz(status)));
    }

    @PostMapping("/api/user/push-messages")
    @PreAuthorize("@perm.can('marketing:push:send')")
    public PushMessageVO createPush(@RequestBody MarketingDtos.PushReq body) {
        return pushService.save(body.toEntity());
    }

    @PostMapping("/api/user/push-messages/{pushNo}")
    @PreAuthorize("@perm.can('marketing:push:send')")
    public PushMessageVO updatePush(@PathVariable String pushNo,
                                    @RequestBody MarketingDtos.PushReq body) {
        MktPush e = body.toEntity();
        e.setPushNo(pushNo);
        return pushService.save(e);
    }

    // ——————————————— 邀请裂变（只读，记录由 C 端注册链路自动产生）———————————————

    @GetMapping("/api/user/referrals")
    @PreAuthorize("@perm.can('marketing:campaign:read')")
    public PageResult<ReferralVO> referrals(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String inviterNo,
                                            @RequestParam(required = false) String status) {
        return referralService.page(page, size, keyword, inviterNo, status);
    }

    // ——————————————— 广告活动 / 投放曝光（菜单叶：广告经营）———————————————

    @GetMapping("/api/user/ad-campaigns")
    @PreAuthorize("@perm.can('marketing:ad:read')")
    public PageResult<AdCampaignVO> adCampaigns(@RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String advertiserNo,
                                                @RequestParam(required = false) String status) {
        return adCampaignService.page(page, size, keyword,
                Map.of("advertiserNo", nz(advertiserNo), "status", nz(status)));
    }

    @PostMapping("/api/user/ad-campaigns")
    @PreAuthorize("@perm.can('marketing:ad:update')")
    public AdCampaignVO createAdCampaign(@RequestBody AdDtos.AdCampaignReq body) {
        return adCampaignService.save(body.toEntity());
    }

    @PostMapping("/api/user/ad-campaigns/{adNo}")
    @PreAuthorize("@perm.can('marketing:ad:update')")
    public AdCampaignVO updateAdCampaign(@PathVariable String adNo,
                                         @RequestBody AdDtos.AdCampaignReq body) {
        AdCampaign e = body.toEntity();
        e.setAdNo(adNo);
        return adCampaignService.save(e);
    }

    /** 投放与曝光统计。只读 —— 曝光数是广告主的结算依据，不提供人工增改口。 */
    @GetMapping("/api/user/ad-deliveries")
    @PreAuthorize("@perm.can('marketing:ad:read')")
    public PageResult<AdDeliveryVO> adDeliveries(@RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size,
                                                 @RequestParam(required = false) String adNo,
                                                 @RequestParam(required = false) String slotNo,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        return adDeliveryService.page(page, size, adNo, slotNo, from, to);
    }

    // ——————————————— 广告位（挂机柜，故归 /api/ops · [§3.9]）———————————————

    @GetMapping("/api/ops/ad-slots")
    @PreAuthorize("@perm.can('marketing:ad:read')")
    public PageResult<AdSlotVO> adSlots(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String cabinetNo,
                                        @RequestParam(required = false) String position,
                                        @RequestParam(required = false) String status) {
        return adSlotService.page(page, size, keyword,
                Map.of("cabinetNo", nz(cabinetNo), "position", nz(position), "status", nz(status)));
    }

    @PostMapping("/api/ops/ad-slots")
    @PreAuthorize("@perm.can('marketing:ad:update')")
    public AdSlotVO createAdSlot(@RequestBody AdSlot body) {
        return adSlotService.save(body);
    }

    @PostMapping("/api/ops/ad-slots/{slotNo}")
    @PreAuthorize("@perm.can('marketing:ad:update')")
    public AdSlotVO updateAdSlot(@PathVariable String slotNo, @RequestBody AdSlot body) {
        body.setSlotNo(slotNo);
        return adSlotService.save(body);
    }

    // ——————————————— 公告（C 端首页公告条的发布口 · [§3.8]）———————————————

    @GetMapping("/api/ops/marketing/notices")
    @PreAuthorize("@perm.can('marketing:notice:read')")
    public PageResult<NoticeVO> notices(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(required = false) String status) {
        return noticeService.page(page, size, keyword, Map.of("type", nz(type), "status", nz(status)));
    }

    @PostMapping("/api/ops/marketing/notices")
    @PreAuthorize("@perm.can('marketing:notice:update')")
    public NoticeVO createNotice(@RequestBody MarketingDtos.NoticeReq body) {
        return noticeService.save(body.toEntity());
    }

    @PostMapping("/api/ops/marketing/notices/{noticeNo}")
    @PreAuthorize("@perm.can('marketing:notice:update')")
    public NoticeVO updateNotice(@PathVariable String noticeNo,
                                 @RequestBody MarketingDtos.NoticeReq body) {
        MktNotice e = body.toEntity();
        e.setNoticeNo(noticeNo);
        return noticeService.save(e);
    }

    /** {@code Map.of} 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 归档Notice。**不是删除** —— 行仍在，勾「显示已归档」可见，可 unarchive 恢复。
     *
     * <p>归档语义统一在 {@code AbstractCrudService}：盖 {@code archivedAt} 时间戳。
     * 时间戳而非布尔位，因为「什么时候归档的」本身是审计信息。
     */
    @PostMapping("/api/ops/marketing/notices/{no}/archive")
    @PreAuthorize("@perm.can('marketing:notice:update')")
    public Object archiveNotice(@PathVariable String no) {
        return noticeService.archive(no);
    }

    /** 取消归档Notice：清空时间戳，回到默认列表。 */
    @PostMapping("/api/ops/marketing/notices/{no}/unarchive")
    @PreAuthorize("@perm.can('marketing:notice:update')")
    public Object unarchiveNotice(@PathVariable String no) {
        return noticeService.unarchive(no);
    }

    /**
     * 归档Coupon。**不是删除** —— 行仍在，勾「显示已归档」可见，可 unarchive 恢复。
     *
     * <p>归档语义统一在 {@code AbstractCrudService}：盖 {@code archivedAt} 时间戳。
     * 时间戳而非布尔位，因为「什么时候归档的」本身是审计信息。
     */
    @PostMapping("/api/user/coupons/{no}/archive")
    @PreAuthorize("@perm.can('marketing:coupon:update')")
    public Object archiveCoupon(@PathVariable String no) {
        return couponTplService.archive(no);
    }

    /** 取消归档Coupon：清空时间戳，回到默认列表。 */
    @PostMapping("/api/user/coupons/{no}/unarchive")
    @PreAuthorize("@perm.can('marketing:coupon:update')")
    public Object unarchiveCoupon(@PathVariable String no) {
        return couponTplService.unarchive(no);
    }

    // ───────────────── 状态迁移 / 发送 / 记录查询 ─────────────────

    /**
     * 活动状态迁移（start/pause/end）。
     *
     * <p><b>合法迁移由 {@link ai.neargo.sharehub.user.marketing.CampaignStateMachine} 判定</b> ——
     * 与前端 {@code CAMPAIGN_TRANSITIONS} 是同一张表。页面藏按钮不等于服务端会拒绝，
     * 直接调接口照样能把已结束的活动点活，所以服务端必须自己判。
     */
    @PostMapping("/api/user/campaigns/{campaignNo}/{action}")
    @PreAuthorize("@perm.can('marketing:campaign:update')")
    public Object transitionCampaign(@PathVariable String campaignNo, @PathVariable String action) {
        return campaignService.transition(campaignNo, action);
    }

    /** 广告投放状态迁移，语义同活动。 */
    @PostMapping("/api/user/ad-campaigns/{adNo}/{action}")
    @PreAuthorize("@perm.can('marketing:ad:update')")
    public Object transitionAdCampaign(@PathVariable String adNo, @PathVariable String action) {
        return adCampaignService.transition(adNo, action);
    }

    /**
     * 发送推送。**必带幂等键** —— 推送是真的推到用户手机上，双击不该推两次。
     *
     * <p>一个端点两种语义（沿用前端既有契约 {@code PushSendPayload}）：
     * {@code scheduledAt} 有值 = 排期（转 SCHEDULED，到点由扫描发出）；空 = 立即发送。
     * 不拆成两个端点，是因为前端本来就是一个「发送」按钮 + 一个可选时间。
     */
    @PostMapping("/api/user/push-messages/{pushNo}/send")
    @PreAuthorize("@perm.can('marketing:push:update')")
    public Object sendPushMessage(@PathVariable String pushNo,
                                  @RequestBody(required = false) java.util.Map<String, Object> body) {
        String key = str(body, "idempotencyKey");
        String at = str(body, "scheduledAt");
        String by = str(body, "operatorName");
        return (at == null || at.isBlank())
                ? pushService.send(pushNo, key, by)
                : pushService.schedule(pushNo, at, by);
    }

    /**
     * 推送收尾：SENDING → SENT，落触达统计。
     *
     * <p>真实触达由推送通道回执驱动（尚未接入），在那之前由运营/联调显式收尾 ——
     * 让单子停在 SENDING 也比**假装已送达**强：后者会让"成功 N 人"是编的。
     */
    @PostMapping("/api/user/push-messages/{pushNo}/finish")
    @PreAuthorize("@perm.can('marketing:push:update')")
    public Object finishPushMessage(@PathVariable String pushNo,
                                    @RequestBody(required = false) java.util.Map<String, Object> body) {
        return pushService.finish(pushNo, intOf(body, "targetCount"), intOf(body, "successCount"));
    }

    /**
     * 扫描到点的排期推送并发出。
     *
     * <p>**这是临时入口**：本该由共用调度器按 cron 回调（v4/07 的 JobHandler），
     * 但那要等 ai-shop 的任务服务做完多系统改造 —— 目前 backend/pom.xml 的 enforcer
     * 明令禁止依赖 {@code ai.neargo.shop:*}，接口类根本引不进来。
     * 调度器就绪后把 {@link PushService#sweepDue} 挂成 JobHandler 即可，业务代码不动。
     */
    @PostMapping("/api/user/push-messages/sweep-due")
    @PreAuthorize("@perm.can('marketing:push:update')")
    public Object sweepDuePushMessages(@RequestBody(required = false) java.util.Map<String, Object> body) {
        return java.util.Map.of("handled", pushService.sweepDue(str(body, "now")));
    }

    private static String str(java.util.Map<String, Object> body, String k) {
        Object v = body == null ? null : body.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static Integer intOf(java.util.Map<String, Object> body, String k) {
        Object v = body == null ? null : body.get(k);
        return v == null ? null : Integer.valueOf(String.valueOf(v));
    }

    /** 券发放记录（append，只增不改）。 */
    @GetMapping("/api/user/coupon-issue-records")
    @PreAuthorize("@perm.can('marketing:coupon:read')")
    public Object couponIssueRecords(@RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size,
                                     @RequestParam(required = false) String couponNo) {
        return userCouponService.pageIssueRecords(page, size, couponNo);
    }

    /** 新建 / 修改裂变规则。此前只有 GET —— 前端「新增/编辑」在真后端下必 404。 */
    @PostMapping("/api/user/referral-rules")
    @PreAuthorize("@perm.can('marketing:referral:update')")
    public Object createReferralRule(
            @RequestBody ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ReferralRuleVO body) {
        return referralService.saveRule(null, body);   // 新建一律服务端取号
    }

    @PostMapping("/api/user/referral-rules/{ruleNo}")
    @PreAuthorize("@perm.can('marketing:referral:update')")
    public Object updateReferralRule(@PathVariable String ruleNo,
            @RequestBody ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ReferralRuleVO body) {
        return referralService.saveRule(ruleNo, body); // 路径为准
    }

    /** 裂变规则列表。 */
    @GetMapping("/api/user/referral-rules")
    @PreAuthorize("@perm.can('marketing:referral:read')")
    public Object referralRules(@RequestParam(required = false) Integer page,
                                @RequestParam(required = false) Integer size) {
        return referralService.pageRules(page, size);
    }
}
