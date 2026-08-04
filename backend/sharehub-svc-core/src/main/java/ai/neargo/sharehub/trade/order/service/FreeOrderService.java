package ai.neargo.sharehub.trade.order.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.FreeOrder;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.FreeOrderStats;

/**
 * 免费订单业务。
 *
 * <p><b>没有独立表</b>（[db-design §5.1] 定稿）：免费订单就是
 * {@code ord_order WHERE free_reason IS NOT NULL}，减免额取 {@code ord_order.waived_amount}，
 * 免费来源枚举与 {@code usr_free_whitelist.reason} 同源
 * （{@code INTERNAL_TEST/VIP/BD_DEMO/MERCHANT_SELF}）。
 * 建独立表会让「同一张订单在两处各有一份」，对账时必然分叉。
 *
 * <p>{@link #stats()} 是**全量口径**（本月单量 / 累计减免额），不是当前页合计 ——
 * 页头统计拿分页数据算会随翻页变化，那是错的。
 */
public interface FreeOrderService {

    PageResult<FreeOrder> page(Integer page, Integer size, String keyword, String whitelistReason);

    /** 页头统计：本月免费单数 + 累计减免金额（全量，非当前页）。 */
    FreeOrderStats stats();
}
