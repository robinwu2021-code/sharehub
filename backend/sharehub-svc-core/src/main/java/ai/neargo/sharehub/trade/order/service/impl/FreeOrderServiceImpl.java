package ai.neargo.sharehub.trade.order.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.core.port.NicknameQueryPort;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.mapper.OrdMapper;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.FreeOrder;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.FreeOrderStats;
import ai.neargo.sharehub.trade.order.service.FreeOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 免费订单 —— 没有自己的表，是 {@code ord_order WHERE free_reason IS NOT NULL}
 * （[db-design §5.1]，索引 {@code idx_ord_free(tenant_id, free_reason, created_at)} 就是为它建的）。
 *
 * <p><b>此前这里返回的是写死的空结果与零统计</b>，注释说在等 {@code ord_order} 补上
 * {@code free_reason / waived_amount / coupon_no / location_name} 等 v2 新列。
 * 那些列早就补齐了（V8 + 实体映射），但没人回来接线 ——
 * 于是「免费订单」这一页永远是空的、页头永远是 0，
 * 而它看起来和「这个月确实没人用免单」一模一样。
 *
 * <p>（在同一批改动里，{@code ord_order.free_reason} 才第一次真的有人写 ——
 * 见 {@code RentOrderServiceImpl.rent} 里的白名单命中。）
 */
@Service
public class FreeOrderServiceImpl implements FreeOrderService {

    /** 页头统计的默认币种（单市场 AED；多币种后应按 ord_order.currency 分组）。 */
    private static final String DEFAULT_CURRENCY = "AED";

    private final OrdMapper mapper;
    private final NicknameQueryPort nicknames;
    /** 站点名在 platform；跨服务只读面（ADR-017 §5.2）。缺席时退回订单上的点位名。 */
    private final ObjectProvider<SiteQueryPort> siteQuery;

    public FreeOrderServiceImpl(OrdMapper mapper, NicknameQueryPort nicknames,
                                ObjectProvider<SiteQueryPort> siteQuery) {
        this.mapper = mapper;
        this.nicknames = nicknames;
        this.siteQuery = siteQuery;
    }

    @Override
    public PageResult<FreeOrder> page(Integer page, Integer size, String keyword, String whitelistReason) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<OrdOrder> w = new LambdaQueryWrapper<OrdOrder>()
                .isNotNull(OrdOrder::getFreeReason)
                .ne(OrdOrder::getFreeReason, "")
                .eq(has(whitelistReason), OrdOrder::getFreeReason, whitelistReason);
        if (has(keyword)) {
            w.and(q -> q.like(OrdOrder::getOrderNo, keyword).or().like(OrdOrder::getCUserNo, keyword));
        }
        w.orderByDesc(OrdOrder::getId);

        Page<OrdOrder> r = mapper.selectPage(new Page<>(p, s), w);
        List<OrdOrder> rows = r.getRecords();
        // 昵称与站点名各批查一次 —— 逐行查就是 N+1，而这正是最长的那种列表
        Map<String, String> nicks = nicknames.byUserNos(rows.stream().map(OrdOrder::getCUserNo).toList());
        Map<String, String> siteNames = siteNames(rows);
        return new PageResult<>(rows.stream().map(e -> toVO(e, nicks, siteNames)).toList(), r.getTotal());
    }

    /**
     * 页头统计走 SQL 聚合，**不在 Java 侧对分页结果求和** —— 后者翻一页数字就变了。
     *
     * <p>两个数口径不同，刻意分开算：单数只看本月（这个月花了多少），
     * 减免额是累计（这条白名单一共让利多少）。
     */
    @Override
    public FreeOrderStats stats() {
        java.time.LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        Long monthCount = mapper.selectCount(new LambdaQueryWrapper<OrdOrder>()
                .isNotNull(OrdOrder::getFreeReason).ne(OrdOrder::getFreeReason, "")
                .ge(OrdOrder::getCreatedAt, monthStart));

        Map<String, Object> sum = mapper.selectMaps(new QueryWrapper<OrdOrder>()
                .select("COALESCE(SUM(waived_amount), 0) AS waived_total")
                .isNotNull("free_reason").ne("free_reason", "")).stream().findFirst().orElse(Map.of());
        Object total = sum.get("waived_total");
        return new FreeOrderStats(monthCount == null ? 0L : monthCount,
                total == null ? BigDecimal.ZERO : new BigDecimal(total.toString()), DEFAULT_CURRENCY);
    }

    private Map<String, String> siteNames(List<OrdOrder> rows) {
        SiteQueryPort sites = siteQuery.getIfAvailable();
        if (sites == null) return Map.of();
        List<String> nos = rows.stream().map(OrdOrder::getSiteNo).filter(n -> n != null && !n.isBlank()).distinct().toList();
        if (nos.isEmpty()) return Map.of();
        return sites.briefsByNos(nos).stream()
                .filter(b -> b.name() != null)
                .collect(java.util.stream.Collectors.toMap(SiteBrief::siteNo, SiteBrief::name, (a, b) -> a));
    }

    private static FreeOrder toVO(OrdOrder e, Map<String, String> nicks, Map<String, String> siteNames) {
        // 站点名取不到时退回订单上的点位名：宁可显示得粗一点，也好过空白一列
        String siteName = siteNames.getOrDefault(e.getSiteNo(), e.getLocationName());
        return new FreeOrder(e.getOrderNo(), e.getCUserNo(), nicks.get(e.getCUserNo()), e.getFreeReason(),
                e.getWaivedAmount() == null ? BigDecimal.ZERO : e.getWaivedAmount(),
                e.getCurrency() == null ? DEFAULT_CURRENCY : e.getCurrency(),
                siteName, e.getCabinetNo(), e.getRentStartAt(), e.getRentEndAt(), e.getDurationMin());
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank();
    }
}
