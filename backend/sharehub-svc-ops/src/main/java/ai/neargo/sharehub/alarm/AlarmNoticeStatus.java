package ai.neargo.sharehub.alarm;

/**
 * 告警通知的投递结果（{@code dev_alarm_notice.status}，[db-design §3.2]）。
 *
 * <p>⚠️ 当前触达通道未接（notify 域仍是 stub），写入侧一律落 {@link #SENT} 占位 ——
 * <b>那不是真实回执</b>。通道接通后要回写真实结果，{@link #FAILED} 目前没有任何代码会写。
 * 把这件事写在取值域上，比藏在某个 service 的行内注释里更难被忽略。
 */
public enum AlarmNoticeStatus {

    /** 已发出（DDL 默认值；通道未接前是占位值，见类注释）。 */
    SENT,
    /** 投递失败 —— 通道接通前**不会**被写入。 */
    FAILED;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static AlarmNoticeStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("通知状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("通知状态非法: " + v + "（仅 SENT/FAILED）");
        }
    }
}
