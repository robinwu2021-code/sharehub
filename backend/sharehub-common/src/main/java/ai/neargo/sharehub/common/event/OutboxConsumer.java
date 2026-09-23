package ai.neargo.sharehub.common.event;

import ai.neargo.sharehub.common.event.entity.SysOutbox;

/**
 * 跨进程事件的消费者 SPI。
 *
 * <p>**进程内**消费不用实现本接口 —— `OutboxEventBus` 提交后会用 Spring 的
 * `ApplicationEventPublisher` 发一份，域内用 `@EventListener` 接即可，那条路更省事。
 * 本接口是给**要把事件送到另一个进程**的场景用的（如把设备事件推给业务单体）。
 *
 * <p>实现方注意三点：
 * <ol>
 *   <li><b>幂等由实现方负责</b>：投递语义是至少一次。用 {@code EventIdempotency.once(...)} 包住副作用；</li>
 *   <li><b>抛异常 = 本次投递失败</b>，轮询器会按退避重投，到上限转 {@code DEAD}；
 *       所以**不要吞异常** —— 吞了就等于宣告送达，事件从此消失；</li>
 *   <li><b>不要在这里回查发布方</b>：事件应自带消费所需的全部字段，回查等于把同步调用藏进事件。</li>
 * </ol>
 */
public interface OutboxConsumer {

    /** 是否处理这一类事件。 */
    boolean supports(String eventType);

    /**
     * 投递。抛异常即视为失败并进入重投。
     *
     * @param row 完整的 outbox 行（含 {@code eventNo} 与 {@code payload}）
     */
    void deliver(SysOutbox row);

    /** 消费者标识，用于去重与日志。默认取实现类名。 */
    default String handlerName() {
        return getClass().getSimpleName();
    }
}
