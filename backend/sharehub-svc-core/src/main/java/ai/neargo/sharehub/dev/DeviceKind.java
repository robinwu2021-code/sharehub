package ai.neargo.sharehub.dev;

/**
 * 设备类型（{@code dev_cabinet.device_type} · {@code gw_vendor_device_type.device_type} ·
 * {@code ord_order.device_type} 等，[ADR-021 通用设备命名与使用形态]）。
 *
 * <p><b>这是平台从"充电宝"泛化到"共享设备"的那一维</b>：柜机 / 充电桩 / 储物柜 / 按摩椅
 * 共用同一套站点、订单、分润骨架，按设备类型特化取价与指令族。
 *
 * <p>⚠️ 取值域目前只在 {@code V21__vendor_device_type.sql} 的列注释里写过
 * （{@code 'POWERBANK/EV_PILE/LOCKER'}），而代码里还用到 {@code MASSAGE_CHAIR}。
 * 本枚举把四个都纳入 —— 少一个的后果是新设备类型上线时，
 * 按注释写的过滤条件会**静默漏掉整类设备**。
 *
 * <p>{@code md_device_type} 表是这一维的字典表，但[实体-领域对象对账表]查出它
 * **全代码零引用**（待裁定是 ADR-021 的预留还是建早了）。在它接入之前，
 * 本枚举是唯一可执行的取值域。
 */
public enum DeviceKind {

    /** 共享充电宝柜机 —— 当前唯一在产的形态。 */
    POWERBANK,
    /** 电动车充电桩（S8）。 */
    EV_PILE,
    /** 储物柜。 */
    LOCKER,
    /** 共享按摩椅（S7 试点）。 */
    MASSAGE_CHAIR;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static DeviceKind of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("设备类型必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("设备类型非法: " + v
                    + "（仅 POWERBANK/EV_PILE/LOCKER/MASSAGE_CHAIR）");
        }
    }
}
