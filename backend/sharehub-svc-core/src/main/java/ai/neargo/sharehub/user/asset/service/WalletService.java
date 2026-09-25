package ai.neargo.sharehub.user.asset.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletOverview;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletRow;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletTxnRow;

/**
 * 钱包（usr_wallet / usr_wallet_txn）。资金聚合根 → 手写。
 *
 * <p><b>用户价值画像是聚合不是列</b>（[db-design §1.4]）：{@link WalletRow} 里的
 * {@code orderCount}/{@code orderAmount}/{@code rechargeCount}/{@code rechargeAmount}
 * 现算自 {@code ord_order} 与 {@code usr_recharge_order}，实体上刻意没有这四列。
 */
public interface WalletService {

    /** 运营端钱包列表（含用户价值画像）。 */
    PageResult<WalletRow> page(Integer page, Integer size, String keyword);

    /** 运营端查某用户钱包流水。 */
    PageResult<WalletTxnRow> txnsOf(String cUserNo, Integer page, Integer size, String type);

    /** C 端「我的钱包」总览；钱包不存在时返回全零占位（新用户尚未开钱包不该报错）。 */
    WalletOverview overviewOf(String cUserNo);

    /**
     * 入账（充值到账 / 赠额）—— 余额与流水在同一事务里写。
     *
     * <p>与 {@link #adjust} 的区别是**流水的业务类型**：这里是用户真实充值（{@code RECHARGE}/{@code BONUS}），
     * 那里是运营补款（记 {@code REFUND}）。混为一谈会让「充值次数 / 充值金额」这两个经营指标凭空变大。
     *
     * <p>钱包行不存在则开户。此前全后端只有 {@link #adjust} 会建钱包行，
     * 于是一个从没被运营调过账的用户，充值时无处可入账。
     *
     * @param bizNo 入账来源单号（充值单号）。对账时要能从流水反查到那一单，
     *              留空的流水在差额排查里等于线索断了
     */
    WalletOverview credit(String cUserNo, java.math.BigDecimal payAmount, java.math.BigDecimal giftAmount,
                          String currency, String bizType, String bizNo, String title);

    /**
     * 运营手工调整余额 / 赠额。
     *
     * <p><b>改完必须补一条流水</b>，否则「流水合计 === 余额」当场被破坏 ——
     * 钱包详情会跟它打开来源的那张列表自相矛盾，而对账时没人说得清这笔差额从哪来。
     *
     * <p><b>用户必须存在；钱包不存在则开户</b>（与 {@link #credit} 共用同一处开户逻辑）。
     * 注册链路仍然不建钱包行，所以一个既没充值过、也没被调账过的用户是没有钱包行的。
     * 但给一个**不存在的用户**开钱包不行 —— 那笔钱永远没人认领，却会进所有统计。
     *
     * @param operator 操作人（取自会话，不信前端传值）—— 手工调账必须回答「谁改的」
     */
    WalletRow adjust(String cUserNo, ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletAdjustReq in, String operator);
}
