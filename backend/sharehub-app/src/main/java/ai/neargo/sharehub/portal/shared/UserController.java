package ai.neargo.sharehub.portal.shared;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.StaffContext;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentOrder;
import ai.neargo.sharehub.trade.service.RentOrderService;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.MemberRow;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletRow;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletTxnRow;
import ai.neargo.sharehub.user.asset.service.MembershipService;
import ai.neargo.sharehub.user.asset.service.WalletService;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.CUserRow;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.FreeUserWhitelist;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.UserBlacklist;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.UserRisk;
import ai.neargo.sharehub.user.core.service.FreeWhitelistService;
import ai.neargo.sharehub.user.core.service.UserBlacklistService;
import ai.neargo.sharehub.user.core.service.UserQueryService;
import ai.neargo.sharehub.user.core.service.UserRiskService;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.CouponTplVO;
import ai.neargo.sharehub.user.marketing.service.CouponTplService;
import ai.neargo.sharehub.user.member.dto.MemberDtos.CreditScoreChange;
import ai.neargo.sharehub.user.member.dto.MemberDtos.MemberCard;
import ai.neargo.sharehub.user.member.service.CreditScoreService;
import ai.neargo.sharehub.user.member.service.MemberService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * user 域运营端端点（对齐 docs/api §六 与 ops-web {@code http.ts}）：
 * C 端用户档案 / 营销券模板 / 风控拉黑（内部）。SeedData 骨架已退役，全部走真表。
 *
 * <p>用户 360 档案（{@code /users/{no}/profile}）在门面层组装：用户域读模型出档案行，
 * 订单/钱包/会员各域 Service 各出自己的切片 —— 跨域组合是 portal 的职责，
 * 不让用户域反向依赖交易域（分层 L1 ↛ L3）。
 */
@RestController
public class UserController {

    private final UserQueryService userQuery;
    private final CouponTplService couponTpls;
    private final UserBlacklistService userBlacklists;
    private final UserRiskService userRisks;
    private final FreeWhitelistService freeWhitelists;
    private final CreditScoreService creditScores;
    private final WalletService wallets;
    private final MembershipService memberships;
    private final MemberService members;
    private final RentOrderService rentOrders;

    public UserController(UserQueryService userQuery, CouponTplService couponTpls,
                          UserBlacklistService userBlacklists, UserRiskService userRisks,
                          FreeWhitelistService freeWhitelists, CreditScoreService creditScores,
                          WalletService wallets, MembershipService memberships,
                          MemberService members, RentOrderService rentOrders) {
        this.userQuery = userQuery;
        this.couponTpls = couponTpls;
        this.userBlacklists = userBlacklists;
        this.userRisks = userRisks;
        this.freeWhitelists = freeWhitelists;
        this.creditScores = creditScores;
        this.wallets = wallets;
        this.memberships = memberships;
        this.members = members;
        this.rentOrders = rentOrders;
    }

    @GetMapping("/api/user/users")
    @PreAuthorize("@perm.can('user:cuser:read')")
    public PageResult<CUserRow> users(@RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size,
                                      @RequestParam(required = false) String keyword) {
        PageResult<CUserRow> r = userQuery.page(page, size, keyword);
        return new PageResult<>(withOrderCounts(r.getList()), r.getTotal());
    }

