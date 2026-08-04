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
}
