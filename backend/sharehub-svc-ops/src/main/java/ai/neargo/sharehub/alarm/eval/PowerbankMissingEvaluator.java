package ai.neargo.sharehub.alarm.eval;

import ai.neargo.sharehub.alarm.AlarmCause;
import ai.neargo.sharehub.alarm.AlarmSubjectType;
import ai.neargo.sharehub.alarm.ImpactScope;
import ai.neargo.sharehub.api.core.port.CabinetStatePort;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 充电宝失联（对齐清单 E1 · 资产）：借出中却没有进行中订单、也不在在途调拨里，超过 N 小时（默认 24）。
 * 待办给运维仓管核查最后位置；宝重新出现（归还识别 / 调拨）即不再成立、自动恢复。
 */
@Component
public class PowerbankMissingEvaluator implements StateEvaluator {

    public static final String POWERBANK_MISSING = "POWERBANK_MISSING";

    private final CabinetStatePort cabinets;
    private final SysParamPort params;

    public PowerbankMissingEvaluator(CabinetStatePort cabinets, SysParamPort params) {
        this.cabinets = cabinets;
        this.params = params;
    }

    @Override
    public Set<String> codes() {
        return Set.of(POWERBANK_MISSING);
    }

    /** 按宝全局判定，不跟站点批次走。 */
    @Override
    public boolean siteBatched() {
        return false;
    }

    @Override
    public List<Finding> evaluate(EvalScope scope, LocalDateTime now) {
        int hours = params.intOf("asset.powerbank.missing_hours", 24);
        return cabinets.missingPowerbanks(hours, 500).stream()
                .map(p -> new Finding(POWERBANK_MISSING, AlarmSubjectType.POWERBANK, p.powerbankNo(), p.lastSiteNo(), null, null, null,
                        AlarmCause.MISSING, ImpactScope.ENTITY, 0,
                        List.of(new Finding.Evidence("NO_ORDER", null, null, p.lastChangedAt(),
                                "最后订单 " + (p.lastOrderNo() == null ? "无" : p.lastOrderNo()) + "，状态 " + p.status())),
                        Map.of()))
                .toList();
    }
}
