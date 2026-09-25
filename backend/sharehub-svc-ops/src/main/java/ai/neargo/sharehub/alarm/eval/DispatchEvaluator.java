package ai.neargo.sharehub.alarm.eval;

import ai.neargo.sharehub.alarm.AlarmCause;
import ai.neargo.sharehub.alarm.AlarmSubjectType;
import ai.neargo.sharehub.alarm.ImpactScope;
import ai.neargo.sharehub.api.core.dto.CabinetAvailability;
import ai.neargo.sharehub.api.core.port.CabinetStatePort;
import ai.neargo.sharehub.api.core.port.CabinetStatePort.CabinetPower;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 调度类判定（对齐清单 D1 / D2）。
 *
 * <ul>
 *   <li>{@code RENTABLE_LOW}：站点里有柜子「还能借、但快借空了」—— 可借 1..N 颗，或可借率低于下限；</li>
 *   <li>{@code RETURN_SPACE_LOW}：有柜子「还能还、但快满了」—— 空仓 1..N 个；</li>
 *   <li>{@code LOW_BATTERY_PILEUP}：单柜在柜宝里低电占比过高 —— 宝在、却一直充不上电，多半是仓位充电故障；</li>
 *   <li>{@code POWERBANK_AGED}：单柜里有老化待报废的宝（已停止借出），随调度换下回收。</li>
 * </ul>
 *
 * <p><b>可借 / 空仓为 0 的柜子不在这里</b>：那是「借不到 / 还不了」告警的事（影响更大、优先级更高）。
 * 两边开出来的补宝 / 取宝单按「区域 × 当天」合并成同一张调度单（引擎 mergeKey 对 REFILL 的规则），所以不会重复派人。
 */
@Component
public class DispatchEvaluator implements StateEvaluator {

    public static final String RENTABLE_LOW = "RENTABLE_LOW";
    public static final String RETURN_SPACE_LOW = "RETURN_SPACE_LOW";
    public static final String LOW_BATTERY_PILEUP = "LOW_BATTERY_PILEUP";
    public static final String POWERBANK_AGED = "POWERBANK_AGED";

    private final CabinetStatePort cabinets;
    private final SysParamPort params;

    public DispatchEvaluator(CabinetStatePort cabinets, SysParamPort params) {
        this.cabinets = cabinets;
        this.params = params;
    }

    @Override
    public Set<String> codes() {
        return Set.of(RENTABLE_LOW, RETURN_SPACE_LOW, LOW_BATTERY_PILEUP, POWERBANK_AGED);
    }

    @Override
    public List<Finding> evaluate(EvalScope scope, LocalDateTime now) {
        List<SiteBrief> open = scope.sites().stream().filter(SiteBrief::rentable).toList();
        if (open.isEmpty()) return List.of();
        List<String> siteNos = open.stream().map(SiteBrief::siteNo).toList();
        int maxRentable = params.intOf("dispatch.refill.max_rentable", 1);
        BigDecimal minRatio = params.decimalOf("dispatch.refill.min_ratio", new BigDecimal("0.2"));
        int maxEmpty = params.intOf("dispatch.pickup.max_empty", 1);
        BigDecimal lowRatio = params.decimalOf("dispatch.low_battery.ratio", new BigDecimal("0.5"));

        Map<String, List<CabinetAvailability>> avail = cabinets.availabilityBySites(siteNos).stream()
                .collect(Collectors.groupingBy(CabinetAvailability::siteNo));
        Map<String, List<CabinetPower>> power = cabinets.powerBySites(siteNos).stream()
                .collect(Collectors.groupingBy(CabinetPower::siteNo));
        List<Finding> out = new ArrayList<>();
        for (SiteBrief s : open) {
            List<CabinetAvailability> cabs = avail.getOrDefault(s.siteNo(), List.of()).stream()
                    .filter(CabinetAvailability::online).filter(c -> !c.rentBlocked()).toList();
            List<CabinetAvailability> lowStock = cabs.stream().filter(c -> c.rentableSlots() > 0
                    && (c.rentableSlots() <= maxRentable || ratio(c.rentableSlots(), c.slotTotal()).compareTo(minRatio) < 0)).toList();
            if (!lowStock.isEmpty()) {
                out.add(siteFinding(RENTABLE_LOW, s, AlarmCause.NO_STOCK, lowStock.stream().map(c -> new Finding.Evidence("LOW_STOCK",
                        c.cabinetNo(), null, now, "可借 " + c.rentableSlots() + " / " + c.slotTotal())).toList()));
            }
            List<CabinetAvailability> nearFull = cabs.stream().filter(c -> c.returnableSlots() > 0 && c.returnableSlots() <= maxEmpty).toList();
            if (!nearFull.isEmpty()) {
                out.add(siteFinding(RETURN_SPACE_LOW, s, AlarmCause.FULL, nearFull.stream().map(c -> new Finding.Evidence("NEAR_FULL",
                        c.cabinetNo(), null, now, "空仓 " + c.returnableSlots() + " / " + c.slotTotal())).toList()));
            }
            for (CabinetPower p : power.getOrDefault(s.siteNo(), List.of())) {
                if (p.inCabinet() >= 2 && ratio(p.lowBattery(), p.inCabinet()).compareTo(lowRatio) >= 0) {
                    out.add(cabinetFinding(LOW_BATTERY_PILEUP, s, p, AlarmCause.LOW_BATTERY, "低电 " + p.lowBattery() + " / 在柜 " + p.inCabinet(), now));
                }
                if (p.aged() > 0) {
                    out.add(cabinetFinding(POWERBANK_AGED, s, p, AlarmCause.AGED, "老化待报废 " + p.aged() + " 颗", now));
                }
            }
        }
        return out;
    }

    private static Finding siteFinding(String code, SiteBrief s, AlarmCause cause, List<Finding.Evidence> ev) {
        return new Finding(code, AlarmSubjectType.SITE, s.siteNo(), s.siteNo(), null, s.agentNo(), s.regionId(), cause,
                ImpactScope.SITE, 0, ev.stream().limit(20).toList(), Map.of());
    }

    private static Finding cabinetFinding(String code, SiteBrief s, CabinetPower p, AlarmCause cause, String note, LocalDateTime now) {
        return new Finding(code, AlarmSubjectType.CABINET, p.cabinetNo(), s.siteNo(), p.cabinetNo(), p.agentNo(), s.regionId(), cause,
                ImpactScope.CABINET, 0, List.of(new Finding.Evidence(cause.name(), p.cabinetNo(), null, now, note)), Map.of());
    }

    private static BigDecimal ratio(int part, int whole) {
        return whole <= 0 ? BigDecimal.ONE : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, java.math.RoundingMode.HALF_UP);
    }
}
