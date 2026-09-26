package ai.neargo.sharehub.portal.core;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.StaffContext;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.MemberRow;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.RechargeOrderRow;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.RechargePackageRow;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletRow;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletTxnRow;
import ai.neargo.sharehub.user.asset.entity.UsrMembership;
import ai.neargo.sharehub.user.asset.entity.UsrRechargePkg;
import ai.neargo.sharehub.user.asset.service.MembershipService;
import ai.neargo.sharehub.user.asset.service.RechargeOrderService;
import ai.neargo.sharehub.user.asset.service.RechargePackageService;
import ai.neargo.sharehub.user.asset.service.WalletService;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.CUserInvoiceRow;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.FreeUserWhitelist;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.LogoffItem;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.UserBlacklist;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.UserRisk;
import ai.neargo.sharehub.user.core.entity.UsrFreeWhitelist;
import ai.neargo.sharehub.user.core.service.FreeWhitelistService;
import ai.neargo.sharehub.user.core.service.UserInvoiceService;
import ai.neargo.sharehub.user.core.service.UserLogoffService;
import ai.neargo.sharehub.user.core.service.UserBlacklistService;
import ai.neargo.sharehub.user.core.service.UserRiskService;
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
 * 运营端 · 用户风控与资产（[api/README §5.1]）。
 *
 * <p>与已存在的 {@link UserController} 同属 {@code /api/user} 前缀但**子路径不重叠**
 * （那边是 {@code /users}、{@code /coupons}、{@code /internal/user/credit/blacklist}），
 * 拆开是因为那些还走内存种子、这些已落库。**新增端点前务必核对已占用路径**，重复映射会让 Spring 启动直接失败。
 */
@RestController
@RequestMapping("/api/user")
public class UserOpsController {

    private final UserLogoffService logoffs;
    private final UserInvoiceService cuserInvoices;
    private final UserRiskService risks;
    private final UserBlacklistService blacklist;
    private final FreeWhitelistService whitelist;
    private final MembershipService members;
    private final ai.neargo.sharehub.user.member.service.MemberService memberService;
    private final ai.neargo.sharehub.user.member.service.CreditScoreService creditScoreService;
    private final WalletService wallets;
    private final RechargePackageService packages;
    private final RechargeOrderService rechargeOrders;

    public UserOpsController(UserRiskService risks, UserBlacklistService blacklist,
                             FreeWhitelistService whitelist, MembershipService members,
                             WalletService wallets, RechargePackageService packages,
                             RechargeOrderService rechargeOrders,
                             ai.neargo.sharehub.user.member.service.MemberService memberService,
                             ai.neargo.sharehub.user.member.service.CreditScoreService creditScoreService,
                              UserLogoffService logoffs, UserInvoiceService cuserInvoices) {
        this.logoffs = logoffs;
        this.cuserInvoices = cuserInvoices;
        this.memberService = memberService;
        this.creditScoreService = creditScoreService;
        this.risks = risks;
        this.blacklist = blacklist;
        this.whitelist = whitelist;
        this.members = members;
        this.wallets = wallets;
        this.packages = packages;
        this.rechargeOrders = rechargeOrders;
    }

    // —— 风控用户（菜单叶：用户管理 › 风控用户）——

    @GetMapping("/risk-users")
    @PreAuthorize("@perm.can('user:risk:read')")
    public PageResult<UserRisk> riskUsers(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String riskLevel) {
        return risks.page(page, size, keyword, riskLevel);
    }

    // —— 黑名单（拉黑/解除的写入口在 POST /internal/user/credit/blacklist，见 UserController）——

    @GetMapping("/blacklist")
    @PreAuthorize("@perm.can('user:risk:read')")
    public PageResult<UserBlacklist> blacklist(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status) {
        return blacklist.page(page, size, keyword, status);
    }

    // —— 免费用户白名单 ——

    @GetMapping("/free-whitelist")
    @PreAuthorize("@perm.can('user:risk:read')")
    public PageResult<FreeUserWhitelist> freeWhitelist(@RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String reason,
                                                       @RequestParam(required = false) String status) {
        return whitelist.page(page, size, keyword, reason, status);
    }

