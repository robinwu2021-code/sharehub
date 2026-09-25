package ai.neargo.sharehub.wo;

/**
 * 派单流水记录的动作（{@code wo_dispatch.action}）。
 *
 * <p><b>这一列在 DDL 里没有注释</b>（{@code action VARCHAR(24) NULL}，V4__user_ad_workorder.sql）——
 * 取值域只存在于 {@code WoOpsServiceImpl} 的两处字面量里。本枚举把它补成明文。
 *
 * <p>值与 {@link WoStateMachine} 的**事件名同形**，但两者不是一回事：
 * 那里的 {@code DISPATCH}/{@code ACCEPT} 是驱动状态迁移的动词、不落库；
 * 这里是**派单流水表上的一列**，回答「这条流水记的是派单还是接单」，用于算 SLA 时长
 * （{@code dispatchedAt} 取 DISPATCH 那条、接单耗时取 ACCEPT 那条）。
 * 同形是因为它们描述同一个动作的两面，不是因为它们是同一个东西。
 */
public enum WoDispatchAction {

    /** 派单：记录派给了谁、什么时候派的。 */
    DISPATCH,
    /** 接单：记录谁接的、什么时候接的。 */
    ACCEPT,
    /** 代理停用改派（批次 F2）：名下未完结工单转给平台员工。 */
    REASSIGN,
    /** 平台接管（批次 F4）：代理的单超时，平台改派自己的运维。 */
    TAKEOVER;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static WoDispatchAction of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("派单动作必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("派单动作非法: " + v + "（仅 DISPATCH/ACCEPT）");
        }
    }
}
