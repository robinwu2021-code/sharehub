package ai.neargo.sharehub.agent.ext.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentAssignment;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentPerformance;
import ai.neargo.sharehub.agent.ext.service.AgentPerformanceService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 代理绩效读模型 —— **当前为空实现**（返回空页），端点先通、口径后填。
 *
 * <p>两个方法都要跨域聚合 {@code ord_order}（trade 域）与 {@code dev_cabinet}（dev 域），
 * 这两个域的 mapper 不在本分片边界内；且绩效榜的 {@code rank} 必须在**同一条 SQL** 里算，
 * 分两次查再在 Java 里排会与分页打架（分页后排名只在当前页内成立，是经典错觉 bug）。
 *
 * <p>TODO(读模型): 落地方式建议自定义 Mapper XML，一条 GROUP BY + 窗口函数出结果。SQL 骨架：
 * <pre>
 *   SELECT a.agent_no, a.name AS agent_name,
 *          COALESCE(SUM(o.fee_amount), 0)                            AS gmv,
 *          RANK() OVER (ORDER BY COALESCE(SUM(o.fee_amount),0) DESC) AS `rank`
 *     FROM agt_agent a
 *     LEFT JOIN ord_order o
 *           ON o.agent_no = a.agent_no          -- ADR-012 归属冗余列，免子查询
 *          AND o.deleted = 0
 *          AND o.rent_start_at BETWEEN #{from} AND #{to}
 *    WHERE a.deleted = 0
 *    GROUP BY a.agent_no, a.name
 * </pre>
 * {@code cabinetCount} / {@code onlineRate} 再 join {@code dev_cabinet} 按 {@code agent_no} 聚合
 * （**不要读 {@code agt_agent.cabinet_count} 列** —— [db-design §1.4]「计数列不是列，是聚合」）。
 * {@code assignments()} 的 {@code region} 取 {@code agt_agent_region} 拼串
 * （见 {@link ai.neargo.sharehub.agent.ext.service.AgentRegionService#csvOf}）。
 */
@Service
public class AgentPerformanceServiceImpl implements AgentPerformanceService {

    @Override
    public PageResult<AgentPerformance> page(Integer page, Integer size, String keyword, String from, String to) {
        return new PageResult<>(List.of(), 0L);
    }

    @Override
    public PageResult<AgentAssignment> assignments(Integer page, Integer size, String keyword) {
        return new PageResult<>(List.of(), 0L);
    }
}
