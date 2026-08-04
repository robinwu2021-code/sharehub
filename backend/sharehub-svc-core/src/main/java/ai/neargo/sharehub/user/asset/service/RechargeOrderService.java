package ai.neargo.sharehub.user.asset.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.RechargeOrderRow;

/**
 * 充值订单（usr_recharge_order）。资金单据 → 手写；运营端只读（下单/回调走支付域）。
 *
 * <p>日期范围一律筛 {@code created_at}：{@code PENDING}/{@code FAILED} 单没有 {@code paid_at}，
 * 按 {@code paid_at} 筛会把未支付单整段漏掉，对账时对不上。
 */
public interface RechargeOrderService {

    /**
     * @param status PENDING/PAID/FAILED/REFUNDED；空则全部
     * @param from   起始日（含），比对 {@code created_at}
     * @param to     截止日（含）
     */
    PageResult<RechargeOrderRow> page(Integer page, Integer size, String keyword,
                                      String status, String from, String to);
}
