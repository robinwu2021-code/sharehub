package ai.neargo.sharehub.gw;

/**
 * 供应商启停（{@code gw_vendor.status}）。
 *
 * <p>⚠️ 这个 {@code ENABLED/DISABLED} 与别处同名但各自独立：全仓 {@code "ENABLED"}
 * 散落 12 处，分属供应商、告警规则（那里用的是 {@code ACTIVE/INACTIVE}）、
 * 代理商、品牌等好几个对象。同名不等于同一个词表 —— 合并会让"禁用了什么"
 * 这个问题在类型上失去答案。
 *
 * <p>{@code VendorServiceImpl} 判断"这家厂商能不能下发指令"就是看它等于 {@link #ENABLED}。
 */
public enum VendorStatus {

    /** 启用 —— 可下发指令（新建默认值）。 */
    ENABLED,
    /** 停用。 */
    DISABLED;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static VendorStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("供应商状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("供应商状态非法: " + v + "（仅 ENABLED/DISABLED）");
        }
    }
}
