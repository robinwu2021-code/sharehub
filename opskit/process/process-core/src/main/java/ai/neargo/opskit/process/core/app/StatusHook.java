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

    /**
     * 实例刚创建（在初始状态的 onEnter 之前）。
     *
     * @param slaPolicies 启动时引用的 SLA 策略代号（工单类型或调用方给出）
     */
    default void onStarted(HookContext ctx, StatusDef initial, java.util.List<String> slaPolicies) {
    }

    /** 一次迁移走完（onEnter 之后、onClosed 之前）；带迁移代号，SLA 的「走了某迁移即起算 / 停止」要用。 */
    default void onTransitioned(HookContext ctx, StatusDef from, StatusDef to, String code) {
    }

    /** 实例字段被改（不迁移）；SLA 据此重新匹配目标矩阵。 */
    default void onFieldsChanged(HookContext ctx, StatusDef current) {
    }
}