    @PostMapping("/free-whitelist")
    @PreAuthorize("@perm.can('user:risk:update')")
    public FreeUserWhitelist grantWhitelist(@RequestBody UsrFreeWhitelist body) {
        return whitelist.save(body, StaffContext.require().userNo());
    }

    @PostMapping("/free-whitelist/{userNo}")
    @PreAuthorize("@perm.can('user:risk:update')")
    public FreeUserWhitelist updateWhitelist(@PathVariable String userNo,
                                             @RequestBody UsrFreeWhitelist body) {
        body.setCUserNo(userNo); // 路径为准，忽略 body 里的属主，防越权改他人白名单
        return whitelist.save(body, StaffContext.require().userNo());
    }

    /** 撤销 = 软删除（{@code status=REVOKED} 留记录），不是 DELETE（[api §1.5] 全站零 DELETE）。 */
    @PostMapping("/free-whitelist/{userNo}/revoke")
    @PreAuthorize("@perm.can('user:risk:update')")
    public FreeUserWhitelist revokeWhitelist(@PathVariable String userNo) {
        return whitelist.revoke(userNo, StaffContext.require().userNo());
    }

    // —— 会员 / 次卡 ——

    @GetMapping("/members")
    @PreAuthorize("@perm.can('user:member:read')")
    public PageResult<MemberRow> members(@RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String level) {
        return members.page(page, size, keyword, level);
    }

    @PostMapping("/members")
    @PreAuthorize("@perm.can('user:member:update')")
    public MemberRow createMember(@RequestBody UsrMembership body) {
        return members.save(body);
    }

    @PostMapping("/members/{userNo}")
    @PreAuthorize("@perm.can('user:member:update')")
    public MemberRow updateMember(@PathVariable String userNo, @RequestBody UsrMembership body) {
        body.setCUserNo(userNo);
        return members.save(body);
    }

    // —— 钱包（余额/赠金 + 用户价值画像；画像是聚合，不落列）——

    @GetMapping("/wallets")
    @PreAuthorize("@perm.can('user:wallet:read')")
    public PageResult<WalletRow> wallets(@RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size,
                                         @RequestParam(required = false) String keyword) {
        return wallets.page(page, size, keyword);
    }

    /**
     * 运营手工调整钱包。此前只有 GET —— 前端「调整钱包」在真后端下必 404。
     *
     * <p>两条路径都收：无 {@code userNo} 的那条要求 body 里带（服务端拒绝凭空开户）。
     */
    @PostMapping("/wallets")
    @PreAuthorize("@perm.can('user:wallet:update')")
    public WalletRow adjustWallet(
            @RequestBody ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletAdjustReq body) {
        return wallets.adjust(body.userNo(), body, currentOperator());
    }

    @PostMapping("/wallets/{userNo}")
    @PreAuthorize("@perm.can('user:wallet:update')")
    public WalletRow adjustWalletOf(@PathVariable String userNo,
            @RequestBody ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletAdjustReq body) {
        return wallets.adjust(userNo, body, currentOperator()); // 路径为准，防越权改他人钱包
    }

    @GetMapping("/wallets/{userNo}/txns")
    @PreAuthorize("@perm.can('user:wallet:read')")
    public PageResult<WalletTxnRow> walletTxns(@PathVariable String userNo,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String type) {
        return wallets.txnsOf(userNo, page, size, type);
    }

    // —— 充值套餐 ——

