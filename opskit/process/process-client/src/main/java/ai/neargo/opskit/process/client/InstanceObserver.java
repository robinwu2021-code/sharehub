package ai.neargo.opskit.process.client;

import ai.neargo.opskit.commons.tenancy.TenantContext;

/**
 * 实例变化的同步回调（与引起变化的写操作同事务）：迁移完成、SLA 计时器状态或到期变化之后调用。
 * 上层模块据此维护自己的只读快照（工单的状态 / SLA 快照，21/02 §8.6）—— 在同一事务里同步，快照不可能与实例不一致。
 * 抛异常 = 整个写操作回滚。
 */
public interface InstanceObserver {

    void onInstanceChanged(TenantContext t, String instanceNo);
}
