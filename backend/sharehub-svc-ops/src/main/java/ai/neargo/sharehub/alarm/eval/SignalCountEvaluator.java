package ai.neargo.sharehub.alarm.eval;

import ai.neargo.sharehub.alarm.AlarmCause;
import ai.neargo.sharehub.alarm.AlarmSubjectType;
import ai.neargo.sharehub.alarm.ImpactScope;
import ai.neargo.sharehub.alarm.entity.DevAlarmCode;
import ai.neargo.sharehub.alarm.mapper.DevAlarmCodeMapper;
import ai.neargo.sharehub.api.core.port.CabinetStatePort;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 窗口计数型判定（对齐清单 E1，eval_type=COUNT）：同一柜在窗口内某信号的次数达到阈值。
 * 窗口与阈值读告警码配置（运营可调）：
 * <ul>
 *   <li>{@code EJECT_FAIL_RATE}：弹出失败（EJECT_TIMEOUT）60 分钟 ≥ 3 次 → 柜子停借（告警持有）+ 维修单；</li>
 *   <li>{@code FREQUENT_OFFLINE}：掉线后恢复（LINK_RESTORED）24 小时 ≥ 6 次（即「> 5 次」）→ 查网络电源单。</li>
 * </ul>
 * 单次弹出失败不是业务告警（订单已自动撤单，用户没有损失），只有反复失败才伤到业务。
 */
@Component
public class SignalCountEvaluator implements StateEvaluator {

    public static final String EJECT_FAIL_RATE = "EJECT_FAIL_RATE";
    public static final String FREQUENT_OFFLINE = "FREQUENT_OFFLINE";
    private static final Map<String, String> SIGNAL = Map.of(EJECT_FAIL_RATE, "EJECT_TIMEOUT", FREQUENT_OFFLINE, "LINK_RESTORED");

    private final CabinetStatePort cabinets;
    private final DevAlarmCodeMapper codes;

    public SignalCountEvaluator(CabinetStatePort cabinets, DevAlarmCodeMapper codes) {
        this.cabinets = cabinets;
        this.codes = codes;
    }

    @Override
    public Set<String> codes() {
        return SIGNAL.keySet();
    }

    @Override
    public List<Finding> evaluate(EvalScope scope, LocalDateTime now) {
        List<SiteBrief> sites = scope.sites();
        if (sites.isEmpty()) return List.of();
        Map<String, SiteBrief> bySite = sites.stream().collect(Collectors.toMap(SiteBrief::siteNo, s -> s, (a, b) -> a));
        Map<String, DevAlarmCode> config = codes.selectList(new LambdaQueryWrapper<DevAlarmCode>().in(DevAlarmCode::getCode, SIGNAL.keySet()))
                .stream().collect(Collectors.toMap(DevAlarmCode::getCode, c -> c, (a, b) -> a));
        List<Finding> out = new ArrayList<>();
        for (var en : SIGNAL.entrySet()) {
            DevAlarmCode c = config.get(en.getKey());
            if (c == null || c.getWindowMinutes() == null || c.getThreshold() == null) continue;
            int threshold = c.getThreshold().setScale(0, java.math.RoundingMode.CEILING).intValue();
            Map<String, Integer> counts = cabinets.signalCounts(bySite.keySet(), en.getValue(), now.minusMinutes(c.getWindowMinutes()));
            Map<String, String> siteOf = cabinets.availabilityBySites(bySite.keySet()).stream()
                    .collect(Collectors.toMap(a -> a.cabinetNo(), a -> a.siteNo(), (a, b) -> a));
            for (var cnt : counts.entrySet()) {
                if (cnt.getValue() < threshold) continue;
                SiteBrief s = bySite.get(siteOf.get(cnt.getKey()));
                if (s == null) continue;
                boolean eject = EJECT_FAIL_RATE.equals(en.getKey());
                out.add(new Finding(en.getKey(), AlarmSubjectType.CABINET, cnt.getKey(), s.siteNo(), cnt.getKey(), s.agentNo(), s.regionId(),
                        eject ? AlarmCause.FAULT : AlarmCause.UNSTABLE, ImpactScope.CABINET, 0,
                        List.of(new Finding.Evidence(en.getValue(), cnt.getKey(), null, now,
                                c.getWindowMinutes() + " 分钟内 " + cnt.getValue() + " 次（阈值 " + threshold + "）")),
                        eject ? Map.of(Finding.ATTR_STOP_RENT, "true") : Map.of()));
            }
        }
        return out;
    }
}
