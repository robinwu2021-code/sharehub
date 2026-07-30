package ai.neargo.powerbank.agent.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.powerbank.dto.Dto.Agent;

/** 代理商业务（ADR-012）。VO/入参复用 Dto.Agent（字段一致，MVP 无需拆 AddReq/UpdReq）。 */
public interface AgentService {

    PageResult<Agent> page(Integer page, Integer size, String keyword);

    /** 新增/更新（upsert）：无 agentNo 则生成 AGxxx。 */
    Agent save(String agentNo, Agent in);
}
