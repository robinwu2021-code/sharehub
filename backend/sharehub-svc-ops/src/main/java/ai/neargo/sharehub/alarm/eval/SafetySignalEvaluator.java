package ai.neargo.sharehub.alarm.eval;

import ai.neargo.sharehub.alarm.AlarmCause;
import ai.neargo.sharehub.alarm.AlarmSubjectType;
import ai.neargo.sharehub.alarm.ImpactScope;
import ai.neargo.sharehub.api.core.event.DeviceSignalEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 安全信号（TDD/05 §4.2）：电池异常 → BATTERY_HAZARD；机内高温 → CABINET_OVERHEAT。
 * 两码只随处置完成关闭（recover_rule=DISPOSITION_DONE），信号恢复不自动关 —— 安全隐患必须有人到场确认。
 */
@Component
public class SafetySignalEvaluator implements EventEvaluator<DeviceSignalEvent> {

    public static final String BATTERY_HAZARD = "BATTERY_HAZARD";
    public static final String CABINET_OVERHEAT = "CABINET_OVERHEAT";

    @Override
    public Class<DeviceSignalEvent> eventType() {
        return DeviceSignalEvent.class;
    }

    @Override
    public List<Finding> onEvent(DeviceSignalEvent s) {
        Finding.Evidence ev = new Finding.Evidence(s.code(), s.cabinetNo(), s.slotIndex(), s.occurredAtTime(), s.vendorErrorCode());
        return switch (s.code()) {
            case "BATTERY_ABNORMAL" -> {
                boolean byPb = s.powerbankNo() != null;
                String subject = byPb ? s.powerbankNo() : s.cabinetNo() + "#" + s.slotIndex();
                yield List.of(new Finding(BATTERY_HAZARD, byPb ? AlarmSubjectType.POWERBANK : AlarmSubjectType.SLOT, subject,
                        s.siteNo(), s.cabinetNo(), s.agentNo(), null, AlarmCause.HAZARD, ImpactScope.SLOT, 0, List.of(ev), Map.of()));
            }
            case "TEMP_HIGH" -> List.of(new Finding(CABINET_OVERHEAT, AlarmSubjectType.CABINET, s.cabinetNo(), s.siteNo(),
                    s.cabinetNo(), s.agentNo(), null, AlarmCause.OVERHEAT, ImpactScope.CABINET, 0, List.of(ev), Map.of()));
            default -> List.of();
        };
    }
}
