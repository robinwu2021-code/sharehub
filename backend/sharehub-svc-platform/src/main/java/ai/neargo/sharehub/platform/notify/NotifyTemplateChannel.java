package ai.neargo.sharehub.platform.notify;

import ai.neargo.sharehub.common.BizException;

/**
 * 通知模板渠道（{@code notify_template.channel}）。
 *
 * <h2>⚠️ 这是本仓库四套 {@code channel} 词表中的一套，它们两两不同</h2>
 * <table>
 *   <tr><td>{@code notify_template.channel}</td><td>INAPP / PUSH / SMS / SUBSCRIBE</td><td>← 本枚举</td></tr>
 *   <tr><td>{@code notify_blacklist.channel}</td><td>ALL / EMAIL / PUSH / SMS / WHATSAPP</td><td></td></tr>
 *   <tr><td>{@code dev_alarm_rule.channel}</td><td>EMAIL / PUSH / SMS / WEBHOOK</td><td></td></tr>
 *   <tr><td>{@code mkt_push.channel}</td><td>APP_PUSH / SUBSCRIBE</td><td></td></tr>
 * </table>
 * 只有 {@code PUSH} 与 {@code SMS} 是四套共有的。**合并成一个枚举会让四列全都不再有校验**，
 * 而并集里的值在各自列上都是无意义的（给模板配 {@code WHATSAPP}、给拉黑配 {@code WEBHOOK}）。
 * {@code VocabularyRejectedOnWriteTest} 里那两格刻意拿对方的独有值做负例 ——
 * 谁哪天真合并了，它们会同时红。
 *
 * <p>⚠️ 模板这一列**没有 EMAIL** —— 而拉黑那一列有。本仓库至今无发件实现
 * （无 SMTP / MailSender，见 P3b 设计 §5.1），所以不因为「看起来该有」就补进来。
 */
public enum NotifyTemplateChannel {

    /** 站内消息。 */
    INAPP,
    /** 应用推送。 */
    PUSH,
    /** 短信。 */
    SMS,
    /** 订阅消息。 */
    SUBSCRIBE;

    /** 宽松解析：非法值抛 400。文案走 i18n key。 */
    public static NotifyTemplateChannel of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.notify.template_channel_required");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.notify.template_channel_invalid", v);
        }
    }

    /** **传了才校验** —— 空/缺省返回 {@code null}，调用方沿用原语义。 */
    public static NotifyTemplateChannel ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
