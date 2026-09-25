package ai.neargo.sharehub.portal.core;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.ConsumerContext;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.RechargePackageRow;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.RechargeResultVO;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletOverview;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletTxnRow;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.MembershipPlanVO;
import ai.neargo.sharehub.user.asset.service.MembershipService;
import ai.neargo.sharehub.user.asset.service.RechargePackageService;
import ai.neargo.sharehub.user.asset.service.RechargeService;
import ai.neargo.sharehub.user.asset.service.WalletService;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.InvoiceApplyReq;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.InvoiceItem;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.InvoiceTitleItem;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.LogoffItem;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.MessageItem;
import ai.neargo.sharehub.user.core.entity.UsrInvoice;
import ai.neargo.sharehub.user.core.entity.UsrInvoiceTitle;
import ai.neargo.sharehub.user.core.service.UserFavoriteService;
import ai.neargo.sharehub.user.core.service.UserInvoiceService;
import ai.neargo.sharehub.user.core.service.UserLogoffService;
import ai.neargo.sharehub.user.core.service.UserMessageService;
import ai.neargo.sharehub.user.core.service.UserQueryService;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.CUserRow;
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
 * C 端用户资产与个人中心（{@code /mp/user/**}，[api/README §8.4 / §8.7 / §8.8]）。
 *
 * <p><b>无 {@code @PreAuthorize}</b>：C 端没有 RBAC，只有<b>属主鉴权</b> ——
 * 所有查询的属主一律取自 {@link ConsumerContext}（服务端可信来源），
 * <b>绝不从路径或 body 读用户号</b>，否则就是横向越权（IDOR）。
 * 这与运营端 {@code /api/**} 的「Bearer + 权限码」是两套机制，不要混用。
 *
 * <p>个人资料读写（{@code /mp/user/profile}）已从演示类 MpController 移入本类（真表实现）。
 */
@RestController
@RequestMapping("/mp/user")
public class MpUserController {

    private final WalletService wallets;
    private final RechargePackageService packages;
    private final RechargeService recharges;
    private final UserFavoriteService favorites;
    private final UserMessageService messages;
    private final UserInvoiceService invoices;
    private final UserLogoffService logoff;
    private final UserQueryService userQuery;
    private final MembershipService memberships;

    public MpUserController(WalletService wallets, RechargePackageService packages,
                            RechargeService recharges,
                            UserFavoriteService favorites, UserMessageService messages,
                            UserInvoiceService invoices, UserLogoffService logoff,
                            UserQueryService userQuery, MembershipService memberships) {
        this.wallets = wallets;
        this.packages = packages;
        this.recharges = recharges;
        this.favorites = favorites;
        this.messages = messages;
        this.invoices = invoices;
        this.logoff = logoff;
        this.userQuery = userQuery;
        this.memberships = memberships;
    }

    // —— 钱包（C-WA-01 / C-WA-05 / C-WA-02）——

    @GetMapping("/wallet")
    public WalletOverview wallet() {
        return wallets.overviewOf(ConsumerContext.userNo());
    }

    /** 路径是 {@code /txns} 不是 {@code /transactions}（[api §十 冲突裁决 4]，以 c-app 现状为准）。 */
    @GetMapping("/wallet/txns")
    public PageResult<WalletTxnRow> walletTxns(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String type) {
        return wallets.txnsOf(ConsumerContext.userNo(), page, size, type);
    }

    /**
     * 可购充值套餐，按用户所在市场过滤。
     *
     * <p>{@code country} 暂由端上传（定位/注册地）；<b>TODO</b>：改为服务端按用户档案的
     * {@code region_id}/注册国推导，端上传参可被篡改以购买他国套餐（定价差异 = 套利面）。
     */
    @GetMapping("/recharge-packages")
    public List<RechargePackageRow> rechargePackages(@RequestParam(required = false) String country) {
        return packages.listForMarket(country);
    }

    /**
     * 按套餐充值（C-WA-02）。
     *
     * <p><b>只收 {@code packageNo}，不收金额</b> —— 金额由服务端按套餐算。
     * 收前端传的金额，「充 1 元到账 100」就是一次普通的改参数请求。
     *
     * <p>此前这条路是断的：c-app 的「充值」按钮打的是 {@code POST /mp/trade/pay}，
     * 而那个口在没有 orderNo 时直接抛「充值等无单支付待充值单流程接入」。
     * 前端又没有 catch，于是点下去**什么都不发生** —— 没提示，也没报错。
     */
    @PostMapping("/recharge")
    public RechargeResultVO recharge(@RequestBody Map<String, String> body) {
        String packageNo = body == null ? null : body.get("packageNo");
        return recharges.recharge(ConsumerContext.userNo(), packageNo);
    }

    // —— 收藏门店 ——
    //
    // 列表在 MpNearbyController（`GET /mp/user/favorites`）：收藏页渲染的是**门店卡片**，
    // 要把站点 × 机柜 × 报价组合起来，而那是找柜 BFF 的活。这里只留写侧。

    /** 收藏 / 取消收藏（切换）。返回切换后是否已收藏。 */
    @PostMapping("/favorites/{siteNo}")
    public boolean toggleFavorite(@PathVariable String siteNo) {
        return favorites.toggle(ConsumerContext.userNo(), siteNo);
    }

    // —— 站内消息（C-MS-03）——

    @GetMapping("/messages")
    public PageResult<MessageItem> messages(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String type,
                                            @RequestParam(required = false) Boolean read) {
        return messages.pageByOwner(ConsumerContext.userNo(), page, size, type, read);
    }

    @PostMapping("/messages/{messageNo}/read")
    public MessageItem markRead(@PathVariable String messageNo) {
        return messages.markRead(ConsumerContext.userNo(), messageNo);
    }

    // —— 发票（C-IV-01/02/03）——

    @GetMapping("/invoice-titles")
    public PageResult<InvoiceTitleItem> invoiceTitles(@RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return invoices.pageTitles(ConsumerContext.userNo(), page, size);
    }

    @PostMapping("/invoice-titles")
    public InvoiceTitleItem saveInvoiceTitle(@RequestBody UsrInvoiceTitle body) {
        return invoices.saveTitle(ConsumerContext.userNo(), body);
    }

    @GetMapping("/invoices")
    public PageResult<InvoiceItem> invoices(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String status) {
        return invoices.pageInvoices(ConsumerContext.userNo(), page, size, status);
    }

    @PostMapping("/invoices")
    public InvoiceItem applyInvoice(@RequestBody InvoiceApplyReq body) {
        return invoices.apply(ConsumerContext.userNo(), body);
    }

    // —— 注销（C-AC-05，PDPL 冷静期）——

    /** 提交注销申请；返回冷静期截止时间，期内可撤销。 */
    @PostMapping("/logoff")
    public LogoffItem applyLogoff() {
        return logoff.apply(ConsumerContext.userNo());
    }

    /**
     * 当前注销申请；没有则返回 {@code null}。
     *
     * <p><b>没有这个端点，冷静期就是个说法</b>：用户提交之后看不到「几号生效」，
     * 也无从知道自己还能不能反悔 —— 而 {@code UserLogoffService} 一直实现着 current/cancel，
     * 只是没人把它们接出来。少了这两个口，注销在产品上是<b>单向门</b>。
     */
    @GetMapping("/logoff")
    public LogoffItem currentLogoff() {
        return logoff.current(ConsumerContext.userNo());
    }

    /**
     * 冷静期内撤销注销。无 PENDING 申请或冷静期已过 → 400（服务层抛 IllegalStateException）。
     *
     * <p>过期之后不允许撤销不是吝啬：到期后清除作业可能已经在跑，
     * 这时候「撤销成功」会是一句假话 —— 数据已经开始删了。
     */
    @PostMapping("/logoff/cancel")
    public LogoffItem cancelLogoff() {
        return logoff.cancel(ConsumerContext.userNo());
    }

    // —— 个人资料（C-ME，自 MpController 演示端点移入，真表实现）——

    /** 我的资料（镜像 c-app {@code UserProfile}）。{@code phone} 出参即脱敏。 */
    @GetMapping("/profile")
    public java.util.Map<String, Object> profile() {
        return profileOf(ConsumerContext.userNo());
    }

    /**
     * 资料编辑：仅 {@code nickname}/{@code avatar}。{@code email} **显式拒绝**——
     * 无落列（PII 归 pb_pii 分库，未建），静默丢弃会让端上以为存上了。
     */
    @PostMapping("/profile")
    public java.util.Map<String, Object> updateProfile(@RequestBody java.util.Map<String, String> body) {
        if (body != null && body.get("email") != null && !body.get("email").isBlank()) {
            throw new IllegalArgumentException("email 暂不支持修改：PII 库(pb_pii)未建，请先只改昵称/头像");
        }
        String me = ConsumerContext.userNo();
        userQuery.updateProfile(me, body == null ? null : body.get("nickname"),
                body == null ? null : body.get("avatar"));
        return profileOf(me);
    }

    /** 会员方案列表（C-MB 选购页）：全部在售方案 + 我是否已开通。 */
    @GetMapping("/membership")
    public List<MembershipPlanVO> membership() {
        return memberships.plansFor(ConsumerContext.userNo());
    }

    private java.util.Map<String, Object> profileOf(String me) {
        CUserRow u = userQuery.get(me);
        if (u == null) throw BizException.notFound(me);
        var member = memberships.get(me);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("cUserNo", u.cUserNo());
        out.put("nickname", u.nickname());
        out.put("avatar", u.avatar());
        out.put("phone", u.phone());
        out.put("email", null);               // 无列（pb_pii 未建），如实出 null
        out.put("creditScore", u.creditScore());
        out.put("freeDeposit", false);        // 免押开通态待 nearpay 预授权接入（ADR-005），不伪造
        out.put("memberLevel", member == null ? null : member.level());
        return out;
    }
}
