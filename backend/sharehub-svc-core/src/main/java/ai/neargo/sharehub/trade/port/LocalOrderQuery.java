package ai.neargo.sharehub.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.core.port.OrderQueryPort;
import ai.neargo.sharehub.trade.OrderStatus;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.mapper.OrdMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@link OrderQueryPort} 的本地实现（系统读，豁免数据范围）。 */
@Service
public class LocalOrderQuery implements OrderQueryPort {

    /** 进行中：出宝中 / 使用中 —— 宝还在用户手里。 */
    static final List<String> IN_FLIGHT = List.of(OrderStatus.DISPENSING.name(), OrderStatus.IN_USE.name());

    private final OrdMapper orders;

    public LocalOrderQuery(OrdMapper orders) {
        this.orders = orders;
    }

    @Override
    public Map<String, Long> inFlightCountBySites(Collection<String> siteNos) {
        if (siteNos == null || siteNos.isEmpty()) return Map.of();
        Map<String, Long> out = new HashMap<>();
        DataScopeContext.executeWithoutScope(() -> orders.selectMaps(new QueryWrapper<OrdOrder>()
                        .select("site_no AS siteNo", "COUNT(*) AS cnt").in("site_no", siteNos).in("status", IN_FLIGHT)
                        .groupBy("site_no")))
                .forEach(m -> out.put(String.valueOf(m.get("siteNo")), ((Number) m.get("cnt")).longValue()));
        return out;
    }

    /** 结算后的实收（amount 已扣券与免单）才算经营数据；进行中的单金额未定。 */
    static final List<String> SETTLED = List.of(OrderStatus.SETTLED.name(), OrderStatus.CLOSED.name());

    @Override
    public Map<String, java.math.BigDecimal> gmvBySites(Collection<String> siteNos, int days) {
        if (siteNos == null || siteNos.isEmpty()) return Map.of();
        java.time.LocalDateTime from = java.time.LocalDateTime.now().minusDays(days);
        Map<String, java.math.BigDecimal> out = new HashMap<>();
        DataScopeContext.executeWithoutScope(() -> orders.selectMaps(new QueryWrapper<OrdOrder>()
                        .select("site_no AS siteNo", "COALESCE(SUM(amount), 0) AS gmv").in("site_no", siteNos)
                        .in("status", SETTLED).ge("started_at", from).groupBy("site_no")))
                .forEach(m -> out.put(String.valueOf(m.get("siteNo")), new java.math.BigDecimal(String.valueOf(m.get("gmv")))));
        return out;
    }

    @Override
    public Map<String, Map<String, java.math.BigDecimal>> monthlyGmvBySites(Collection<String> siteNos, java.time.LocalDate from,
                                                                            java.time.LocalDate to) {
        if (siteNos == null || siteNos.isEmpty()) return Map.of();
        Map<String, Map<String, java.math.BigDecimal>> out = new HashMap<>();
        DataScopeContext.executeWithoutScope(() -> orders.selectMaps(new QueryWrapper<OrdOrder>()
                        .select("site_no AS siteNo", "DATE_FORMAT(started_at, '%Y-%m') AS ym", "COALESCE(SUM(amount), 0) AS gmv")
                        .in("site_no", siteNos).in("status", SETTLED).ge("started_at", from.atStartOfDay()).lt("started_at", to.atStartOfDay())
                        .groupBy("site_no", "DATE_FORMAT(started_at, '%Y-%m')")))
                .forEach(m -> out.computeIfAbsent(String.valueOf(m.get("siteNo")), k -> new HashMap<>())
                        .put(String.valueOf(m.get("ym")), new java.math.BigDecimal(String.valueOf(m.get("gmv")))));
        return out;
    }

    @Override
    public Map<String, Map<Integer, Long>> ordersByHour(Collection<String> siteNos, int days) {
        if (siteNos == null || siteNos.isEmpty()) return Map.of();
        java.time.LocalDateTime from = java.time.LocalDateTime.now().minusDays(days);
        Map<String, Map<Integer, Long>> out = new HashMap<>();
        DataScopeContext.executeWithoutScope(() -> orders.selectMaps(new QueryWrapper<OrdOrder>()
                        .select("site_no AS siteNo", "HOUR(started_at) AS hr", "COUNT(*) AS cnt").in("site_no", siteNos)
                        .ge("started_at", from).isNotNull("started_at").groupBy("site_no", "HOUR(started_at)")))
                .forEach(m -> out.computeIfAbsent(String.valueOf(m.get("siteNo")), k -> new HashMap<>())
                        .put(((Number) m.get("hr")).intValue(), ((Number) m.get("cnt")).longValue()));
        return out;
    }
}
