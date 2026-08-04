package ai.neargo.sharehub.gw.driver.powerbank;

import ai.neargo.sharehub.gw.driver.spi.DeviceDriver;
import ai.neargo.sharehub.gw.driver.spi.DriverManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 充电宝参考驱动（形态 A：厂商云 API）。
 *
 * <p><b>这是骨架，不是可上线的实现</b>：{@code send} 只记日志不真调厂商接口。
 * 它的作用是（1）证明 SPI 可被实现、（2）作为接第一家真实厂商时的样板、
 * （3）让 {@link ai.neargo.sharehub.gw.driver.DriverRegistry} 在测试里有东西可路由。
 *
 * <p><b>manifest 里没有任何订单/支付类能力</b> —— 充电宝厂商开放平台通常连
 * 「租借下单/代收款」一起提供，本平台一律不用（总纲 §1.5 红线）：订单永远由 core 建。
 * 声明越界能力会在构造 {@link DriverManifest} 时直接抛异常。
 */
@Component
public class DemoPowerbankDriver implements DeviceDriver {

    private static final Logger log = LoggerFactory.getLogger(DemoPowerbankDriver.class);

    private static final DriverManifest MANIFEST = new DriverManifest(
            "DEMO", "POWERBANK", "POWERBANK", "VENDOR_CLOUD",
            Set.of("EJECT_SLOT", "EJECT_ANY", "REBOOT", "QUERY_STATUS"),
            // 事件是**物理事实**：仓门弹出成功 / 有宝插回。
            // 「算不算某订单的归还」由 core 判定，driver 不下这个结论。
            Set.of("EJECT_OK", "ITEM_INSERTED", "SLOT_REPORT", "ONLINE", "OFFLINE", "HEARTBEAT"));

    @Override
    public DriverManifest manifest() {
        return MANIFEST;
    }

    @Override
    public String send(String sn, String command, Map<String, Object> params) {
        log.info("[demo-driver] 下发 sn={} command={} params={}", sn, command, params);
        return "DEMO-" + System.nanoTime();
    }

    @Override
    public NormalizedEvent parse(String rawPayload) {
        // 真实驱动在这里做验签 + 协议解析 + SN 映射。
        // 无法识别时返回 null —— 由网关按「未知报文」留痕，**不静默丢弃**：
        // 丢弃会让协议升级导致的解析失败完全不可见。
        if (rawPayload == null || rawPayload.isBlank()) return null;
        return new NormalizedEvent("HEARTBEAT", "demo-sn", Map.of("raw", rawPayload));
    }
}
