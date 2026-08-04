package ai.neargo.sharehub.agent.ext.service;

import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentCommission;
import ai.neargo.sharehub.agent.ext.entity.AgtCommission;
import ai.neargo.sharehub.common.crud.CrudService;

/**
 * 代理分润配置（规则配置类 → 通用 CRUD）。
 *
 * <p>唯一的业务规则是**按 {@code dimension} 校验对应金额字段非空**，
 * 落在 impl 的 {@code beforeCreate}/{@code beforeUpdate} 钩子里，不值得为此手写一整套。
 */
public interface AgentCommissionService extends CrudService<AgtCommission, AgentCommission> {
}
