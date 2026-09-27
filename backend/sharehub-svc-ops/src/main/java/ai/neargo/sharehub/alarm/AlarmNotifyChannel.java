package ai.neargo.sharehub.alarm;

import ai.neargo.sharehub.common.BizException;

/**
 * 告警通知渠道（{@code dev_alarm_rule.channel}）。
 *
 * <h2>⚠️ 本仓库有四套互不相同的 {@code channel} 词表，别合并</h2>
 * <table>
 *   <tr><td>{@code dev_alarm_rule.channel}</td><td>EMAIL / PUSH / SMS / WEBHOOK</td><td>← 本枚举</td></tr>
 *   <tr><td>{@code notify_template.channel}</td><td>INAPP / PUSH / SMS / SUBSCRIBE</td><td></td></tr>
 *   <tr><td>{@code notify_blacklist.channel}</td><td>ALL / EMAIL / PUSH / SMS / WHATSAPP</td><td></td></tr>
 *   <tr><td>{@code mkt_push.channel}</td><td>APP_PUSH / SUBSCRIBE</td><td></td></tr>
 * </table>
 * 四套只有 {@code PUSH} 与 {@code SMS} 是共有的。合并成一个枚举会把每一列都放宽到并集，
 * <b>那之后四列全都不再有校验</b> —— 而并集里的值在各自的列上都是无意义的
 * （给告警规则配 {@code WHATSAPP}、给拉黑记录配 {@code WEBHOOK}）。
 *
 * <p>⚠️ {@code EMAIL} 在本仓库**没有发件实现**（无 SMTP / 无 MailSender，
 * 见 P3b 设计 §5.1 的裁决）。它留在词表里是因为 DDL 这么声明的，
 * 但配了它等于不通知 —— 这条与「短信推送」那条同形，待有发件通道后才真正可用。
 */
public enum AlarmNotifyChannel {

    /** 邮件 —— ⚠️ 当前无发件实现，见类注释。 */
    EMAIL,
    /** 应用推送。 */
    PUSH,
    /** 短信。 */
    SMS,
    /** Webhook 回调。 */
    WEBHOOK;

    /** 宽松解析：非法值抛 400。文案走 i18n key。 */
    public static AlarmNotifyChannel of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.alarm.notify_channel_required");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.alarm.notify_channel_invalid", v);
        }
    }

    /** **传了才校验** —— 空/缺省返回 {@code null}，调用方沿用原语义。 */
    public static AlarmNotifyChannel ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
