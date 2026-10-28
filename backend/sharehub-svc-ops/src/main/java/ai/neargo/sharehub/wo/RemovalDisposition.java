package ai.neargo.sharehub.wo;

/**
 * 撤机去向（{@code wo_order.removal_disposition}，V164；技术设计 §3.5）。
 *
 * <ul>
 *   <li>{@link #RETURN_TO_POOL}：回同一商家的出库池（换点位 / 换站点），不生成回仓单，可再绑定；</li>
 *   <li>{@link #RETURN_TO_WAREHOUSE}：退出、维修、返厂 —— 生成回仓单，签收进仓时离开出库池。</li>
 * </ul>
 * 缺省按回仓：不说去哪，就按最保守的处理（设备回到平台手里）。
 */
public enum RemovalDisposition {
    RETURN_TO_POOL,
    RETURN_TO_WAREHOUSE;

    /** 空 = 回仓；词表外 → 400。 */
    public static RemovalDisposition ofNullable(String v) {
        if (v == null || v.isBlank()) return RETURN_TO_WAREHOUSE;
        for (RemovalDisposition d : values()) if (d.name().equalsIgnoreCase(v.trim())) return d;
        throw ai.neargo.sharehub.common.BizException.badRequest("error.common.invalid_value", v);
    }
}
