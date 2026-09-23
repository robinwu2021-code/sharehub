package ai.neargo.sharehub.common.event;

/**
 * 发件箱记录的投递状态（{@code sys_outbox.status}）。
 *
 * <p><b>「至少一次」是刻意的</b>，所以这四个态描述的是一次投递的生命周期，而不是业务状态：
 * Outbox + 重投必然产生重复投递（投递成功但回写 {@link #SENT} 前宕机 → 下轮重投），
 * 做成「恰好一次」需要分布式事务，代价远大于收益。约定是「至少一次 + 消费端去重」，
 * 去重靠 {@code sys_event_consumed(event_no, handler)} 的唯一键。
 *
 * <p>{@link #DEAD} 是 B7 补的：**没有死信状态的重投队列，最终会变成一个无人看的失败堆**。
 * 注意 V15 建表时的列注释只写了 PENDING/SENT/FAILED，DEAD 是 V43 补进注释的 ——
 * 这个枚举现在是唯一真源。
 */
public enum OutboxStatus {

    /** 待投递。轮询器只取 {@code PENDING 且 next_retry_at <= now} 的行。 */
    PENDING,
    /** 已送达。 */
    SENT,
    /** 投递失败，待重投（带指数退避 1m→5m→30m→2h→6h→12h）。 */
    FAILED,
    /** 超过重试上限，**终态** —— 需人工介入，不再自动重投也不静默丢弃。 */
    DEAD;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}。 */
    public static OutboxStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("发件箱状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("发件箱状态非法: " + v + "（仅 PENDING/SENT/FAILED/DEAD）");
        }
    }
}
