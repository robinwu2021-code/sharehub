package ai.neargo.sharehub.trade.order;

import ai.neargo.sharehub.common.BizException;

/**
 * 催缴渠道（{@code ord_deposit.last_dun_channel}，DDL 注释 {@code 'SMS/PUSH/CALL'}）。
 *
 * <p>催缴**只留痕不改状态**，所以这一列是「催过没有、怎么催的」的<b>唯一记录</b>。
 * 写错一个字母的症状是按渠道统计催缴效果时这一条落到谁都不属于的一档 ——
 * 而催缴记录正是用来判断「该不该升级处理」的依据。
 */
public enum DunChannel {

    /** 短信。 */
    SMS,
    /** App 推送。 */
    PUSH,
    /** 人工电话。 */
    CALL;

    /** 宽松解析：非法值抛 {@link BizException}（400，文案走 i18n key —— 中文字面量会原样送到英文 / 阿语界面）。 */
    public static DunChannel of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "channel");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.common.invalid_value", v + " (SMS/PUSH/CALL)");
        }
    }

    /** 没传就不校验：催缴可以不注明渠道（线下催的情况）。 */
    public static DunChannel ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
