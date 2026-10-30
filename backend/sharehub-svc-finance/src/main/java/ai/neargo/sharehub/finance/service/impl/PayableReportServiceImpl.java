package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.finance.SettlementStatus;
import ai.neargo.sharehub.finance.service.PayableReportService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 读模型，无对应表：分润明细与结算单两边各聚合一次再按 (收款方, 期间) 对上。
 * 只读、走运营端财务权限，不经数据范围（与分润统计同口径）。
 */
@Service
public class PayableReportServiceImpl implements PayableReportService {

    private static final Pattern PERIOD = Pattern.compile("\\d{4}-\\d{2}");

    private final JdbcTemplate jdbc;

    public PayableReportServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<PayableRow> page(Integer page, Integer size, String payeeType, String payeeNo,
                                       String fromPeriod, String toPeriod, boolean onlyOutstanding) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        // 逐笔口径（2026-10-29 走查后改）：每笔分润明细 / 已确认调整项按**它自己的期间**计应得，
        // 按它挂的那张结算单（settle_no）的状态计已出单 / 已付。此前按「同期间的结算单总额」对，
        // 结算单一旦并入别的期间的明细或调整项，同一行就会出现「已付 > 应得」、未付为负。
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE i.payee_no IS NOT NULL");
        if (has(payeeType)) { where.append(" AND i.payee_type = ?"); args.add(payeeType.trim().toUpperCase()); }
        if (has(payeeNo)) { where.append(" AND i.payee_no = ?"); args.add(payeeNo.trim()); }
        if (has(fromPeriod)) { where.append(" AND i.period >= ?"); args.add(period(fromPeriod, "fromPeriod")); }
        if (has(toPeriod)) { where.append(" AND i.period <= ?"); args.add(period(toPeriod, "toPeriod")); }

        String confirmed = SettlementStatus.CONFIRMED.name(), paid = SettlementStatus.PAID.name();
        String adjCounted = "'" + ai.neargo.sharehub.finance.AdjustmentStatus.CONFIRMED.name() + "', '"
                + ai.neargo.sharehub.finance.AdjustmentStatus.SETTLED.name() + "'";
        String items = "SELECT r.payee_type, r.payee_no, r.payee_name, r.period, r.currency, r.amount, r.settle_no "
                + "FROM share_record r WHERE r.deleted = 0 "
                + "UNION ALL SELECT a.payee_type, a.payee_no, a.payee_name, a.period, a.currency, a.amount, a.settle_no "
                + "FROM stl_adjustment a WHERE a.status IN (" + adjCounted + ")";
        String base = "SELECT i.payee_type, i.payee_no, MAX(i.payee_name) AS payee_name, i.period, MAX(i.currency) AS currency, "
                + "SUM(i.amount) AS earned, "
                + "SUM(CASE WHEN st.status IN ('" + confirmed + "', '" + paid + "') THEN i.amount ELSE 0 END) AS settled, "
                + "SUM(CASE WHEN st.status = '" + paid + "' THEN i.amount ELSE 0 END) AS paid, "
                + "SUM(i.amount) - SUM(CASE WHEN st.status = '" + paid + "' THEN i.amount ELSE 0 END) AS outstanding "
                + "FROM (" + items + ") i LEFT JOIN stl_settlement st ON st.settle_no = i.settle_no AND st.deleted = 0"
                + where + " GROUP BY i.payee_type, i.payee_no, i.period"
                + (onlyOutstanding ? " HAVING outstanding <> 0" : "");
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + base + ") t", Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((p - 1) * s);
        List<PayableRow> rows = jdbc.query(base + " ORDER BY period DESC, payee_type, payee_no LIMIT ? OFFSET ?",
                (rs, i) -> new PayableRow(rs.getString("payee_type"), rs.getString("payee_no"), rs.getString("payee_name"),
                        rs.getString("period"), rs.getString("currency"), nz(rs.getBigDecimal("earned")),
                        nz(rs.getBigDecimal("settled")), nz(rs.getBigDecimal("paid")), nz(rs.getBigDecimal("outstanding"))),
                pageArgs.toArray());
        return new PageResult<>(rows, total == null ? 0 : total);
    }

    private static String period(String v, String name) {
        String t = v.trim();
        if (!PERIOD.matcher(t).matches()) throw BizException.badRequest("error.common.invalid_value", name);
        return t;
    }

    private static boolean has(String v) {
        return v != null && !v.isBlank();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
