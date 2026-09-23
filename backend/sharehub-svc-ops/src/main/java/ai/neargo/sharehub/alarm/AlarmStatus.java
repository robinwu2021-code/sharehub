package ai.neargo.sharehub.alarm;

/**
 * 告警状态（{@code dev_alarm.status}，[db-design §3.2]）—— {@link AlarmStateMachine} 的取值域。
 *
 * <p><b>为什么非收敛不可</b>：这张表上曾经有过一次无症状缺陷 ——
 * {@code AlarmServiceImpl} 自动开工单时查 {@code status IN ('OPEN','ACK')}，
 * 而 {@code "ACK"} 是**事件名**、落库状态是 {@code ACKED}，于是已受理的告警永远开不出工单，
 * 不报错、不留日志。差一个字母，编译器无从分辨；收进枚举之后，写错的那一刻就编译不过。
 *
 * <p><b>v1 词表不在此列</b>：{@code dev_alert}（已随 V45 退役）的 {@code OPEN/ACK/RESOLVED}
 * 与本枚举不兼容，历史值必须在迁移期换掉，**不在运行期兼容**。
 */
public enum AlarmStatus {

    /** 新产生，未受理。 */
    OPEN,
    /** 已受理，处理中 —— 注意不是 {@code ACK}（那是事件名）。 */
    ACKED,
    /** 已关闭（已解决 / 误报 / 自愈）。 */
    CLOSED;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static AlarmStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("告警状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("告警状态非法: " + v + "（仅 OPEN/ACKED/CLOSED）");
        }
    }
}
