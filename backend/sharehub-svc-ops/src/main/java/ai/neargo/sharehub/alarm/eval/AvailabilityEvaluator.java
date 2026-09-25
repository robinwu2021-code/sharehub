package ai.neargo.sharehub.alarm.eval;

import ai.neargo.sharehub.alarm.AlarmCause;
import ai.neargo.sharehub.alarm.AlarmSubjectType;
import ai.neargo.sharehub.alarm.ImpactScope;
import ai.neargo.sharehub.api.core.dto.CabinetAvailability;
import ai.neargo.sharehub.api.core.port.CabinetStatePort;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 可借 / 可还（TDD/05 §4.1）。只判营业中的站点；站点级「整站借不到 / 还不了」取代柜级。
 *
 * <p>「在线」= 最近心跳 ≤ 3 分钟（设备不会主动报离线）；「被停借」只算信号与人工持有的停借 ——
 * 告警自己申请的停借不算，否则判定会被自己的输出锁死。
 */
@Component
public class AvailabilityEvaluator implements StateEvaluator {

    public static final String SITE_UNRENTABLE = "SITE_UNRENTABLE";
    public static final String CABINET_UNRENTABLE = "CABINET_UNRENTABLE";
    public static final String SITE_UNRETURNABLE = "SITE_UNRETURNABLE";
    public static final String CABINET_UNRETURNABLE = "CABINET_UNRETURNABLE";

    private final CabinetStatePort cabinets;

    public AvailabilityEvaluator(CabinetStatePort cabinets) {
        this.cabinets = cabinets;
    }

    @Override
    public Set<String> codes() {
        return Set.of(SITE_UNRENTABLE, CABINET_UNRENTABLE, SITE_UNRETURNABLE, CABINET_UNRETURNABLE);
    }

    @Override
    public List<Finding> evaluate(EvalScope scope, LocalDateTime now) {
        List<SiteBrief> open = scope.sites().stream().filter(SiteBrief::rentable).toList();
        if (open.isEmpty()) return List.of();
        Map<String, List<CabinetAvailability>> bySite = cabinets.availabilityBySites(open.stream().map(SiteBrief::siteNo).toList())
                .stream().collect(Collectors.groupingBy(CabinetAvailability::siteNo));
        List<Finding> out = new ArrayList<>();
        for (SiteBrief s : open) {
            List<CabinetAvailability> cabs = bySite.getOrDefault(s.siteNo(), List.of());
            if (cabs.isEmpty()) continue;
            int inFlight = cabs.stream().mapToInt(CabinetAvailability::inFlightOrders).sum();
            judge(out, s, cabs, inFlight, true);
            judge(out, s, cabs, inFlight, false);
        }
        return out;
    }

    private void judge(List<Finding> out, SiteBrief s, List<CabinetAvailability> cabs, int inFlight, boolean rent) {
        List<CabinetAvailability> bad = cabs.stream().filter(c -> !(rent ? rentable(c) : returnable(c))).toList();
        if (bad.isEmpty()) return;
        List<Finding.Evidence> ev = bad.stream().limit(20)
                .map(c -> new Finding.Evidence(reasonOf(c, rent), c.cabinetNo(), null, c.lastHeartbeatAt(), c.status())).toList();
        if (bad.size() == cabs.size()) {
            out.add(new Finding(rent ? SITE_UNRENTABLE : SITE_UNRETURNABLE, AlarmSubjectType.SITE, s.siteNo(), s.siteNo(), null,
                    s.agentNo(), s.regionId(), causeOf(cabs, rent), ImpactScope.SITE, inFlight, ev, Map.of()));
            return;
        }
        for (CabinetAvailability c : bad) {
            out.add(new Finding(rent ? CABINET_UNRENTABLE : CABINET_UNRETURNABLE, AlarmSubjectType.CABINET, c.cabinetNo(),
                    s.siteNo(), c.cabinetNo(), c.agentNo(), s.regionId(), causeOf(List.of(c), rent), ImpactScope.CABINET,
                    c.inFlightOrders(), List.of(new Finding.Evidence(reasonOf(c, rent), c.cabinetNo(), null, c.lastHeartbeatAt(), c.status())),
                    Map.of()));
        }
    }

    static boolean rentable(CabinetAvailability c) {
        return c.online() && !c.rentBlocked() && c.rentableSlots() > 0;
    }

    static boolean returnable(CabinetAvailability c) {
        return c.online() && c.returnableSlots() > 0;
    }

    static AlarmCause causeOf(List<CabinetAvailability> cabs, boolean rent) {
        if (cabs.stream().noneMatch(CabinetAvailability::online)) return AlarmCause.OFFLINE;
        List<CabinetAvailability> online = cabs.stream().filter(CabinetAvailability::online).toList();
        boolean allOnlineHealthy = online.size() == cabs.size();
        if (rent) {
            if (online.stream().allMatch(CabinetAvailability::rentBlocked)) return allOnlineHealthy ? AlarmCause.FAULT : AlarmCause.MIXED;
            if (online.stream().allMatch(c -> c.rentBlocked() || c.rentableSlots() == 0)) {
                return allOnlineHealthy ? AlarmCause.NO_STOCK : AlarmCause.MIXED;
            }
        } else if (online.stream().allMatch(c -> c.returnableSlots() == 0)) {
            return allOnlineHealthy ? AlarmCause.FULL : AlarmCause.MIXED;
        }
        return AlarmCause.MIXED;
    }

    private static String reasonOf(CabinetAvailability c, boolean rent) {
        if (!c.online()) return "OFFLINE";
        if (rent) return c.rentBlocked() ? "RENT_BLOCKED" : "NO_STOCK";
        return "FULL";
    }
}
