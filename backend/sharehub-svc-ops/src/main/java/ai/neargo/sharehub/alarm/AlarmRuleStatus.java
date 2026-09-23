package ai.neargo.sharehub.alarm;

/**
 * 告警规则的启停（{@code dev_alarm_rule.status}，[db-design §3.2]）。
 *
 * <p>与 {@link AlarmStatus} 是**两个不同的词表**，只是都叫 status ——
 * 规则说的是「这条规则开没开」，告警说的是「这条告警处理到哪一步」。
 * 合并成一个枚举会让 {@code ACTIVE} 这种值同时出现在两个语义里，
 * 而这正是全仓 {@code "ACTIVE"} 散落 25 处却说不清各自指什么的由来。
 */
public enum AlarmRuleStatus {

    /** 启用（DDL 默认值）。 */
    ACTIVE,
    /** 停用。 */
    INACTIVE;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static AlarmRuleStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("规则状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("规则状态非法: " + v + "（仅 ACTIVE/INACTIVE）");
        }
    }
}
