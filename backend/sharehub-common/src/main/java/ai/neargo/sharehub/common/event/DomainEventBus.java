package ai.neargo.sharehub.common.event;

/**
 * 事件发布口。**发布方代码在单体与微服务两种形态下完全一致**，换的只是实现：
 *
 * <ul>
 *   <li>单体：写 outbox + 事务提交后转 {@code ApplicationEventPublisher}；</li>
 *   <li>微服务：写 outbox，独立轮询器投递到 MQ。</li>
 * </ul>
 *
 * <p><b>两种形态都写 outbox</b> —— 它不是「拆分时才加」的东西。
 * 单体期不写，将来切 MQ 时所有发布点都要改一遍，且改的时候没有任何测试能证明没漏。
 */
public interface DomainEventBus {

    /**
     * 发布事件。**必须在业务事务内调用** —— 事件与业务数据同事务落库，
     * 这正是 outbox 模式解决的问题（落库与发消息的原子性）。
     */
    void publish(DomainEvent event);
}
