package ai.neargo.sharehub.trade.port;

import ai.neargo.sharehub.api.core.port.TradeAnomalyPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link TradeAnomalyPort} 的本地实现。订单与宝都在 core，联表不跨服务。
 *
 * <p>「宝已在柜中」取 {@code dev_powerbank}（状态 IN_CABINET 且所在柜非空）；
 * 「首次出现时刻」近似取该行最近更新时刻 —— 仓位级上报（dev_slot）接入后改读仓位的识别时刻。
 */
@Service
public class LocalTradeAnomaly implements TradeAnomalyPort {

    private final JdbcTemplate jdbc;

    public LocalTradeAnomaly(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OrderAnomaly> undelivered(int minutes, int limit) {
        return jdbc.query("SELECT o.order_no, o.c_user_no, o.site_no, o.cabinet_no, o.slot_index, o.powerbank_no, o.agent_no,"
                        + " COALESCE(o.started_at, o.created_at) since, o.amount FROM ord_order o"
                        + " WHERE o.deleted = 0 AND o.status = 'DISPENSING' AND COALESCE(o.started_at, o.created_at) < ?"
                        + " ORDER BY o.id LIMIT ?",
                (rs, i) -> row(rs), LocalDateTime.now().minusMinutes(minutes), clamp(limit));
    }

    @Override
    public List<OrderAnomaly> returnUnrecognized(int minutes, int limit) {
        return jdbc.query("SELECT o.order_no, o.c_user_no, o.site_no, p.cabinet_no, p.slot_index, o.powerbank_no, o.agent_no,"
                        + " p.updated_at since, o.amount FROM ord_order o JOIN dev_powerbank p ON p.powerbank_no = o.powerbank_no"
                        + " WHERE o.deleted = 0 AND p.deleted = 0 AND o.status = 'IN_USE' AND p.status = 'IN_CABINET'"
                        + " AND p.cabinet_no IS NOT NULL AND p.updated_at < ? ORDER BY o.id LIMIT ?",
                (rs, i) -> row(rs), LocalDateTime.now().minusMinutes(minutes), clamp(limit));
    }

    @Override
    public OrderAnomaly orderInfo(String orderNo) {
        List<OrderAnomaly> rows = jdbc.query("SELECT o.order_no, o.c_user_no, o.site_no,"
                        + " COALESCE(p.cabinet_no, o.cabinet_no) cabinet_no, COALESCE(p.slot_index, o.slot_index) slot_index,"
                        + " o.powerbank_no, o.agent_no, COALESCE(p.updated_at, o.started_at, o.created_at) since, o.amount"
                        + " FROM ord_order o LEFT JOIN dev_powerbank p ON p.powerbank_no = o.powerbank_no AND p.deleted = 0"
                        + " AND p.status = 'IN_CABINET' AND p.cabinet_no IS NOT NULL WHERE o.order_no = ? AND o.deleted = 0 LIMIT 1",
                (rs, i) -> row(rs), orderNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static OrderAnomaly row(java.sql.ResultSet rs) throws java.sql.SQLException {
        // 用 LocalDateTime 读写而不是 Timestamp：连接串 serverTimezone=UTC，Timestamp 会按 JVM 时区换算、差出 8 小时
        LocalDateTime since = rs.getObject("since", LocalDateTime.class);
        return new OrderAnomaly(rs.getString("order_no"), rs.getString("c_user_no"), rs.getString("site_no"),
                rs.getString("cabinet_no"), (Integer) rs.getObject("slot_index", Integer.class), rs.getString("powerbank_no"),
                rs.getString("agent_no"), since, rs.getBigDecimal("amount"));
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 1000));
    }
}
