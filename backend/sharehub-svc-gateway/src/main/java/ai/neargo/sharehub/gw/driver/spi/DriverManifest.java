package ai.neargo.sharehub.gw.driver.spi;

import java.util.Set;

/**
 * 驱动能力声明（ADR-018 §四 · 总纲 §1.5）。每个 driver 注册时声明自己是谁、能做什么。
 *
 * <p><b>{@code commands}/{@code events} 是白名单，而白名单是「供应商只做设备管理」这条红线的执行手段。</b>
 * 充电宝厂商开放平台普遍连「租借下单/代收款」一起卖 —— 那些能力不在白名单里，
 * 于是在 driver 层面就<b>不存在</b>，想调也调不到。订单永远由 core 建。
 *
 * <p>白名单还必须是「族的子集」：声明了本族没有的指令 = 要么族定义漏了、
 * 要么厂商在往里塞业务能力，两种都该在注册时就拒绝。
 *
 * @param vendorCode 供应商编码
 * @param deviceType 设备类型（POWERBANK/EV_PILE/LOCKER）
 * @param family     指令族，通常与 deviceType 同名
 * @param form       接入形态：VENDOR_CLOUD(A 厂商云) / DIRECT(B 设备直连) / OCPP16J / OCPP201(C 行业标准)
 * @param commands   本 driver 实际支持的指令（须 ⊆ 族的指令集）
 * @param events     本 driver 会上报的事件（须 ⊆ 族的事件集）
 */
public record DriverManifest(String vendorCode, String deviceType, String family, String form,
                             Set<String> commands, Set<String> events) {

    public DriverManifest {
        if (vendorCode == null || vendorCode.isBlank()) {
            throw new IllegalArgumentException("driver 必须声明 vendorCode");
        }
        if (!CommandFamily.known().contains(family)) {
            throw new IllegalArgumentException("未知指令族: " + family + "，已知: " + CommandFamily.known());
        }
        // 越界能力在**注册时**就拒绝，不等到下发时才发现 ——
        // 下发时才拒绝意味着这条能力已经写进了业务代码，改起来要动调用方。
        Set<String> allowedCmd = CommandFamily.commandsOf(family);
        for (String c : commands) {
            if (!allowedCmd.contains(c)) {
                throw new IllegalArgumentException(
                        "driver " + vendorCode + " 声明了 " + family + " 族之外的指令: " + c
                                + "。若这是厂商的订单/支付类能力，**按红线不得接入**（总纲 §1.5）；"
                                + "若确属设备能力，应先扩族定义。");
            }
        }
        Set<String> allowedEvt = CommandFamily.eventsOf(family);
        for (String e : events) {
            if (!allowedEvt.contains(e)) {
                throw new IllegalArgumentException(
                        "driver " + vendorCode + " 声明了 " + family + " 族之外的事件: " + e);
            }
        }
    }
}
