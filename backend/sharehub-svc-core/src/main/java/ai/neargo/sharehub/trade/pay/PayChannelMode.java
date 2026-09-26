package ai.neargo.sharehub.trade.pay;

import ai.neargo.sharehub.common.BizException;

/**
 * 支付通道执行方式（{@code pay_channel.mode}）。
 *
 * <p>{@link #DELEGATED} 委托 nearpay 执行，{@link #DIRECT} 平台直连收单机构。
 * 两者的资金流与对账口径不同，配错的症状是**收款走了另一条路而账对不上**。
 *
 * <p>⚠️ {@code pay_channel} 当前 0 行，所以这道校验是**纯预防** ——
 * 第一条数据进来之前把闸装好，比之后再归一便宜。
 */
public enum PayChannelMode {

    /** 委托 nearpay 执行。 */
    DELEGATED,
    /** 平台直连。 */
    DIRECT;

    /**
     * 宽松解析：非法值抛 400。
     *
     * <p>⚠️ **文案走 i18n key，不写中文字面量** —— {@code GlobalExceptionHandler} 会把
     * 非 key 的字符串**一字不改**送到客户端，于是界面切英文/阿语之后页面是英文、报错还是中文。
     * 不报错，只是读不懂（{@code ChineseErrorLiteralRatchetTest} 卡着这件事，
     * 本类第一版就是那么写的，被它当场抓住）。
     */
    public static PayChannelMode of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.pay.channel_mode_required");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.pay.channel_mode_invalid", v);
        }
    }
    /**
     * **传了才校验** —— 空/缺省返回 {@code null}，由调用方沿用它原来的语义。
     *
     * <p>⚠️ 为什么不用 {@link #of}：保存端点**创建与编辑共用**，编辑时只传要改的字段，
     * 没传的留 null 表示「不改」。第一版在那里调了 {@code of}，于是
     * **改个名字都会被拒**（活动编辑实测 400），而那正是运营台最常做的操作。
     * 是 {@code StatusMassAssignmentTest.campaign_save_still_updates_other_fields} 抓住的。
     *
     * <p>缺口并不因此变小：非法值照样被拒，只有「没提供」被放过 ——
     * 而「没提供」在这一改之前本来就是合法的。
     */
    public static PayChannelMode ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }

}
