package ai.neargo.sharehub.agent.ext.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AssignReq;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AssignableAsset;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AssignmentLog;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.ReclaimReq;

import java.util.List;

/**
 * 设备/点位划拨（append 留痕 → 手写，不继承通用 CRUD）。
 *
 * <p>{@code agt_assignment} 只增不改：同一台柜机划出→收回→再划出是三行，不是一行三次 UPDATE。
 */
public interface AgentAssignmentService {

    /** 划拨流水（{@code agt_assignment} 直出）。 */
    PageResult<AssignmentLog> page(Integer page, Integer size, String agentNo,
                                   String targetType, String targetNo);

    /** 划拨 / 收回，落一行 append 记录并返回之。 */
    AssignmentLog assign(AssignReq req);

    /**
     * 可划拨资产候选池（机柜 + 站点，带当前归属）。
     *
     * @param assetType 只看某一类；null = 两类都要
     */
    List<AssignableAsset> assignable(String keyword, String agentNo, String assetType, Integer limit);

    /**
     * 批量回收到平台直营。<b>归属方从资产现状反查</b>，入参不带 {@code agentNo}。
     *
     * @return 每个**实际发生变更**的资产一行流水；已是直营的资产不产生流水（幂等重试友好）
     */
    List<AssignmentLog> reclaim(ReclaimReq req);
}
