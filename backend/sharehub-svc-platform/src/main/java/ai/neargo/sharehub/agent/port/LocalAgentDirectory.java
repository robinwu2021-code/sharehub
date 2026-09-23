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

    public LocalAgentDirectory(AgentMapper agents) {
        this.agents = agents;
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

    private static AgentBrief toBrief(AgtAgent a) {
        return new AgentBrief(a.getAgentNo(), a.getName(), AgentType.of(a.getAgentType()));
    }
}
