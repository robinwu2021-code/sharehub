package ai.neargo.sharehub.alarm;

import ai.neargo.sharehub.common.BizException;

/**
 * 告警通知方式（{@code dev_alarm_rule.method}）—— 立即发还是汇总发。
 *
 * <p>{@link #INSTANT} 每条告警发一次；{@link #DIGEST} 按周期汇总成一条。
 * 配错的后果不对称：把 {@code DIGEST} 写成 {@code INSTANT}，一次批量故障会把
 * 值班的人淹掉（而淹掉之后真告警就被忽略了，见 CLAUDE.md 日志规范那条
 * 「噪音会让真告警被忽略」）；反过来则是紧急告警晚到一个周期。
 */
public enum AlarmNotifyMethod {

    /** 立即发送，每条一次。 */
    INSTANT,
    /** 按周期汇总成一条。 */
    DIGEST;

    /** 宽松解析：非法值抛 400。文案走 i18n key。 */
    public static AlarmNotifyMethod of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.alarm.notify_method_required");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.alarm.notify_method_invalid", v);
        }
    }

    /** **传了才校验** —— 空/缺省返回 {@code null}，调用方沿用原语义。 */
    public static AlarmNotifyMethod ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
