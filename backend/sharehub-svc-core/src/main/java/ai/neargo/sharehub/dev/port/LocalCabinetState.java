package ai.neargo.sharehub.dev.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.core.dto.CabinetAvailability;
import ai.neargo.sharehub.api.core.port.CabinetStatePort;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link CabinetStatePort} 的本地实现（系统读，豁免数据范围）。
 *
 * <p>可借可还口径（TDD/04 §4.4）：在线 = 最近心跳 ≤ 3 分钟；可借宝数以设备上报的 {@code available_count} 为准，
 * 被整柜停借保护的柜子视为 0；可还空仓 = 总仓数 − 在柜宝数。仓位级保护（锁仓）接入后在此扣减。
 */
@Service
public class LocalCabinetState implements CabinetStatePort {

    static final int ONLINE_WINDOW_MIN = 3;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CabinetMapper cabinets;
    private final CabinetAvailabilitySource availability;

    private final int minBattery;

    public LocalCabinetState(CabinetMapper cabinets, CabinetAvailabilitySource availability,
                             @org.springframework.beans.factory.annotation.Value("${sharehub.rent.min-battery:60}") int minBattery) {
        this.cabinets = cabinets;
        this.availability = availability;
        this.minBattery = minBattery;
    }

    @Override
    public Map<String, Integer> signalCounts(Collection<String> siteNos, String code, LocalDateTime since) {
        if (siteNos == null || siteNos.isEmpty()) return Map.of();
        List<String> nos = DataScopeContext.executeWithoutScope(() -> cabinets.selectList(new LambdaQueryWrapper<DevCabinet>()
                        .select(DevCabinet::getCabinetNo).in(DevCabinet::getSiteNo, siteNos)
                        .in(DevCabinet::getStatus, List.of(CabinetStatus.DEPLOYED.name(), CabinetStatus.FAULT.name()))
                        .isNull(DevCabinet::getArchivedAt)))
                .stream().map(DevCabinet::getCabinetNo).toList();
        return availability.signalCounts(nos, code, since);
    }

    @Override
    public List<MissingPowerbank> missingPowerbanks(int hours, int limit) {
        return availability.missingPowerbanks(LocalDateTime.now().minusHours(hours), limit);
    }

    @Override
    public List<CabinetPower> powerBySites(Collection<String> siteNos) {
        if (siteNos == null || siteNos.isEmpty()) return List.of();
        List<DevCabinet> rows = DataScopeContext.executeWithoutScope(() -> cabinets.selectList(new LambdaQueryWrapper<DevCabinet>()
                .in(DevCabinet::getSiteNo, siteNos).in(DevCabinet::getStatus, List.of(CabinetStatus.DEPLOYED.name(), CabinetStatus.FAULT.name()))
                .isNull(DevCabinet::getArchivedAt)));
        if (rows.isEmpty()) return List.of();
        Map<String, int[]> power = availability.powerProfile(rows.stream().map(DevCabinet::getCabinetNo).toList(), minBattery);
        return rows.stream().filter(c -> power.containsKey(c.getCabinetNo())).map(c -> {
            int[] p = power.get(c.getCabinetNo());
            return new CabinetPower(c.getCabinetNo(), c.getSiteNo(), c.getAgentNo(), p[0], p[1], p[2]);
        }).toList();
    }

    @Override
    public Map<String, Long> countBySites(Collection<String> siteNos, Set<String> statuses) {
        if (siteNos == null || siteNos.isEmpty() || statuses == null || statuses.isEmpty()) return Map.of();
        Map<String, Long> out = new HashMap<>();
        DataScopeContext.executeWithoutScope(() -> cabinets.selectMaps(new QueryWrapper<DevCabinet>()
                        .select("site_no AS siteNo", "COUNT(*) AS cnt").in("site_no", siteNos).in("status", statuses)
                        .isNull("archived_at").groupBy("site_no")))
                .forEach(m -> out.put(String.valueOf(m.get("siteNo")), ((Number) m.get("cnt")).longValue()));
        return out;
    }

    @Override
    public List<CabinetAvailability> availabilityBySites(Collection<String> siteNos) {
        if (siteNos == null || siteNos.isEmpty()) return List.of();
        List<DevCabinet> rows = DataScopeContext.executeWithoutScope(() -> cabinets.selectList(new LambdaQueryWrapper<DevCabinet>()
                .in(DevCabinet::getSiteNo, siteNos).in(DevCabinet::getStatus, List.of(CabinetStatus.DEPLOYED.name(), CabinetStatus.FAULT.name()))
                .isNull(DevCabinet::getArchivedAt)));
        if (rows.isEmpty()) return List.of();
        List<String> nos = rows.stream().map(DevCabinet::getCabinetNo).toList();
        Map<String, Long> inCabinet = availability.inCabinetCounts(nos);
        Set<String> blocked = availability.rentBlockedCabinets(nos);
        Map<String, Long> inFlight = availability.inFlightOrders(nos);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(ONLINE_WINDOW_MIN);
        return rows.stream().map(c -> {
            LocalDateTime hb = parse(c.getLastHeartbeatAt());
            boolean online = hb != null && !hb.isBefore(cutoff);
            boolean rentBlocked = blocked.contains(c.getCabinetNo());
            int total = c.getSlotTotal() == null ? 0 : c.getSlotTotal();
            int rentable = rentBlocked || !online ? 0 : Math.max(0, c.getAvailableCount() == null ? 0 : c.getAvailableCount());
            int returnable = !online ? 0 : Math.max(0, total - inCabinet.getOrDefault(c.getCabinetNo(), 0L).intValue());
            return new CabinetAvailability(c.getCabinetNo(), c.getSiteNo(), c.getAgentNo(), c.getStatus(), online, hb,
                    rentBlocked, total, rentable, returnable, inFlight.getOrDefault(c.getCabinetNo(), 0L).intValue());
        }).toList();
    }

    private static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            String t = s.replace('T', ' ');
            return LocalDateTime.parse(t.length() > 19 ? t.substring(0, 19) : t, TS);   // 秒级足够判 3 分钟窗口
        } catch (RuntimeException e) {
            return null;
        }
    }
}
