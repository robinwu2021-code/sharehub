package ai.neargo.sharehub.agent.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.agent.dto.AgentDtos.Agent;

/** 代理商业务（ADR-012）。VO/入参复用 Agent（字段一致，MVP 无需拆 AddReq/UpdReq）。 */
public interface AgentService {

    PageResult<Agent> page(Integer page, Integer size, String keyword);

    /** 新增/更新（upsert）：无 agentNo 则生成 AGxxx。 */
    Agent save(String agentNo, Agent in);

    /** 运营为商家新建站点（V162）：建站含默认点位、划给商家、写运维责任，一个事务。 */
    ai.neargo.sharehub.loc.dto.LocDtos.Site createSite(String agentNo, ai.neargo.sharehub.loc.dto.SiteDtos.SiteReq req);

    /** 归档代理商：盖 archivedAt 时间戳。**不是删除**，可 unarchive 恢复。 */
    Agent archive(String no);

    /** 取消归档代理商：清空时间戳，回到默认列表。 */
    Agent unarchive(String no);
}
