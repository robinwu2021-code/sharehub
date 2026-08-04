package ai.neargo.sharehub.agent.ext.service;

import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentAccount;
import ai.neargo.sharehub.agent.ext.entity.AgtAccount;
import ai.neargo.sharehub.common.crud.CrudService;

/**
 * 代理登录账号（开通 / 停用）。
 *
 * <p>归类为**配置类** → 通用 CRUD：本表只是「谁能登代理端」的名册，
 * 凭据在 {@code pb_auth}、数据范围在 {@code iam_data_scope}，本表自身无状态机、无跨表副作用。
 * 停用 = {@code status=DISABLED}（全站零 DELETE，[api/README §6A.3]）。
 */
public interface AgentAccountService extends CrudService<AgtAccount, AgentAccount> {
}
