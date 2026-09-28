package ai.neargo.sharehub.dev;

/**
 * 充电宝健康（{@code dev_powerbank.health}）。
 *
 * <p>{@link #AGED} = 循环次数超限、待回收报废（批次 D2），不再借出。
 *
 * <p>这一列**参与可借判定**：写错一个字母不会报错，而这只宝从此既不是 OK
 * 也不是 FAULT —— 按健康筛查不到，可借判定也未必如意料。
 */
public enum PowerbankHealth {

    /** 正常。 */
    OK,
    /** 故障待修。 */
    FAULT,
    /** 老化待报废：循环次数超限，不再借出。 */
    AGED;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static PowerbankHealth of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("健康状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("健康状态非法: " + v + "（仅 OK/FAULT/AGED）");
        }
    }

    /** 没传就不校验（编辑只传要改的字段）。 */
    public static PowerbankHealth ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
