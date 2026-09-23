package ai.neargo.sharehub.alarm;

/**
 * 告警级别（{@code dev_alarm.level} 与 {@code dev_alarm_code.level}，[db-design §3.2]）。
 *
 * <p><b>一个枚举服务两张表是有意的</b>：{@code dev_alarm.level} 是**写入时快照**（可被规则覆盖），
 * {@code dev_alarm_code.level} 只是字典默认值 —— 两者取值域必须相同，否则字典里配了
 * 一个告警记录永远表达不出的级别，而没有任何约束会拦住它。
 */
public enum AlarmLevel {

    /** 提示，不需要处理。 */
    INFO,
    /** 警告，需关注（DDL 默认值）。 */
    WARN,
    /** 严重，需立即处理。 */
    CRITICAL;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static AlarmLevel of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("告警级别必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("告警级别非法: " + v + "（仅 INFO/WARN/CRITICAL）");
        }
    }
}
