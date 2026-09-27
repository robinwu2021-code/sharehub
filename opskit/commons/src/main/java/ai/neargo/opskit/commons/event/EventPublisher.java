package ai.neargo.opskit.commons.event;

/** 必须在业务事务内调用；实现负责同事务分发与写发件箱。 */
public interface EventPublisher {

    void publish(DomainEvent event);
}
