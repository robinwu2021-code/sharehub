package ai.neargo.sharehub.common.event;

import ai.neargo.sharehub.common.Json;
import ai.neargo.sharehub.common.event.entity.SysOutbox;
import ai.neargo.sharehub.common.event.mapper.SysOutboxMapper;
import ai.neargo.sharehub.auth.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * {@link DomainEventBus} 的单体实现：**写 outbox（同事务）+ 事务提交后本地投递**。
 *
 * <p><b>为什么必须等提交后再投递</b>：在事务内直接 publish，若事务随后回滚，
 * 事件已经发出去了 —— 消费方按一个从未发生的事实动作，且无法回滚。
 * 这是「事务内发消息」最经典的错误，也是 outbox 模式存在的理由。
 *
 * <p><b>为什么写 outbox 而不只是延迟投递</b>：延迟投递解决了回滚问题，
 * 但没解决进程崩溃问题 —— 提交后、投递前宕机，事件就永久丢了。
 * 落 outbox 后即使丢投递，记录还在，轮询器会重投。
 *
 * <p>拆分为微服务时，替换本类为 MQ 实现即可，**发布方代码一字不改**。
 */
@Component
public class OutboxEventBus implements DomainEventBus {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventBus.class);

    private final SysOutboxMapper mapper;
    private final ApplicationEventPublisher publisher;
    /** 延迟取：分发器依赖全部 OutboxConsumer，消费者又可能间接依赖本类 —— 构造期直接注入会成环 */
    private final ObjectProvider<OutboxDispatcher> dispatcher;

    public OutboxEventBus(SysOutboxMapper mapper, ApplicationEventPublisher publisher,
                          ObjectProvider<OutboxDispatcher> dispatcher) {
        this.mapper = mapper;
        this.publisher = publisher;
        this.dispatcher = dispatcher;
    }

    @Override
    public void publish(DomainEvent event) {
        SysOutbox row = new SysOutbox();
        row.setEventNo("EVT" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        row.setTenantId(SecurityUtils.tenantId());
        row.setAggregateType(event.aggregateType());
        row.setAggregateId(event.aggregateId());
        row.setEventType(event.eventType());
        row.setPayload(Json.write(event));
        row.setStatus(OutboxStatus.PENDING.name());
        row.setRetryCount(0);
        mapper.insert(row);

        Long id = row.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(id, event);
                }
            });
        } else {
            // 无事务上下文（如启动期初始化）：直接投递。
            // 不静默跳过 —— 那会让事件永远停在 PENDING，且没人知道。
            deliver(id, event);
        }
    }

    private void deliver(Long id, DomainEvent event) {
        try {
            publisher.publishEvent(event);
            /*
             * 有跨进程消费者认领这类事件 → 不能在这里标 SENT，要交给分发器送出去。
             * 2026-09-28 之前这里无条件标 SENT，而分发器只捞 PENDING / FAILED ——
             * 于是 OutboxConsumer **只有在进程内投递失败时才会被调用**，正常路径永远送不出去。
             * 当时 OutboxConsumer 零实现，所以没人发现；OpsKit 送审是第一个实现（M5）。
             * 分发器自带成败落库（SENT / FAILED + 退避 / DEAD），这里不再改状态。
             */
            OutboxDispatcher d = dispatcher.getIfAvailable();
            if (d != null && d.hasCrossProcessConsumer(event.eventType())) {
                SysOutbox row = mapper.selectById(id);
                if (row != null) {
                    d.dispatchOne(row);
                    return;
                }
            }
            SysOutbox upd = new SysOutbox();
            upd.setId(id);
            upd.setStatus(OutboxStatus.SENT.name());
            upd.setSentAt(java.time.LocalDateTime.now());
            mapper.updateById(upd);
        } catch (Exception e) {
            // 投递失败不能把业务事务拖下水 —— 此时事务已提交，抛异常也无法回滚，
            // 只会让调用方收到一个误导性的失败。记录状态，交给轮询器重投。
            log.error("事件投递失败，留待重投：eventNo={} type={}", id, event.eventType(), e);
            SysOutbox upd = new SysOutbox();
            upd.setId(id);
            upd.setStatus(OutboxStatus.FAILED.name());
            upd.setLastError(String.valueOf(e.getMessage()));
            upd.setNextRetryAt(java.time.LocalDateTime.now().plusMinutes(1));
            mapper.updateById(upd);
        }
    }
}
