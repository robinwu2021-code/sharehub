package ai.neargo.sharehub.dev;

/** 保护动作的持有者（{@code dev_protection.holder_type}）。同一目标可被多个持有者同时要求，全部释放才解除。 */
public enum ProtectionHolder {
    /** 设备信号（holder_ref = 信号码:柜:仓）。 */
    SIGNAL,
    /** 业务告警（holder_ref = 告警号）。 */
    ALARM,
    /** 人工（holder_ref = 操作人）。 */
    MANUAL
}
