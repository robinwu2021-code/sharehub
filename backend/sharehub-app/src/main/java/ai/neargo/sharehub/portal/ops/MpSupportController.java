package ai.neargo.sharehub.portal.ops;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.ConsumerContext;
import ai.neargo.sharehub.cs.dto.CsDtos.CsTicketVO;
import ai.neargo.sharehub.cs.dto.CsDtos.ReportReq;
import ai.neargo.sharehub.cs.dto.CsDtos.ReportResultVO;
import ai.neargo.sharehub.cs.service.CsTicketService;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ClaimableCouponVO;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.NoticeVO;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.UserCouponVO;
import ai.neargo.sharehub.user.marketing.service.NoticeService;
import ai.neargo.sharehub.user.marketing.service.UserCouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * C 端公告 / 券包 / 自助报障（[api/README §8.5 §8.6]）。
 *
 * <p><b>没有 {@code @PreAuthorize}</b>：C 端无 RBAC，只有**属主鉴权**。
 * 一切属主标识取自 {@link ConsumerContext}（即会话），**绝不从请求参数或请求体读 cUserNo**
 * —— 那等于把横向越权（IDOR）的钥匙交给调用方。
 *
 * <p>{@code GET /mp/notice} 是全站唯一无需登录也有意义的端点（首页公告条），
 * 是否放行由 SecurityConfig 的白名单决定，本类不做假设。
 */
@RestController
public class MpSupportController {

    private final NoticeService noticeService;
    private final UserCouponService userCouponService;
    private final CsTicketService ticketService;

    public MpSupportController(NoticeService noticeService,
                               UserCouponService userCouponService,
                               CsTicketService ticketService) {
        this.noticeService = noticeService;
        this.userCouponService = userCouponService;
        this.ticketService = ticketService;
    }

    /**
     * 运营公告（首页公告条 + 公告页）。只返回 PUBLISHED 且在生效期内的，置顶优先。
     * 三语三列全量下发，由客户端按当前语种取 {@code title/titleEn/titleAr}。
     */
    @GetMapping("/mp/notice")
    public List<NoticeVO> notices(@RequestParam(required = false) Integer limit) {
        return noticeService.visibleNotices(limit);
    }

    /** 我的券包（**已领到手的券实例**，不含可领模板；后者见下面的 claimable）。属主恒为当前会话用户。 */
    @GetMapping("/mp/user/coupons")
    public PageResult<UserCouponVO> myCoupons(@RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) String status) {
        return userCouponService.page(page, size, ConsumerContext.userNo(), status);
    }

    /**
     * 领券中心 —— 当前可领的券模板。
     *
     * <p>这个接口补的是一个**闭合不了的环**：上面的 {@code /mp/user/coupons} 返的是
     * 用户已有的券实例，从里面取不到任何可领的模板号，于是下面的 claim 无从发起。
     * 文档（[C端功能清单 C-CP-01]）把「领券中心」和「我的券包」都指到了同一个端点上，
     * 但那个端点只能回答后者。
     */
    @GetMapping("/mp/user/coupons/claimable")
    public List<ClaimableCouponVO> claimableCoupons() {
        return userCouponService.claimable(ConsumerContext.userNo());
    }

    /**
     * 领券。路径上的 {@code couponNo} 是领券中心的券模板号（沿用前端命名）——
     * 即 {@link ClaimableCouponVO#tplNo()}，**不是**券包里那个 {@code CP…} 券实例号。
     * 幂等：已领过则返回已有那张，不发第二张。
     */
    @PostMapping("/mp/user/coupons/{couponNo}/claim")
    public UserCouponVO claim(@PathVariable String couponNo) {
        return userCouponService.claim(ConsumerContext.userNo(), couponNo);
    }

    /**
     * 自助报障 —— 落 {@code cs_ticket}（C 端诉求的**唯一受理单**，[api/README §6A.2]），
     * 随即按 {@code md_problem.suggested_action} 字典分流到工单 / 退款 / 人工会话。
     * 返回体带上 {@code suggestedAction} 与三个去向单号，C 端据此直接跳转对应进度页。
     */
    @PostMapping("/mp/user/report")
    public ReportResultVO report(@RequestBody ReportReq body) {
        return ticketService.report(ConsumerContext.userNo(), body);
    }

    /** 我的报障列表。 */
    @GetMapping("/mp/user/reports")
    public PageResult<CsTicketVO> myReports(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String status) {
        return ticketService.pageByUser(page, size, ConsumerContext.userNo(), status);
    }

    /**
     * 报障进度 / 结果（联动工单与退款状态）。
     * 他人单号一律当作不存在返回 {@code null} —— 用 403 区分会泄露单号存在性。
     */
    @GetMapping("/mp/user/reports/{reportNo}")
    public CsTicketVO myReport(@PathVariable String reportNo) {
        return ticketService.getForUser(ConsumerContext.userNo(), reportNo);
    }
}