    /**
     * 用户 360 档案（列表行点开的详情抽屉），镜像前端 {@code UserProfile}。
     * {@code orderStats} 由订单切片现算 —— 不另存计数，免得两个数打架。
     */
    @GetMapping("/api/user/users/{cUserNo}/profile")
    @PreAuthorize("@perm.can('user:cuser:read')")
    public UserProfileVO profile(@PathVariable String cUserNo) {
        CUserRow base = userQuery.get(cUserNo);
        if (base == null) throw BizException.notFound(cUserNo);
        CUserRow user = withOrderCounts(List.of(base)).get(0);

        // 各切片服务的 page 只支持 keyword LIKE，此处按业务键**精确二次过滤**，防子串误命中
        UserRisk risk = userRisks.page(1, 20, cUserNo, null).getList().stream()
                .filter(r -> cUserNo.equals(r.userNo())).findFirst().orElse(null);
        List<UserBlacklist> blacklist = userBlacklists.page(1, 200, cUserNo, null).getList().stream()
                .filter(b -> cUserNo.equals(b.userNo())).toList();
        FreeUserWhitelist whitelist = freeWhitelists.get(cUserNo);
        List<CreditScoreChange> creditChanges = creditScores.pageChanges(1, 50, cUserNo).getList();
        WalletRow wallet = wallets.page(1, 20, cUserNo).getList().stream()
                .filter(w -> cUserNo.equals(w.userNo())).findFirst().orElse(null);
        List<WalletTxnRow> walletTxns = wallets.txnsOf(cUserNo, 1, 5, null).getList();
        MemberRow member = memberships.get(cUserNo);
        List<MemberCard> cards = members.pageCards(1, 50, cUserNo, null).getList();

        PageResult<RentOrder> orderPage = rentOrders.pageByOwner(cUserNo, 1, 200);
        List<RentOrder> orders = orderPage.getList();
        BigDecimal amount = orders.stream()
                .map(o -> BigDecimal.valueOf(o.feeAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = orders.isEmpty() ? "AED" : orders.get(0).currency();
        OrderStats orderStats = new OrderStats(orderPage.getTotal(), amount, currency);

        return new UserProfileVO(user, risk, blacklist, whitelist, creditChanges,
                wallet, walletTxns, member, cards, orders, orderStats);
    }

    @GetMapping("/api/user/coupons")
    @PreAuthorize("@perm.can('marketing:coupon:read')")
    public PageResult<CouponTplVO> coupons(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String keyword) {
        return couponTpls.page(page, size, keyword, Map.of());
    }

    /** 内部拉黑/解除。拉黑写新记录、解除单向（{@code ACTIVE → RELEASED}），语义在 service。 */
    @PostMapping("/internal/user/credit/blacklist")
    @PreAuthorize("@perm.can('user:risk:update')")
    public OkResult blacklist(@RequestBody Map<String, Object> body) {
        String cUserNo = String.valueOf(body.get("cUserNo"));
        boolean blacklisted = Boolean.TRUE.equals(body.get("blacklisted"));
        String reason = body.get("reason") == null ? "运营端拉黑" : String.valueOf(body.get("reason"));
        String operator = StaffContext.require().username();
        if (blacklisted) {
            userBlacklists.block(cUserNo, reason, operator);
        } else {
            userBlacklists.release(cUserNo, operator);
        }
        return new OkResult(true);
    }

    /** 累计单数是跨域数据，在门面层批量回填（见类注释）。 */
    private List<CUserRow> withOrderCounts(List<CUserRow> rows) {
        Map<String, Long> counts = rentOrders.countByUsers(rows.stream().map(CUserRow::cUserNo).toList());
        return rows.stream().map(u -> new CUserRow(u.cUserNo(), u.nickname(), u.avatar(), u.phone(),
                u.creditScore(), u.blacklisted(), counts.getOrDefault(u.cUserNo(), 0L),
                u.registeredAt())).toList();
    }

    /** 订单聚合（由订单切片现算）。 */
    public record OrderStats(Long count, BigDecimal amount, String currency) {
    }

    /** 用户 360 档案出参，镜像前端 {@code UserProfile}。 */
    public record UserProfileVO(CUserRow user, UserRisk risk, List<UserBlacklist> blacklist,
                                FreeUserWhitelist whitelist, List<CreditScoreChange> creditChanges,
                                WalletRow wallet, List<WalletTxnRow> walletTxns, MemberRow member,
                                List<MemberCard> cards, List<RentOrder> orders, OrderStats orderStats) {
    }
}
