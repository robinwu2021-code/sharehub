package ai.neargo.sharehub.platform.notify;

import ai.neargo.sharehub.common.BizException;

/**
 * 触达拉黑的渠道范围（{@code notify_blacklist.channel}）—— 这个人在**哪条通路**上被拉黑。
 *
 * <p>{@link #ALL} 是全渠道拉黑，其余是单渠道。范围写错的后果不对称：
 * 该全渠道拉黑的只拉了短信 ⇒ **推送照发**，而用户是明确退订过的（合规问题）；
 * 反过来则是多挡了一条通路，用户收不到该收的通知。两种都不报错。
 *
 * <h2>⚠️ 与另外三套 {@code channel} 词表两两不同</h2>
 * <table>
 *   <tr><td>{@code notify_blacklist.channel}</td><td>ALL / EMAIL / PUSH / SMS / WHATSAPP</td><td>← 本枚举</td></tr>
 *   <tr><td>{@code notify_template.channel}</td><td>INAPP / PUSH / SMS / SUBSCRIBE</td><td></td></tr>
 *   <tr><td>{@code dev_alarm_rule.channel}</td><td>EMAIL / PUSH / SMS / WEBHOOK</td><td></td></tr>
 *   <tr><td>{@code mkt_push.channel}</td><td>APP_PUSH / SUBSCRIBE</td><td></td></tr>
 * </table>
 * 本列独有 {@link #ALL} 与 {@link #WHATSAPP}，而**没有** {@code INAPP} / {@code SUBSCRIBE}
 * —— 站内消息与订阅消息挡不住，因为它们不是「推送给你」而是「你自己来看」。
 * 详见 {@link NotifyTemplateChannel} 类注释里的四套对照表。
 */
public enum NotifyBlacklistChannel {

    /** 全渠道拉黑。 */
    ALL,
    /** 仅邮件 —— ⚠️ 本仓库无发件实现，配了它等于没挡也没发。 */
    EMAIL,
    /** 仅应用推送。 */
    PUSH,
    /** 仅短信。 */
    SMS,
    /** 仅 WhatsApp。 */
    WHATSAPP;

    /** 宽松解析：非法值抛 400。文案走 i18n key。 */
    public static NotifyBlacklistChannel of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.notify.blacklist_channel_required");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.notify.blacklist_channel_invalid", v);
        }
    }

    /** **传了才校验** —— 空/缺省返回 {@code null}，调用方沿用原语义。 */
    public static NotifyBlacklistChannel ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
