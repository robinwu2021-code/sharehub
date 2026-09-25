package ai.neargo.sharehub.agent.port;

import ai.neargo.sharehub.agent.entity.AgtAgent;
import ai.neargo.sharehub.agent.mapper.AgentMapper;
import ai.neargo.sharehub.api.platform.dto.AgentBrief;
import ai.neargo.sharehub.api.platform.dto.AgentType;
import ai.neargo.sharehub.api.platform.port.AgentDirectoryPort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AgentDirectoryPort} 的单体内实现 —— 直接读本域的 mapper。
 *
 * <p>命名与 {@code loc/port/LocalSiteQuery} 一致：{@code Local*} 表示「同进程内实现」，
 * 与 S5 之后的远程实现（{@code api/remote/}）区分。
 */
@Component
public class LocalAgentDirectory implements AgentDirectoryPort {

    private final AgentMapper agents;
    private final ai.neargo.sharehub.agent.ext.mapper.AgtExitMapper exits;
    private final ai.neargo.sharehub.agent.ext.mapper.AgtOpsAssessmentMapper assessments;

    public LocalAgentDirectory(AgentMapper agents, ai.neargo.sharehub.agent.ext.mapper.AgtExitMapper exits,
                               ai.neargo.sharehub.agent.ext.mapper.AgtOpsAssessmentMapper assessments) {
        this.agents = agents;
        this.exits = exits;
        this.assessments = assessments;
    }

    @Override
    public java.math.BigDecimal opsCoefficient(String agentNo, String period) {
        if (agentNo == null || period == null) return java.math.BigDecimal.ONE;
        var a = assessments.selectOne(new LambdaQueryWrapper<ai.neargo.sharehub.agent.ext.entity.AgtOpsAssessment>()
                .eq(ai.neargo.sharehub.agent.ext.entity.AgtOpsAssessment::getAgentNo, agentNo)
                .eq(ai.neargo.sharehub.agent.ext.entity.AgtOpsAssessment::getApplyPeriod, period).last("limit 1"));
        return a == null || a.getCoefficient() == null ? java.math.BigDecimal.ONE : a.getCoefficient();
    }

    @Override
    public List<OpsAssessmentBrief> assessmentsBelow(String applyPeriod, java.math.BigDecimal threshold) {
        return assessments.selectList(new LambdaQueryWrapper<ai.neargo.sharehub.agent.ext.entity.AgtOpsAssessment>()
                        .eq(ai.neargo.sharehub.agent.ext.entity.AgtOpsAssessment::getApplyPeriod, applyPeriod)
                        .isNotNull(ai.neargo.sharehub.agent.ext.entity.AgtOpsAssessment::getSlaRate)
                        .lt(ai.neargo.sharehub.agent.ext.entity.AgtOpsAssessment::getSlaRate, threshold))
                .stream().map(x -> new OpsAssessmentBrief(x.getAgentNo(), x.getPeriod(), x.getSlaRate(), x.getWoTotal(),
                        x.getTakenOver(), x.getCoefficient())).toList();
    }

    @Override
    public Map<String, AgentBrief> briefsOf(Collection<String> agentNos) {
        if (agentNos == null || agentNos.isEmpty()) return Map.of();
        List<String> nos = agentNos.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (nos.isEmpty()) return Map.of();

        // LinkedHashMap 而不是 Collectors.toMap：后者对重复键会抛，
        // 而 agent_no 上有唯一键、这里本不该重复 —— 但「本不该」不是让它在生产抛异常的理由。
        Map<String, AgentBrief> out = new LinkedHashMap<>();
        for (AgtAgent a : agents.selectList(new LambdaQueryWrapper<AgtAgent>().in(AgtAgent::getAgentNo, nos))) {
            out.putIfAbsent(a.getAgentNo(), toBrief(a));
        }
        return out;
    }

    @Override
    public AgentBrief briefOf(String agentNo) {
        if (agentNo == null || agentNo.isBlank()) return null;
        AgtAgent a = agents.selectOne(new LambdaQueryWrapper<AgtAgent>()
                .eq(AgtAgent::getAgentNo, agentNo).last("limit 1"));
        return a == null ? null : toBrief(a);
    }

    private AgentBrief toBrief(AgtAgent a) {
        boolean settling = !ai.neargo.sharehub.agent.AgentStatus.ENABLED.name().equals(a.getStatus()) && exits.selectCount(new LambdaQueryWrapper<ai.neargo.sharehub.agent.ext.entity.AgtExit>()
                .eq(ai.neargo.sharehub.agent.ext.entity.AgtExit::getAgentNo, a.getAgentNo())
                .eq(ai.neargo.sharehub.agent.ext.entity.AgtExit::getStatus, ai.neargo.sharehub.agent.ext.AgentExitStatus.SETTLING.name())) > 0;
        return new AgentBrief(a.getAgentNo(), a.getName(), AgentType.of(a.getAgentType()), a.getStatus(), settling);
    }
}