    @GetMapping("/recharge-packages")
    @PreAuthorize("@perm.can('user:wallet:read')")
    public PageResult<RechargePackageRow> rechargePackages(@RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer size,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) String status) {
        return packages.page(page, size, keyword, Map.of("status", nz(status)));
    }

    @PostMapping("/recharge-packages")
    @PreAuthorize("@perm.can('user:wallet:update')")
    public RechargePackageRow createRechargePackage(@RequestBody UserAssetDtos.RechargePackageReq body) {
        // 走 saveWithMarkets：适用市场在关联表上，基类的 save 只认实体 ——
        // 此前调 save 等于把这一格静默丢掉，而没有市场的套餐 C 端对每个用户都不出现
        return packages.saveWithMarkets(body.toEntity(), body.markets());
    }

    @PostMapping("/recharge-packages/{packageNo}")
    @PreAuthorize("@perm.can('user:wallet:update')")
    public RechargePackageRow updateRechargePackage(@PathVariable String packageNo,
                                                    @RequestBody UserAssetDtos.RechargePackageReq body) {
        UsrRechargePkg e = body.toEntity();
        e.setPackageNo(packageNo);
        return packages.saveWithMarkets(e, body.markets());
    }

    // —— 充值订单（只读；下单与回调走支付域）——

    @GetMapping("/recharge-orders")
    @PreAuthorize("@perm.can('user:wallet:read')")
    public PageResult<RechargeOrderRow> rechargeOrders(@RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to) {
        return rechargeOrders.page(page, size, keyword, status, from, to);
    }

    /** {@code Map.of} 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 归档RechargePackage。**不是删除** —— 行仍在，勾「显示已归档」可见，可 unarchive 恢复。
     *
     * <p>归档语义统一在 {@code AbstractCrudService}：盖 {@code archivedAt} 时间戳。
     * 时间戳而非布尔位，因为「什么时候归档的」本身是审计信息。
     */
    @PostMapping("/recharge-packages/{no}/archive")
    @PreAuthorize("@perm.can('marketing:recharge:update')")
    public Object archiveRechargePackage(@PathVariable String no) {
        return packages.archive(no);
    }

    /** 取消归档RechargePackage：清空时间戳，回到默认列表。 */
    @PostMapping("/recharge-packages/{no}/unarchive")
    @PreAuthorize("@perm.can('marketing:recharge:update')")
    public Object unarchiveRechargePackage(@PathVariable String no) {
        return packages.unarchive(no);
    }

    // ───────────────── 会员权益 / 会员卡 / 信用分 ─────────────────

    /** 会员等级权益，**按等级由低到高**返回（页面排序与单调性校验共用同一顺序）。 */
    @GetMapping("/member-benefits")
    @PreAuthorize("@perm.can('user:member:read')")
    public Object memberBenefits() {
        return memberService.benefits();
    }

    /**
     * 保存某等级的权益。
     *
     * <p><b>写入时强制单调性</b>：折扣更低、免费时长更长、升级门槛更高。
     * 黄金比铂金还便宜的话会员体系当场失去意义，而这种错误在三行数字的列表页上极不显眼，
     * 等发现时可能已经卖出一批卡 —— 所以必须在写入时拦。
     */
    @PostMapping("/member-benefits/{level}")
    @PreAuthorize("@perm.can('user:member:update')")
    public Object saveMemberBenefit(@PathVariable String level,
                                    @RequestBody ai.neargo.sharehub.user.member.dto.MemberDtos.MemberBenefit in) {
        return memberService.saveBenefit(level, in);
    }

    /** 会员卡列表。 */
    @GetMapping("/member-cards")
    @PreAuthorize("@perm.can('user:member:read')")
    public Object memberCards(@RequestParam(required = false) Integer page,
                              @RequestParam(required = false) Integer size,
                              @RequestParam(required = false) String keyword,
                              @RequestParam(required = false) String level) {
        return memberService.pageCards(page, size, keyword, level);
    }

    /** 发卡。 */
    @PostMapping("/member-cards")
    @PreAuthorize("@perm.can('user:member:update')")
    public Object grantMemberCard(@RequestBody ai.neargo.sharehub.user.member.dto.MemberDtos.MemberCardGrantReq req) {
        return memberService.grantCard(req);
    }

    /**
     * 调整信用分。**原因必填**，改分与落流水同事务 ——
     * 只改分不留流水，「分是怎么变成今天这样的」就永远查不出来。
     */
    @PostMapping("/users/{cUserNo}/credit-score")
    @PreAuthorize("@perm.can('user:risk:update')")
    public Object adjustCreditScore(@PathVariable String cUserNo,
                                    @RequestBody ai.neargo.sharehub.user.member.dto.MemberDtos.CreditScoreAdjustReq req) {
        return creditScoreService.adjust(cUserNo, req);
    }

    /** 信用分变更流水（append，只增不改）。 */
    @GetMapping("/credit-score-changes")
    @PreAuthorize("@perm.can('user:risk:read')")
    public Object creditScoreChanges(@RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size,
                                     @RequestParam(required = false) String cUserNo) {
        return creditScoreService.pageChanges(page, size, cUserNo);
    }

    /** 操作人以会话为准，不信前端传值 —— 手工调账必须回答「谁改的」。 */
    private static String currentOperator() {
        return ai.neargo.sharehub.auth.SecurityUtils.currentUser().map(u -> u.userNo()).orElse("SYSTEM");
    }

    // —— 注销申请受理（C-AC-05 的运营侧；权限码 user:logoff:*，2026-09-25 新增）——
    //
    // C 端 2026-09-25 已接上真接口（提交 / 看冷静期 / 自助撤销），而运营端**零入口** ——
    // 用户打电话说「我点错了」时，客服既看不到队列也无从代为撤销，
    // 只能让他自己在 App 里找，而他正是因为找不到才打的电话。

    /** 注销队列。出参只有用户号与三个时间戳，不含手机号姓名 —— 这也是它能给只读角色看的前提。 */
    @GetMapping("/logoffs")
    @PreAuthorize("@perm.can('user:logoff:read')")
    public PageResult<LogoffItem> logoffs(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String status) {
        return logoffs.pageForOps(page, size, status);
    }

    /**
     * 代为撤销注销申请。
     *
     * <p><b>没有「立即执行」的对应动作</b>：冷静期到点由清除作业执行，
     * 给一个手动提前销毁的按钮，等于给了一个不可逆的误操作入口。
     *
     * <p>冷静期已过则拒（400）—— 那时数据可能已在清除，说「撤销成功」是对用户说假话。
     */
    @PostMapping("/logoffs/{cUserNo}/revoke")
    @PreAuthorize("@perm.can('user:logoff:revoke')")
    public LogoffItem revokeLogoff(@PathVariable String cUserNo) {
        return logoffs.cancel(cUserNo);
    }

    // —— C 端开票受理（C-IV-03 的运营侧；权限码 user:invoice:*，2026-09-25 新增）——
    //
    // 运营端「发票」那个菜单叶管的是 fin_invoice —— 给场地方/代理商开的**结算发票**，
    // 与消费者开票是两个对象、两张表、两套业务键。于是 C 端能提交，提交之后无人受理。

    /** C 端开票申请队列。待受理的按申请时间正序 —— 否则老单永远沉在后面。 */
    @GetMapping("/cuser-invoices")
    @PreAuthorize("@perm.can('user:invoice:read')")
    public PageResult<CUserInvoiceRow> cuserInvoices(@RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String status) {
        return cuserInvoices.pageForOps(page, size, keyword, status);
    }

    /** 开具：置 ISSUED 并回填发票文件地址。受理人取当前登录人，不信前端传值。 */
    @PostMapping("/cuser-invoices/{invoiceNo}/issue")
    @PreAuthorize("@perm.can('user:invoice:handle')")
    public CUserInvoiceRow issueCUserInvoice(@PathVariable String invoiceNo,
                                             @RequestBody(required = false) Map<String, String> body) {
        String fileUrl = body == null ? null : body.get("fileUrl");
        return cuserInvoices.issue(invoiceNo, fileUrl, StaffContext.require().userNo());
    }

    /** 驳回：原因必填 —— 只说「已驳回」等于让用户无从改正后重提。 */
    @PostMapping("/cuser-invoices/{invoiceNo}/reject")
    @PreAuthorize("@perm.can('user:invoice:handle')")
    public CUserInvoiceRow rejectCUserInvoice(@PathVariable String invoiceNo,
                                              @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return cuserInvoices.reject(invoiceNo, reason, StaffContext.require().userNo());
    }
}
