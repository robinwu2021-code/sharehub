package ai.neargo.sharehub.trade.order.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.FreeOrder;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.FreeOrderStats;
import ai.neargo.sharehub.trade.order.service.FreeOrderService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 免费订单实现 —— **本类刻意不持有任何 mapper**。
 *
 * <p>免费订单没有自己的表：它是 {@code ord_order WHERE free_reason IS NOT NULL}
 * （[db-design §5.1] 定稿，索引 {@code idx_ord_free(tenant_id, free_reason, created_at)} 就是为它建的）。
 * 而 {@code ord_order} 的实体/mapper 归 {@code trade.entity}/{@code trade.mapper}，
 * 本分片没有写入权 —— 强行在这里新建一个 {@code OrdOrder} 副本会造成两份实体定义，
 * 后续加列时必然漏改一处。
 *
 * <p>所以骨架阶段只把接口与端点立起来，返回空结果集与零统计；接线见下方 TODO。
 */
@Service
public class FreeOrderServiceImpl implements FreeOrderService {

    /** 页头统计的默认币种（单市场 AED；多币种后应按 ord_order.currency 分组）。 */
    private static final String DEFAULT_CURRENCY = "AED";

    @Override
    public PageResult<FreeOrder> page(Integer page, Integer size, String keyword, String whitelistReason) {
        // TODO(依赖 ord_order)：需要 trade.mapper.OrdMapper + OrdOrder 实体补上 v2 新列
        //  （free_reason / waived_amount / coupon_no / location_no / location_name / buyout，
        //   见 ddl/pb_core-v2-alter.sql §2）。补齐后本方法为：
        //    LambdaQueryWrapper<OrdOrder> w = new LambdaQueryWrapper<>();
        //    w.isNotNull(OrdOrder::getFreeReason);
        //    if (has(whitelistReason)) w.eq(OrdOrder::getFreeReason, whitelistReason);
        //    if (has(keyword)) w.and(q -> q.like(OrdOrder::getOrderNo, kw).or().like(OrdOrder::getCUserNo, kw));
        //    w.orderByDesc(OrdOrder::getId);  → selectPage → toVO
        //  nickname 需要联 usr_user（或由 user 域提供批量取昵称的接口），不在 ord_order 里。
        return new PageResult<>(List.of(), 0L);
    }

    @Override
    public FreeOrderStats stats() {
        // TODO(依赖 ord_order)：**全量口径**，不是当前页合计 ——
        //  monthCount  = count(ord_order where free_reason is not null and created_at >= 本月 1 号)
        //  waivedTotal = sum(waived_amount) where free_reason is not null（累计减免额）
        //  两个数都要走 SQL 聚合，不能在 Java 侧对分页结果求和（翻页就变了）。
        return new FreeOrderStats(0L, BigDecimal.ZERO, DEFAULT_CURRENCY);
    }
}
