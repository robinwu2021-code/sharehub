package ai.neargo.opskit.process.core.app;

import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.client.InstanceObserver;
import java.util.List;

/**
 * 把「实例变了」同步通知给上层模块（{@link InstanceObserver}）。排在钩子列表最后：门、任务、SLA 都已处理完，
 * 观察者读到的是这次写操作之后的完整状态。
 */
public class ObserverHook implements StatusHook {

    private final List<InstanceObserver> observers;

    public ObserverHook(List<InstanceObserver> observers) {
        this.observers = List.copyOf(observers);
    }

    @Override
    @SystemAction("状态钩子：通知上层同步快照")
    public void onTransitioned(HookContext ctx, StatusDef from, StatusDef to, String code) {
        observers.forEach(o -> o.onInstanceChanged(ctx.tenant(), ctx.instanceNo()));
    }

    @Override
    @SystemAction("状态钩子：字段变化可能改了 SLA 目标")
    public void onFieldsChanged(HookContext ctx, StatusDef current) {
        observers.forEach(o -> o.onInstanceChanged(ctx.tenant(), ctx.instanceNo()));
    }
}
