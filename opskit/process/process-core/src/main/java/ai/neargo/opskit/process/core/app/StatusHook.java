package ai.neargo.opskit.process.core.app;

import ai.neargo.opskit.process.api.definition.StatusDef;

/**
 * 状态变化的扩展点（21/02 §8.1 第 5–7 步）：门（审批 / 外部确认 / 定时）的启动与收尾、SLA 的起算 / 暂停 / 停止都挂在这里。
 * 与迁移同事务、同步执行；抛异常 = 整个迁移回滚。
 */
public interface StatusHook {

    default void onEnter(HookContext ctx, StatusDef status) {
    }

    default void onLeave(HookContext ctx, StatusDef status) {
    }

    /** 进入终态类别（DONE / CANCELLED）之后。 */
    default void onClosed(HookContext ctx) {
    }
}
