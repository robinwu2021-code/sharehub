package ai.neargo.sharehub.dev;

/**
 * 机柜状态（{@code dev_cabinet.status}，DDL 注释 {@code 'DEPLOYED/FAULT/RETIRED'}）。
 *
 * <p>⚠️ 与 {@link PowerbankStatus} **不是一个词表**，尽管两者都有 {@code FAULT} 与 {@code IN_STOCK}：
 * 机柜说的是这台设备本身的部署状态，充电宝说的是那块电池在哪、被谁拿着。
 * {@code DEPLOYED} 在机柜这里是"已铺设在站点上"，而它出现在
 * {@code dev_powerbank.status} 的 DDL 注释里纯属抄错（见 {@link PowerbankStatus} 类注释）。
 *
 * <p><b>DDL 注释漏了 {@link #IN_STOCK}</b>：{@code CabinetServiceImpl} 新建机柜时落的就是它
 * （"还没上架就置 ONLINE 会让它出现在 C 端可借列表里"），而列注释只写了三个态。
 * 照注释写过滤条件的人会漏掉所有新建未上架的设备 —— 且不会报错，只是少一批数据。
 */
public enum CabinetStatus {

    /** 在库，未上架 —— 新建机柜的初始态。**DDL 注释里没有它**，见类注释。 */
    IN_STOCK,
    /** 已部署在站点上（DDL 默认值）。 */
    DEPLOYED,
    /** 故障。 */
    FAULT,
    /** 已退役。 */
    RETIRED;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static CabinetStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("机柜状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("机柜状态非法: " + v + "（仅 IN_STOCK/DEPLOYED/FAULT/RETIRED）");
        }
    }
}
