package ai.neargo.opskit.process.core.app;

import ai.neargo.opskit.process.api.definition.ApproverRef;

/** 后置动作「派给谁」的执行者（由任务模块 O2-4 实现）。 */
public interface AssignmentHandler {

    void assign(HookContext ctx, ApproverRef candidates);
}
