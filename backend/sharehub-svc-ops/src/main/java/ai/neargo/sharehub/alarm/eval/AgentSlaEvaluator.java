package ai.neargo.sharehub.alarm.eval;

import ai.neargo.sharehub.alarm.AlarmCause;
import ai.neargo.sharehub.alarm.AlarmSubjectType;
import ai.neargo.sharehub.alarm.ImpactScope;
import ai.neargo.sharehub.alarm.entity.DevAlarmCode;
import ai.neargo.sharehub.alarm.mapper.DevAlarmCodeMapper;
import ai.neargo.sharehub.api.platform.port.AgentDirectoryPort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代理运维不达标（对齐清单 F5）：作用于本月的考核（= 上月结果）里 SLA 达成率低于告警码阈值（默认 0.8）→ BD 待办约谈。
 * 次月考核达标即不再成立、自动恢复。
 */
@Component
public class AgentSlaEvaluator implements StateEvaluator {

    public static final String AGENT_SLA_BELOW = "AGENT_SLA_BELOW";

    private final AgentDirectoryPort agents;
    private final DevAlarmCodeMapper codes;

    public AgentSlaEvaluator(AgentDirectoryPort agents, DevAlarmCodeMapper codes) {
        this.agents = agents;
        this.codes = codes;
    }

    @Override
    public Set<String> codes() {
        return Set.of(AGENT_SLA_BELOW);
    }

    @Override
    public boolean siteBatched() {
        return false;
    }

    @Override
    public List<Finding> evaluate(EvalScope scope, LocalDateTime now) {
        DevAlarmCode c = codes.selectOne(new LambdaQueryWrapper<DevAlarmCode>().eq(DevAlarmCode::getCode, AGENT_SLA_BELOW).last("limit 1"));
        BigDecimal threshold = c == null || c.getThreshold() == null ? new BigDecimal("0.8") : c.getThreshold();
        return agents.assessmentsBelow(YearMonth.from(now).toString(), threshold).stream()
                .map(a -> new Finding(AGENT_SLA_BELOW, AlarmSubjectType.AGENT, a.agentNo(), null, null, a.agentNo(), null,
                        AlarmCause.SLA_BELOW, ImpactScope.ENTITY, 0,
                        List.of(new Finding.Evidence("SLA_RATE", null, null, now, a.period() + " 达成率 " + a.slaRate()
                                + "（" + a.woTotal() + " 单，被接管 " + a.takenOver() + "），本月运维分成系数 " + a.coefficient())),
                        Map.of()))
                .toList();
    }
}
