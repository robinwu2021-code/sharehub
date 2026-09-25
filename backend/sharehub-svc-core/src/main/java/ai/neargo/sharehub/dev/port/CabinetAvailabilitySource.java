package ai.neargo.sharehub.dev.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 可借可还判定的原料（按柜批量，豁免数据范围）。与 {@link LocalCabinetState} 分开：那边只做口径组合，这里只取数。
 */
@Component
public class CabinetAvailabilitySource {

    private final PowerbankMapper powerbanks;
    private final JdbcTemplate jdbc;

    public CabinetAvailabilitySource(PowerbankMapper powerbanks, JdbcTemplate jdbc) {
        this.powerbanks = powerbanks;
        this.jdbc = jdbc;
    }

    /** 各柜在柜宝数。 */
    public Map<String, Long> inCabinetCounts(Collection<String> cabinetNos) {
        Map<String, Long> out = new HashMap<>();
        DataScopeContext.executeWithoutScope(() -> powerbanks.selectMaps(new QueryWrapper<DevPowerbank>()
                        .select("cabinet_no AS cabinetNo", "COUNT(*) AS cnt").in("cabinet_no", cabinetNos)
                        .eq("status", PowerbankStatus.IN_CABINET.name()).isNull("archived_at").groupBy("cabinet_no")))
                .forEach(m -> out.put(String.valueOf(m.get("cabinetNo")), ((Number) m.get("cnt")).longValue()));
        return out;
    }

    /**
     * 各柜从该柜借出、尚未归还的订单数。
     *
     * <p>直接读 {@code ord_order} 而不经 trade 的 mapper：dev → trade 会与现有的 trade → dev 成环
     * （ArchitectureTest.noCyclesBetweenDomains）。状态值与 {@code OrderStatus} 同名。
     */
    public Map<String, Long> inFlightOrders(Collection<String> cabinetNos) {
        if (cabinetNos.isEmpty()) return Map.of();
        String in = String.join(",", java.util.Collections.nCopies(cabinetNos.size(), "?"));
        Map<String, Long> out = new HashMap<>();
        jdbc.query("SELECT cabinet_no, COUNT(*) c FROM ord_order WHERE deleted = 0 AND status IN ('DISPENSING', 'IN_USE')"
                + " AND cabinet_no IN (" + in + ") GROUP BY cabinet_no",
                rs -> { out.put(rs.getString(1), rs.getLong(2)); }, cabinetNos.toArray());
        return out;
    }

    /**
     * 有生效中整柜停借保护（slot_index = -1、动作 STOP_RENT）的柜。
     *
     * <p><b>不算业务告警自己持有的停借</b>：离线告警会以告警身份申请整柜停借，若它也算进「不可借」，
     * 心跳恢复后柜子仍被判为不可借 → 告警永远不恢复 → 停借永远不释放。判定输入不能包含判定自己的输出。
     */
    public Set<String> rentBlockedCabinets(Collection<String> cabinetNos) {
        if (cabinetNos.isEmpty()) return Set.of();
        String in = String.join(",", java.util.Collections.nCopies(cabinetNos.size(), "?"));
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT cabinet_no FROM dev_protection WHERE active = 1"
                + " AND slot_index = -1 AND action = 'STOP_RENT' AND holder_type <> 'ALARM' AND cabinet_no IN (" + in + ")", String.class, cabinetNos.toArray()));
    }

    /** 在柜宝况：cabinetNo → [在柜数, 低电数（电量 < minBattery）, 老化数（health=AGED）]。 */
    public Map<String, int[]> powerProfile(Collection<String> cabinetNos, int minBattery) {
        if (cabinetNos == null || cabinetNos.isEmpty()) return Map.of();
        Map<String, int[]> out = new java.util.HashMap<>();
        String in = String.join(",", java.util.Collections.nCopies(cabinetNos.size(), "?"));
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(minBattery);
        args.addAll(cabinetNos);
        jdbc.query("SELECT cabinet_no, COUNT(*) n, SUM(CASE WHEN battery IS NOT NULL AND battery < ? THEN 1 ELSE 0 END) low,"
                        + " SUM(CASE WHEN health = 'AGED' THEN 1 ELSE 0 END) aged FROM dev_powerbank"
                        + " WHERE deleted = 0 AND archived_at IS NULL AND status = 'IN_CABINET' AND cabinet_no IN (" + in + ") GROUP BY cabinet_no",
                rs -> {
                    out.put(rs.getString("cabinet_no"), new int[]{rs.getInt("n"), rs.getInt("low"), rs.getInt("aged")});
                }, args.toArray());
        return out;
    }

    /** 窗口计数：cabinetNo → since 之后该信号的次数。 */
    public Map<String, Integer> signalCounts(Collection<String> cabinetNos, String code, java.time.LocalDateTime since) {
        if (cabinetNos == null || cabinetNos.isEmpty()) return Map.of();
        Map<String, Integer> out = new java.util.HashMap<>();
        String in = String.join(",", java.util.Collections.nCopies(cabinetNos.size(), "?"));
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(code);
        args.add(since);
        args.addAll(cabinetNos);
        jdbc.query("SELECT cabinet_no, COUNT(*) c FROM dev_signal_log WHERE code = ? AND occurred_at >= ? AND cabinet_no IN (" + in + ")"
                + " GROUP BY cabinet_no", rs -> {
            out.put(rs.getString("cabinet_no"), rs.getInt("c"));
        }, args.toArray());
        return out;
    }

    /** 失联的宝：借出中、无进行中订单、不在在途调拨，且 updated_at 早于 cutoff。订单表直接用 SQL 读 —— dev 不依赖 trade 包。 */
    public java.util.List<ai.neargo.sharehub.api.core.port.CabinetStatePort.MissingPowerbank> missingPowerbanks(
            java.time.LocalDateTime cutoff, int limit) {
        return jdbc.query("SELECT p.powerbank_no, p.status, p.updated_at,"
                        + " (SELECT o.order_no FROM ord_order o WHERE o.powerbank_no = p.powerbank_no ORDER BY o.id DESC LIMIT 1) last_order,"
                        + " (SELECT o.site_no FROM ord_order o WHERE o.powerbank_no = p.powerbank_no ORDER BY o.id DESC LIMIT 1) last_site"
                        + " FROM dev_powerbank p WHERE p.deleted = 0 AND p.archived_at IS NULL AND p.status = 'RENTED' AND p.updated_at < ?"
                        + " AND NOT EXISTS (SELECT 1 FROM ord_order o WHERE o.deleted = 0 AND o.powerbank_no = p.powerbank_no"
                        + "   AND o.status IN ('DISPENSING', 'IN_USE'))"
                        + " AND NOT EXISTS (SELECT 1 FROM inv_transfer_item i JOIN inv_transfer t ON t.transfer_no = i.transfer_no"
                        + "   WHERE i.powerbank_no = p.powerbank_no AND i.deleted = 0 AND t.deleted = 0 AND t.status = 'IN_TRANSIT')"
                        + " ORDER BY p.id LIMIT ?",
                (rs, n) -> new ai.neargo.sharehub.api.core.port.CabinetStatePort.MissingPowerbank(rs.getString("powerbank_no"),
                        rs.getString("status"), rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime(),
                        rs.getString("last_order"), rs.getString("last_site")),
                cutoff, Math.max(1, Math.min(limit, 1000)));
    }
}
