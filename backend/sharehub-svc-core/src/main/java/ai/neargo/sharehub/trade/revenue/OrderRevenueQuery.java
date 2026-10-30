package ai.neargo.sharehub.trade.revenue;

import ai.neargo.sharehub.trade.OrderStatus;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.mapper.OrdMapper;
import ai.neargo.sharehub.trade.order.entity.OrdRefund;
import ai.neargo.sharehub.trade.order.mapper.OrdRefundMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 商家营收的交易侧口径（需求方案 §10.1，商家一期 B5）。
 *
 * <ul>
 *   <li><b>交易额</b> = 窗口内结束、已结算订单的实付合计（含 VAT；免单与券抵扣不计 —— 没有真实现金流）；</li>
 *   <li><b>退款</b> = 这些订单已审批通过的退款合计（一期退款止于审批，没有渠道退款执行）；</li>
 *   <li>按订单结束时刻落窗口：订单在结束那一刻才有确定金额。</li>
 * </ul>
 *
 * <p><b>不写任何 where agent_no</b>：订单表登记了商家数据范围，商家调用时由拦截器自动收敛到自己的订单，
 * 运营调用时看全部（同仓内其它读接口的约定）。
 */
@Service
public class OrderRevenueQuery {

    private static final String SETTLED = OrderStatus.SETTLED.name();
    private static final String REFUND_APPROVED = "APPROVED";

    private final OrdMapper orders;
    private final OrdRefundMapper refunds;

    public OrderRevenueQuery(OrdMapper orders, OrdRefundMapper refunds) {
        this.orders = orders;
        this.refunds = refunds;
    }

    /** 窗口 [from, to]（含两端，按业务日）。 */
    public Totals totals(LocalDate from, LocalDate to) {
        Map<String, Object> m = first(orders.selectMaps(settled(from, to)
                .select("COUNT(*) AS n", "COALESCE(SUM(paid_amount), 0) AS gross")));
        List<Object> nos = orders.selectObjs(settled(from, to).select("order_no"));
        BigDecimal refund = BigDecimal.ZERO;
        if (!nos.isEmpty()) {
            Map<String, Object> r = first(refunds.selectMaps(new QueryWrapper<OrdRefund>()
                    .select("COALESCE(SUM(amount), 0) AS refund").eq("status", REFUND_APPROVED).in("order_no", nos)));
            refund = dec(r.get("refund"));
        }
        return new Totals(intOf(m.get("n")), dec(m.get("gross")), refund);
    }

    /** 按天：交易额与订单数，缺天补 0（折线不断）。 */
    public List<RevenueDay> trend(LocalDate from, LocalDate to) {
        Map<String, RevenueDay> byDay = new java.util.TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) byDay.put(d.toString(), new RevenueDay(d.toString(), 0, BigDecimal.ZERO));
        for (Map<String, Object> m : orders.selectMaps(settled(from, to)
                .select("DATE(ended_at) AS d", "COUNT(*) AS n", "COALESCE(SUM(paid_amount), 0) AS gross")
                .groupBy("DATE(ended_at)"))) {
            String d = String.valueOf(m.get("d"));
            byDay.put(d, new RevenueDay(d, intOf(m.get("n")), dec(m.get("gross"))));
        }
        return new ArrayList<>(byDay.values());
    }

    /** 按设备排行：交易额倒序。 */
    public List<RevenueByDevice> byDevice(LocalDate from, LocalDate to, int limit) {
        List<RevenueByDevice> out = new ArrayList<>();
        for (Map<String, Object> m : orders.selectMaps(settled(from, to)
                .select("cabinet_no AS c", "COUNT(*) AS n", "COALESCE(SUM(paid_amount), 0) AS gross")
                .groupBy("cabinet_no").orderByDesc("gross").last("limit " + Math.max(1, Math.min(limit, 100))))) {
            out.add(new RevenueByDevice(String.valueOf(m.get("c")), intOf(m.get("n")), dec(m.get("gross"))));
        }
        return out;
    }

    private static QueryWrapper<OrdOrder> settled(LocalDate from, LocalDate to) {
        return new QueryWrapper<OrdOrder>().eq("status", SETTLED)
                .ge("ended_at", from.atStartOfDay()).lt("ended_at", to.plusDays(1).atStartOfDay());
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() || rows.get(0) == null ? Map.of() : rows.get(0);
    }

    private static BigDecimal dec(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v));
    }

    private static int intOf(Object v) {
        return v == null ? 0 : Integer.parseInt(String.valueOf(v));
    }

    public record Totals(int orderCount, BigDecimal gross, BigDecimal refund) {
    }

    public record RevenueDay(String date, int orderCount, BigDecimal gross) {
    }

    public record RevenueByDevice(String cabinetNo, int orderCount, BigDecimal gross) {
    }
}
