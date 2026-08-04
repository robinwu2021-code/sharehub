package ai.neargo.sharehub.user.asset.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.mapper.OrdMapper;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletOverview;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletRow;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletTxnRow;
import ai.neargo.sharehub.user.asset.entity.UsrRechargeOrder;
import ai.neargo.sharehub.user.asset.entity.UsrWallet;
import ai.neargo.sharehub.user.asset.entity.UsrWalletTxn;
import ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.UsrRechargeOrderMapper;
import ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.UsrWalletMapper;
import ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.UsrWalletTxnMapper;
import ai.neargo.sharehub.user.asset.service.WalletService;
import ai.neargo.sharehub.user.core.service.NicknameLookup;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 钱包实现。
 *
 * <p><b>用户价值画像的聚合来源</b>（[db-design §1.4]「计数列不是列，是聚合」）：
 * <ul>
 *   <li>{@code orderCount}/{@code orderAmount} ← {@code ord_order} 按 {@code c_user_no}
 *       计数与 {@code fee_amount} 求和（只计已完成口径：RETURNED/SETTLED/CLOSED）；</li>
 *   <li>{@code rechargeCount}/{@code rechargeAmount} ← {@code usr_recharge_order}
 *       按 {@code c_user_no} 计数与 {@code pay_amount} 求和（只计 {@code status=PAID}）。</li>
 * </ul>
 * 当前按页内用户号批量拉明细在内存里汇总 —— 骨架阶段够用、口径显式可读。
 * <b>TODO(性能)</b>：明细量上来后改为 {@code GROUP BY c_user_no} 的自定义 Mapper 查询
 * （或落 T+1 的 {@code rpt_user_value} 报表表），<b>但不得为此在 usr_wallet 上加冗余列</b> ——
 * 落列必然与订单页/充值订单页不自洽（前端 mock 已踩过这个坑）。
 */
@Service
public class WalletServiceImpl implements WalletService {

    /** 计入「累计订单」的订单状态（[db-design §9A] 订单状态机）。 */
    private static final List<String> DONE_ORDER_STATUS = List.of("RETURNED", "SETTLED", "CLOSED");

    private final UsrWalletMapper wallets;
    private final UsrWalletTxnMapper txns;
    private final UsrRechargeOrderMapper recharges;
    private final OrdMapper orders;
    private final NicknameLookup nicknames;

    public WalletServiceImpl(UsrWalletMapper wallets, UsrWalletTxnMapper txns,
                             UsrRechargeOrderMapper recharges, OrdMapper orders,
                             NicknameLookup nicknames) {
        this.wallets = wallets;
        this.txns = txns;
        this.recharges = recharges;
        this.orders = orders;
        this.nicknames = nicknames;
    }

    @Override
    public PageResult<WalletRow> page(Integer page, Integer size, String keyword) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<UsrWallet> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(UsrWallet::getWalletNo, keyword).or().like(UsrWallet::getCUserNo, keyword));
        }
        w.orderByDesc(UsrWallet::getId);

        Page<UsrWallet> r = wallets.selectPage(new Page<>(p, s), w);
        List<String> userNos = r.getRecords().stream().map(UsrWallet::getCUserNo).toList();
        Map<String, String> nick = nicknames.byUserNos(userNos);
        List<OrdOrder> userOrders = ordersOf(userNos);
        List<UsrRechargeOrder> userRecharges = paidRechargesOf(userNos);

        List<WalletRow> rows = r.getRecords().stream().map(e -> {
            String u = e.getCUserNo();
            List<OrdOrder> mine = userOrders.stream().filter(o -> u.equals(o.getCUserNo())).toList();
            List<UsrRechargeOrder> myRc = userRecharges.stream().filter(o -> u.equals(o.getCUserNo())).toList();
            return new WalletRow(u, nick.get(u), e.getBalance(), e.getGiftBalance(),
                    e.getCurrency(), e.getUpdatedAt(),
                    mine.size(),
                    mine.stream().map(o -> o.getFeeAmount() == null ? BigDecimal.ZERO
                                    : BigDecimal.valueOf(o.getFeeAmount()))
                            .reduce(BigDecimal.ZERO, BigDecimal::add),
                    myRc.size(),
                    myRc.stream().map(o -> o.getPayAmount() == null ? BigDecimal.ZERO : o.getPayAmount())
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
        }).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public PageResult<WalletTxnRow> txnsOf(String cUserNo, Integer page, Integer size, String type) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<UsrWalletTxn> w = new LambdaQueryWrapper<UsrWalletTxn>()
                .eq(UsrWalletTxn::getCUserNo, cUserNo);
        if (type != null && !type.isBlank()) w.eq(UsrWalletTxn::getType, type);
        w.orderByDesc(UsrWalletTxn::getId);

        Page<UsrWalletTxn> r = txns.selectPage(new Page<>(p, s), w);
        List<WalletTxnRow> rows = r.getRecords().stream()
                .map(e -> new WalletTxnRow(e.getTxnNo(), e.getType(), e.getDirection(), e.getTitle(),
                        e.getAmount(), e.getCurrency(), e.getBizType(), e.getBizNo(), e.getCreatedAt()))
                .toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public WalletOverview overviewOf(String cUserNo) {
        UsrWallet e = wallets.selectOne(new LambdaQueryWrapper<UsrWallet>()
                .eq(UsrWallet::getCUserNo, cUserNo).last("limit 1"));
        if (e == null) {
            // 新用户尚未开钱包：返回零值而不是 404 —— 端上钱包页是常驻入口，报错会挡住整页
            return new WalletOverview(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, null);
        }
        return new WalletOverview(e.getBalance(), e.getGiftBalance(), e.getDepositAmount(),
                e.getFrozenAmount(), e.getCurrency());
    }

    private List<OrdOrder> ordersOf(List<String> userNos) {
        if (userNos.isEmpty()) return List.of();
        return orders.selectList(new LambdaQueryWrapper<OrdOrder>()
                .in(OrdOrder::getCUserNo, userNos)
                .in(OrdOrder::getStatus, DONE_ORDER_STATUS));
    }

    private List<UsrRechargeOrder> paidRechargesOf(List<String> userNos) {
        if (userNos.isEmpty()) return List.of();
        return recharges.selectList(new LambdaQueryWrapper<UsrRechargeOrder>()
                .in(UsrRechargeOrder::getCUserNo, userNos)
                .eq(UsrRechargeOrder::getStatus, "PAID"));
    }
}
