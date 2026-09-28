package ai.neargo.sharehub.trade.order;

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

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static DunChannel of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("催缴渠道必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("催缴渠道非法: " + v + "（仅 SMS/PUSH/CALL）");
        }
    }

    /** 没传就不校验：催缴可以不注明渠道（线下催的情况）。 */
    public static DunChannel ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
