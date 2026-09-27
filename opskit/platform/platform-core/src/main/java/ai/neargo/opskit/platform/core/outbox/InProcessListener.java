package ai.neargo.opskit.platform.core.outbox;

import ai.neargo.opskit.commons.event.DomainEvent;

/**
 * 进程内订阅者：与发布方同一事务、同步执行（如工单订阅流程事件同步快照）。
 * 抛异常 = 整个业务事务回滚 —— 这是有意的：快照与真源必须同成同败。
 */
public interface InProcessListener {

    void on(DomainEvent event);
}
