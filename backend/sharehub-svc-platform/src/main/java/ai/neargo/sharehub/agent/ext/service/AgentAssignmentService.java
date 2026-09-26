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
    /**
     * 可划拨资产候选池，**统一分页形状**（2026-09-26，§5.8 #4）。
     *
     * <p>此前返回裸数组、参数叫 {@code limit} —— 全站其余列表都是 {@code PageResult{list,total}} + {@code page/size}，
     * 于是前端为它单独写了一层适配；换个客户端接它还要再写一遍。
     *
     * <p><b>{@code total} 的口径要说清</b>：候选池只取**前 {@value AgentAssignmentServiceImpl#CANDIDATE_CAP} 条**
     * （它是选项源不是台账），{@code total} 返回的是这批里的条数，与实际能翻到的页数严格一致 ——
     * 返回真实全表 count 的话会出现「总数 800 但翻到第 6 页就空了」，那种 total 是在骗人。
     *
     * @param excludeAgentNo 排除已属于该代理的资产（划拨抽屉里「换个代理」时用：
     *                       把当前代理自己的选项去掉，否则划给自己是一次空操作还留一行流水）
     */
    PageResult<AssignableAsset> assignable(String keyword, String agentNo, String assetType,
                                           Integer page, Integer size, String excludeAgentNo);

    /**
     * 批量回收到平台直营。<b>归属方从资产现状反查</b>，入参不带 {@code agentNo}。
     *
     * @return 每个**实际发生变更**的资产一行流水；已是直营的资产不产生流水（幂等重试友好）
     */
    List<AssignmentLog> reclaim(ReclaimReq req);
}
