package ai.neargo.sharehub.operation.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

/**
 * 运营核心流程指标（对齐清单 G5，原文 §六）：只读统计，窗口 [from, to)。
 *
 * <p>放在 app 层、直接写 SQL：这些指标每一个都横跨两三个域（工单 × 站点、合同 × 站点 × 设备、商机 × 合同），
 * 在任何一个域里算都要反向依赖别的域；它们又只是读，不值得为此在各域开端口。
 *
 * <p>口径（每项都写死在这里，改口径改这里）：
 * <ul>
 *   <li>工单 SLA 达成率：窗口内创建、已完工且有 SLA 计时的工单里，未超解决时限的占比；</li>
 *   <li>MTTR：窗口内创建、已完工的故障单，从开单到最后一次处理记录的平均分钟数；</li>
 *   <li>合同续约率：窗口内到期的主合同里，有续签合同（任意状态，草稿除外）的占比；到期未续占比 = 1 − 续约率；</li>
 *   <li>装机时效：窗口内首次上线的站点，从该站点最早一份合同生效到首次上线的平均天数；</li>
 *   <li>首次试借还通过率：窗口内做第一次试借还的机柜里，第一次就通过的占比；</li>
 *   <li>线索转化率：窗口内新建的商机里，已签约的占比；签约周期：这些已签约商机从建档到合同生效的平均天数；</li>
 *   <li>撤场回收率：窗口内完工的撤机单，现场清点数 ÷ 系统应在柜数（清点不符的差异计入应在柜数）。</li>
 * </ul>
 * 分母为 0 的比率返回 null（「无数据」与「0%」是两回事）。
 */
@Service
public class OpsFlowMetricsService {

    public record OpsFlowMetrics(LocalDate from, LocalDate to,
                                 long woWithSla, BigDecimal woSlaRate, long faultResolved, Long mttrMinutes,
                                 long contractsEnded, long contractsRenewed, BigDecimal renewalRate, BigDecimal expiredNotRenewedRatio,
                                 long sitesWentLive, BigDecimal installLeadDaysAvg,
                                 long firstTrials, BigDecimal firstTrialPassRate,
                                 long leadsCreated, long leadsSigned, BigDecimal leadConversionRate, BigDecimal signingCycleDaysAvg,
                                 long removalCounted, long removalExpected, BigDecimal removalRecoveryRate) {
    }

    private final JdbcTemplate jdbc;

    public OpsFlowMetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OpsFlowMetrics metrics(LocalDate from, LocalDate to) {
        Object[] w = {from.atStartOfDay(), to.atStartOfDay()};

        Map<String, Object> sla = jdbc.queryForMap("SELECT COUNT(*) n, COALESCE(SUM(CASE WHEN s.resolve_breached = 0 THEN 1 ELSE 0 END), 0) ok"
                + " FROM wo_order o JOIN wo_sla s ON s.wo_no = o.wo_no WHERE o.status IN ('DONE','AUDITED','CLOSED')"
                + " AND o.created_at >= ? AND o.created_at < ?", w);
        Map<String, Object> mttr = jdbc.queryForMap("SELECT COUNT(*) n, AVG(TIMESTAMPDIFF(MINUTE, o.created_at, h.last_at)) m FROM wo_order o"
                + " JOIN (SELECT wo_no, MAX(created_at) last_at FROM wo_handle GROUP BY wo_no) h ON h.wo_no = o.wo_no"
                + " WHERE o.type = 'FAULT' AND o.status IN ('DONE','AUDITED','CLOSED') AND o.created_at >= ? AND o.created_at < ?", w);
        Map<String, Object> renew = jdbc.queryForMap("SELECT COUNT(*) n, COALESCE(SUM(CASE WHEN EXISTS (SELECT 1 FROM loc_contract r"
                + " WHERE r.prev_contract_no = c.contract_no AND r.deleted = 0 AND r.status <> 'DRAFT') THEN 1 ELSE 0 END), 0) ok"
                + " FROM loc_contract c WHERE c.deleted = 0 AND c.status = 'EXPIRED' AND COALESCE(c.contract_kind, 'MAIN') = 'MAIN'"
                + " AND c.end_at >= ? AND c.end_at < ?", from.toString(), to.toString());
        Map<String, Object> install = jdbc.queryForMap("SELECT COUNT(*) n, AVG(DATEDIFF(s.first_live_at, (SELECT MIN(c.activated_at)"
                + " FROM loc_contract c WHERE c.site_no = s.site_no AND c.deleted = 0 AND c.activated_at IS NOT NULL))) d FROM loc_site s"
                + " WHERE s.deleted = 0 AND s.first_live_at >= ? AND s.first_live_at < ?", w);
        Map<String, Object> trial = jdbc.queryForMap("SELECT COUNT(*) n, COALESCE(SUM(CASE WHEN t.status = 'PASSED' THEN 1 ELSE 0 END), 0) ok"
                + " FROM dev_trial_rent t JOIN (SELECT cabinet_no, MIN(id) first_id FROM dev_trial_rent WHERE deleted = 0 GROUP BY cabinet_no) f"
                + " ON f.first_id = t.id WHERE t.created_at >= ? AND t.created_at < ?", w);
        Map<String, Object> leads = jdbc.queryForMap("SELECT COUNT(*) n, COALESCE(SUM(CASE WHEN l.stage = 'SIGNED' THEN 1 ELSE 0 END), 0) ok,"
                + " AVG(CASE WHEN l.stage = 'SIGNED' THEN DATEDIFF(c.activated_at, l.created_at) END) d FROM loc_lead l"
                + " LEFT JOIN loc_contract c ON c.contract_no = l.contract_no AND c.activated_at IS NOT NULL"
                + " WHERE l.deleted = 0 AND l.created_at >= ? AND l.created_at < ?", w);
        Map<String, Object> removal = jdbc.queryForMap("SELECT COALESCE(SUM(h.counted_qty), 0) counted,"
                + " COALESCE(SUM(COALESCE(d.expected_qty, h.counted_qty)), 0) expected FROM wo_order o"
                + " JOIN wo_handle h ON h.wo_no = o.wo_no AND h.counted_qty IS NOT NULL"
                + " LEFT JOIN inv_asset_diff d ON d.source_type = 'REMOVAL' AND d.source_ref = o.wo_no"
                + " WHERE o.type = 'REMOVE' AND o.status IN ('DONE','AUDITED','CLOSED') AND h.created_at >= ? AND h.created_at < ?", w);

        long slaN = l(sla.get("n")), ended = l(renew.get("n")), renewed = l(renew.get("ok"));
        BigDecimal renewalRate = ratio(renewed, ended);
        long counted = l(removal.get("counted")), expected = l(removal.get("expected"));
        return new OpsFlowMetrics(from, to,
                slaN, ratio(l(sla.get("ok")), slaN), l(mttr.get("n")), mttr.get("m") == null ? null : Math.round(((Number) mttr.get("m")).doubleValue()),
                ended, renewed, renewalRate, renewalRate == null ? null : BigDecimal.ONE.subtract(renewalRate),
                l(install.get("n")), dec(install.get("d")),
                l(trial.get("n")), ratio(l(trial.get("ok")), l(trial.get("n"))),
                l(leads.get("n")), l(leads.get("ok")), ratio(l(leads.get("ok")), l(leads.get("n"))), dec(leads.get("d")),
                counted, expected, expected == 0 ? null : ratio(Math.min(counted, expected), expected));
    }

    private static long l(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static BigDecimal ratio(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal dec(Object v) {
        return v == null ? null : new BigDecimal(String.valueOf(v)).setScale(1, RoundingMode.HALF_UP);
    }
}
