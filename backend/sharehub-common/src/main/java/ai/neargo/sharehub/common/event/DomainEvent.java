package ai.neargo.sharehub.common.event;

/**
 * 领域事件的最小契约（ADR-017 §5.2 / ADR-019）。
 *
 * <p><b>实现类必须自带消费方所需的全部字段</b> —— 让消费方拿到事件后回查发布方，
 * 等于把同步调用藏在事件里：单体下看不出问题，拆分后依然是强耦合，
 * 而且比直接调用更难发现。
 *
 * <p>事件是**既成事实**，命名一律用过去式（`ASSET_ASSIGNED` 而非 `ASSIGN_ASSET`）。
 * 用祈使式命名的是命令，命令有失败语义，事件没有。
 */
public interface DomainEvent {

    /** 聚合类型，如 {@code AgtAssignment}。用于排错时按单据追溯。 */
    String aggregateType();

    /** 聚合业务键，如 {@code ASG0001}。 */
    String aggregateId();

    /** 事件类型，如 {@code ASSET_ASSIGNED}。 */
    String eventType();
}
