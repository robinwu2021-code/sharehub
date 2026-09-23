package ai.neargo.sharehub.dev;

/**
 * OTA 投放状态（{@code dev_ota_rollout.status}）。
 *
 * <h2>⚠️ DDL 注释与实际取值几乎完全不相交</h2>
 * 列注释写的是 {@code 'RUNNING/PAUSED/COMPLETED/CANCELED'}，而
 * {@code OtaServiceImpl.ROLLOUT_TRANSITIONS} 实际用的是
 * {@code PENDING → RUNNING → DONE → ROLLBACK} —— <b>四个值里只有 RUNNING 重合</b>：
 * <ul>
 *   <li>注释里的 {@code PAUSED} / {@code COMPLETED} / {@code CANCELED} <b>没有任何代码会写</b>；</li>
 *   <li>实际在用的 {@code PENDING} / {@code DONE} / {@code ROLLBACK} <b>注释里一个都没有</b>。</li>
 * </ul>
 * 本枚举以**实际写入方**为准。照注释写过滤条件的人会得到一个空列表，且不会有任何报错 ——
 * 这正是「注释不是约束」能造成的最大伤害：两套词表并存，谁也不会红。
 *
 * <p>{@code CANCELED} 那个拼法还顺带暴露了另一处分叉：全仓其余地方写的是
 * {@code CANCELLED}（双 L）。既然它从没被写过，这里直接不收录。
 */
public enum OtaRolloutStatus {

    /** 已创建，待开始（代码默认值；**DDL 注释里没有**）。 */
    PENDING,
    /** 灰度/全量推送中。 */
    RUNNING,
    /** 推送完成 —— 仍可整体回滚，所以不是终态（**DDL 注释里没有**）。 */
    DONE,
    /** 已回滚，**终态**（**DDL 注释里没有**）。 */
    ROLLBACK;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static OtaRolloutStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("投放状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("投放状态非法: " + v + "（仅 PENDING/RUNNING/DONE/ROLLBACK）");
        }
    }
}
