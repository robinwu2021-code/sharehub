package ai.neargo.opskit.process.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.process.api.definition.CompiledProcess;
import java.util.Map;

/**
 * @param actor     触发这次变化的操作人；门触发的系统迁移时为空
 * @param gateEpoch 迁移之后的门代次：进入新状态的门据此生成定时任务 ID，旧门的定时比对后自然作废
 */
public record HookContext(TenantContext tenant, String instanceNo, CompiledProcess process, Map<String, Object> fields,
        java.util.Optional<Actor> actor, int gateEpoch, int seq) {
}
