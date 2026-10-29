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
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE r.deleted = 0 AND r.payee_no IS NOT NULL");
        if (has(payeeType)) { where.append(" AND r.payee_type = ?"); args.add(payeeType.trim().toUpperCase()); }
        if (has(payeeNo)) { where.append(" AND r.payee_no = ?"); args.add(payeeNo.trim()); }
        if (has(fromPeriod)) { where.append(" AND r.period >= ?"); args.add(period(fromPeriod, "fromPeriod")); }
        if (has(toPeriod)) { where.append(" AND r.period <= ?"); args.add(period(toPeriod, "toPeriod")); }

        String confirmed = SettlementStatus.CONFIRMED.name(), paid = SettlementStatus.PAID.name();
        String base = "SELECT e.payee_type, e.payee_no, e.payee_name, e.period, e.currency, e.earned, "
                + "COALESCE(st.settled, 0) AS settled, COALESCE(st.paid, 0) AS paid, e.earned - COALESCE(st.paid, 0) AS outstanding "
                + "FROM (SELECT r.payee_type, r.payee_no, MAX(r.payee_name) AS payee_name, r.period, MAX(r.currency) AS currency, "
                + "SUM(r.amount) AS earned FROM share_record r" + where + " GROUP BY r.payee_type, r.payee_no, r.period) e "
                + "LEFT JOIN (SELECT payee_type, payee_no, period, "
                + "SUM(CASE WHEN status IN ('" + confirmed + "', '" + paid + "') THEN total_amount ELSE 0 END) AS settled, "
                + "SUM(CASE WHEN status = '" + paid + "' THEN total_amount ELSE 0 END) AS paid "
                + "FROM stl_settlement WHERE deleted = 0 GROUP BY payee_type, payee_no, period) st "
                + "ON st.payee_type = e.payee_type AND st.payee_no = e.payee_no AND st.period = e.period"
                + (onlyOutstanding ? " WHERE e.earned - COALESCE(st.paid, 0) <> 0" : "");
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + base + ") t", Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((p - 1) * s);
        List<PayableRow> rows = jdbc.query(base + " ORDER BY e.period DESC, e.payee_type, e.payee_no LIMIT ? OFFSET ?",
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
