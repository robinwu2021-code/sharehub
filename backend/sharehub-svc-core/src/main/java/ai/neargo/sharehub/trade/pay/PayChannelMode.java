package ai.neargo.sharehub.trade.pay;

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

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static PayChannelMode of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("通道执行方式必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("通道执行方式非法: " + v + "（仅 DELEGATED/DIRECT）");
        }
    }
}
