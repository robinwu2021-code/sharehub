package ai.neargo.sharehub.agent.ext.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.agent.ext.entity.AgtAgentRegion;
import ai.neargo.sharehub.agent.ext.mapper.AgtAgentRegionMapper;
import ai.neargo.sharehub.agent.ext.service.AgentRegionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/** 代理辖域拆表实现。 */
@Service
public class AgentRegionServiceImpl implements AgentRegionService {

    private static final String TENANT_MAIN = "MAIN";

    private final AgtAgentRegionMapper mapper;

    public AgentRegionServiceImpl(AgtAgentRegionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<String> regionsOf(String agentNo) {
        if (agentNo == null || agentNo.isBlank()) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<AgtAgentRegion>()
                        .eq(AgtAgentRegion::getAgentNo, agentNo)
                        .orderByAsc(AgtAgentRegion::getId))
                .stream().map(AgtAgentRegion::getRegionId).toList();
    }

    @Override
    public String csvOf(String agentNo) {
        return String.join(",", regionsOf(agentNo));
    }

    /**
     * 全量替换。
     *
     * <p>用「先删后插」而不是「差集增删」：辖区一个代理通常只有个位数，差集算法省不下什么，
     * 却要多维护一套比对逻辑。软删（{@code @TableLogic}）保证历史行仍可追溯。
     */
    @Override
    @Transactional
    public List<String> replace(String agentNo, String csv) {
        if (agentNo == null || agentNo.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "agentNo");

        List<String> regions = parse(csv);

        mapper.delete(new LambdaQueryWrapper<AgtAgentRegion>().eq(AgtAgentRegion::getAgentNo, agentNo));
        for (String regionId : regions) {
            AgtAgentRegion e = new AgtAgentRegion();
            e.setTenantId(TENANT_MAIN);
            e.setAgentNo(agentNo);
            e.setRegionId(regionId);
            mapper.insert(e);
        }
        return regions;
    }

    /** CSV → 去空白、去空项、**保序去重**（UK(agent_no, region_id) 撞了会整批失败）。 */
    private static List<String> parse(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(set::add);
        return new ArrayList<>(set);
    }
}
