package ai.neargo.sharehub.user.asset.service.impl;

import ai.neargo.sharehub.user.asset.RechargeOrderStatus;

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
    /** 手工调账流水的业务类型：审计时要能把「人改的」单独筛出来。 */
    private static final String BIZ_MANUAL_ADJUST = "MANUAL_ADJUST";
    /** 流水类型（usr_wallet_txn.type 词表）。 */
    private static final String TXN_RECHARGE = "RECHARGE";
    private static final String TXN_BONUS = "BONUS";

    /** 手工调账要先确认用户真实存在 —— 给一个不存在的用户开钱包，那笔钱永远没人认领。 */
    private final ai.neargo.sharehub.user.mapper.UserMappers.UsrUserMapper users;

    public WalletServiceImpl(UsrWalletMapper wallets, UsrWalletTxnMapper txns,
                             UsrRechargeOrderMapper recharges, OrdMapper orders,
                             NicknameLookup nicknames,
                             ai.neargo.sharehub.user.mapper.UserMappers.UsrUserMapper users) {
        this.users = users;
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

    @Override
    @org.springframework.transaction.annotation.Transactional
    public WalletRow adjust(String cUserNo, ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletAdjustReq in, String operator) {
        if (cUserNo == null || cUserNo.isBlank()) {
            // 钱包属于用户。凭空建一个不对应任何用户的钱包，只会让它永远没人认领，
            // 却出现在所有统计里 —— 所以这里不开户，只调整。
            throw new IllegalArgumentException("请指定要调整的用户钱包（userNo 必填）");
        }
        // 用户必须真实存在。给一个不存在的用户开钱包，那笔钱永远没人认领，
        // 却会出现在所有统计里。
        boolean userExists = users.selectCount(
                new LambdaQueryWrapper<ai.neargo.sharehub.user.entity.UsrUser>()
                        .eq(ai.neargo.sharehub.user.entity.UsrUser::getCUserNo, cUserNo)) > 0;
        if (!userExists) throw new IllegalArgumentException("用户不存在: " + cUserNo);

        UsrWallet w = openIfAbsent(cUserNo, in.currency());

        BigDecimal beforeBalance = nz(w.getBalance());
        BigDecimal beforeBonus = nz(w.getGiftBalance());
        BigDecimal afterBalance = in.balance() == null ? beforeBalance : in.balance();
        BigDecimal afterBonus = in.bonus() == null ? beforeBonus : in.bonus();
        if (afterBalance.signum() < 0 || afterBonus.signum() < 0) {
            throw new IllegalArgumentException("余额 / 赠额不能调成负数");
        }

        BigDecimal dBalance = afterBalance.subtract(beforeBalance);
        BigDecimal dBonus = afterBonus.subtract(beforeBonus);
        if (dBalance.signum() == 0 && dBonus.signum() == 0) {
            return rowOf(w);   // 没动数就不记流水：一条 0 元流水只会污染对账
        }

        w.setBalance(afterBalance);
        w.setGiftBalance(afterBonus);
        if (in.currency() != null && !in.currency().isBlank()) w.setCurrency(in.currency());
        wallets.updateById(w);

        String who = (operator == null || operator.isBlank()) ? "SYSTEM" : operator;
        // 加钱记 REFUND（入账）而不是 RECHARGE：运营补款不是用户充值，
        // 混进去会让「充值次数/充值金额」这两个经营指标凭空变大。
        if (dBalance.signum() != 0) {
            writeTxn(w, dBalance.signum() > 0 ? "REFUND" : "SPEND",
                    "运营手工调整余额（" + who + "）", dBalance);
        }
        if (dBonus.signum() != 0) {
            writeTxn(w, "BONUS", "运营手工调整赠额（" + who + "）", dBonus);
        }
        return rowOf(w);
    }

    /**
     * 手工调账的流水。**必须与余额在同一事务里写** ——
     * 只改余额不记流水，「流水合计 === 余额」当场被破坏，而对账时没人说得清差额从哪来。
     */
    private void writeTxn(UsrWallet w, String type, String title, BigDecimal amount) {
        writeTxn(w, type, title, amount, BIZ_MANUAL_ADJUST, "");
    }

    /**
     * 开钱包（没有就建）。
     *
     * <p>抽出来是因为**建钱包的地方必须只有一处**：充值要入账、手工调账要入账，
     * 两处各建一次的话，字段默认值早晚会分叉（币种、押金、冻结各差一点），
     * 而差异只会在对账时以「这个用户的钱包怎么跟别人不一样」的形式冒出来。
     */
    private UsrWallet openIfAbsent(String cUserNo, String currency) {
        UsrWallet w = wallets.selectOne(new LambdaQueryWrapper<UsrWallet>()
                .eq(UsrWallet::getCUserNo, cUserNo).last("limit 1"));
        if (w != null) return w;
        w = new UsrWallet();
        w.setWalletNo(ai.neargo.common.core.IdGenerator.next("WA"));
        w.setTenantId("MAIN");
        w.setCUserNo(cUserNo);
        w.setBalance(BigDecimal.ZERO);
        w.setGiftBalance(BigDecimal.ZERO);
        w.setDepositAmount(BigDecimal.ZERO);
        w.setFrozenAmount(BigDecimal.ZERO);
        w.setCurrency(currency == null || currency.isBlank() ? "AED" : currency);
        wallets.insert(w);
        return w;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public WalletOverview credit(String cUserNo, BigDecimal payAmount, BigDecimal giftAmount,
                                 String currency, String bizType, String bizNo, String title) {
        if (cUserNo == null || cUserNo.isBlank()) throw new IllegalArgumentException("入账必须指定用户");
        BigDecimal pay = nz(payAmount);
        BigDecimal gift = nz(giftAmount);
        if (pay.signum() < 0 || gift.signum() < 0) throw new IllegalArgumentException("入账金额不能为负");
        // 0 元入账不记账：一条 0 元流水只会污染对账，和 adjust 里同样的道理
        if (pay.signum() == 0 && gift.signum() == 0) throw new IllegalArgumentException("入账金额为 0");

        UsrWallet w = openIfAbsent(cUserNo, currency);
        w.setBalance(nz(w.getBalance()).add(pay));
        w.setGiftBalance(nz(w.getGiftBalance()).add(gift));
        wallets.updateById(w);

        // 本金与赠额分两条流水：合成一条就再也分不清「充了多少」与「送了多少」，
        // 而赠额在财务上不是收入，报表要按这个拆
        if (pay.signum() > 0) writeTxn(w, TXN_RECHARGE, title, pay, bizType, bizNo);
        if (gift.signum() > 0) writeTxn(w, TXN_BONUS, title, gift, bizType, bizNo);

        return new WalletOverview(w.getBalance(), w.getGiftBalance(),
                nz(w.getDepositAmount()), nz(w.getFrozenAmount()), w.getCurrency());
    }

    private void writeTxn(UsrWallet w, String type, String title, BigDecimal amount,
                          String bizType, String bizNo) {
        UsrWalletTxn t = new UsrWalletTxn();
        t.setTxnNo(ai.neargo.common.core.IdGenerator.next(ai.neargo.sharehub.common.BizKey.WALLET_TXN));
        t.setWalletNo(w.getWalletNo());
        t.setCUserNo(w.getCUserNo());
        t.setType(type);
        t.setDirection(amount.signum() > 0 ? "IN" : "OUT");
        t.setTitle(title);
        t.setAmount(amount);
        t.setCurrency(w.getCurrency());
        t.setBizType(bizType);   // 手工调账要能被单独筛出来审计；充值要能反查到充值单
        t.setBizNo(bizNo == null ? "" : bizNo);
        t.setCreatedAt(java.time.LocalDateTime.now().toString());
        txns.insert(t);
    }

    private WalletRow rowOf(UsrWallet w) {
        return new WalletRow(w.getCUserNo(), null, nz(w.getBalance()), nz(w.getGiftBalance()),
                w.getCurrency(), w.getUpdatedAt(), 0L, BigDecimal.ZERO, 0L, BigDecimal.ZERO);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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
                .eq(UsrRechargeOrder::getStatus, RechargeOrderStatus.PAID.name()));
    }
}
