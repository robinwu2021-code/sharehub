package ai.neargo.sharehub.dev;

/**
 * 机柜状态（{@code dev_cabinet.status}，DDL 注释 {@code 'DEPLOYED/FAULT/RETIRED'}）。
 *
 * <p>⚠️ 与 {@link PowerbankStatus} **不是一个词表**，尽管两者都有 {@code FAULT} 与 {@code IN_STOCK}：
 * 机柜说的是这台设备本身的部署状态，充电宝说的是那块电池在哪、被谁拿着。
 * {@code DEPLOYED} 在机柜这里是"已铺设在站点上"。
 *
 * <p><b>本枚举与 DDL 当前定义逐项一致</b>：{@code V8__v2_alter.sql} §8.2 补了 {@link #IN_STOCK}
 * （"「库存调拨」需要区分仓库机柜与在投机柜"），并明确 {@code online_status} 是正交轴、不并入 status。
 * V2 最初建表时只有三个态 —— <b>读 DDL 要读当前 schema，不是最初的建表语句</b>。
 */
public enum CabinetStatus {

    /** 在库，未上架 —— 新建机柜的初始态。**DDL 注释里没有它**，见类注释。 */
    IN_STOCK,
    /** 运输中：随调拨单发出，签收后回 IN_STOCK（V107，对齐清单 C4）。 */
    IN_TRANSIT,
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
            throw new IllegalArgumentException("机柜状态非法: " + v + "（仅 IN_STOCK/IN_TRANSIT/DEPLOYED/FAULT/RETIRED）");
        }
    }
}
