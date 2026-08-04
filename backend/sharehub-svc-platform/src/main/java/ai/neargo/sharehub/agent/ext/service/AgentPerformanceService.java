package ai.neargo.sharehub.agent.ext.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentAssignment;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentPerformance;

/**
 * 代理绩效 + 归属现状 —— 两个都是**读模型，不建物理表**（[db-design §3.5]）。
 *
 * <p>{@code [读] AgentPerformance} = {@code agt_agent ⋈ ord_order}/{@code dev_cabinet} 聚合：
 * {@code gmv}（期间订单额之和）、{@code cabinetCount}（在架机柜数）、
 * {@code onlineRate}（{@code dev_cabinet.online_status='ONLINE'} 占比，0..1）、
 * {@code rank}（按 {@code gmv} 排名，由 SQL 窗口函数或结果集序号给出）。
 */
public interface AgentPerformanceService {

    /**
     * 绩效榜。
     *
     * @param from 统计起始日 {@code yyyy-MM-dd}（含），空 = 近 30 天
     * @param to   统计截止日 {@code yyyy-MM-dd}（含），空 = 今天
     */
    PageResult<AgentPerformance> page(Integer page, Integer size, String keyword, String from, String to);

    /** 归属现状（每个代理手上有多少台柜、多少个站），供「设备/点位划拨」页左表。 */
    PageResult<AgentAssignment> assignments(Integer page, Integer size, String keyword);
}
