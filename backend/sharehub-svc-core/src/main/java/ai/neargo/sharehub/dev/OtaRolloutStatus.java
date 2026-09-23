package ai.neargo.sharehub.dev;

/**
 * OTA 投放状态（{@code dev_ota_rollout.status}）。
 *
 * <p><b>与 DDL 当前定义逐项一致</b>：{@code V8__v2_alter.sql} 把注释改成了
 * {@code 'PENDING/RUNNING/DONE/ROLLBACK'}，与 {@code OtaServiceImpl.ROLLOUT_TRANSITIONS} 吻合。
 *
 * <p>⚠️ {@code V2__device_gateway.sql} 的原始注释写的是 {@code 'RUNNING/PAUSED/COMPLETED/CANCELED'}，
 * 与实际几乎不相交。我曾据此断定「DDL 是错的」——<b>那个判断错了</b>，V8 早已修正。
 * <b>看 DDL 必须看当前 schema。</b>（顺带：那个 {@code CANCELED} 是单 L 拼法，
 * 全仓其余地方都写 {@code CANCELLED}；它随 V8 一起消失了。）
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
