package ai.neargo.sharehub.gw.driver.spi;

import java.util.Map;

/**
 * 设备驱动 SPI（ADR-003 分族版）。**每供应商 × 每设备类型一个实现**。
 *
 * <p>业务层不感知厂商差异：它只说「给这台机柜弹一个仓」，
 * 由 {@code DriverRegistry} 按 {@code (vendorCode, deviceType)} 路由到具体实现，
 * 再由实现翻译成厂商 API 调用或私有协议报文。
 *
 * <p><b>driver 的职责边界</b>：设备控制、遥测解析、验签、SN↔业务编号映射。
 * <b>不含</b>订单、计价、支付 —— 那些在 core（总纲 §1.5 红线）。
 *
 * <p><b>指令与报文不得携带消费者个人信息</b>（PDPL）：设备只需要知道「弹哪个仓」，
 * 不需要知道是谁在借。
 */
public interface DeviceDriver {

    /** 本驱动的能力声明。注册时据此校验并入库。 */
    DriverManifest manifest();

    /**
     * 下发指令。
     *
     * @param sn      设备序列号（厂商侧标识）
     * @param command 指令类型，须在 {@link DriverManifest#commands()} 内
     * @param params  指令参数，如 {@code {slotIndex: 3}}
     * @return 厂商侧的指令标识，用于对账回执；无则返回 null
     */
    String send(String sn, String command, Map<String, Object> params);

    /**
     * 解析上行报文为归一化事件。
     *
     * @return 事件类型 + 结构化载荷；无法识别时返回 null（由网关按「未知报文」留痕，不静默丢弃）
     */
    NormalizedEvent parse(String rawPayload);

    /**
     * @param eventType 归一化后的事件类型，须在 {@link DriverManifest#events()} 内
     * @param sn        设备序列号
     * @param data      结构化载荷
     */
    record NormalizedEvent(String eventType, String sn, Map<String, Object> data) {
    }
}
