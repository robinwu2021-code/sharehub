package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.gw.driver.DriverRegistry;
import ai.neargo.sharehub.gw.driver.spi.CommandFamily;
import ai.neargo.sharehub.gw.driver.spi.DeviceDriver;
import ai.neargo.sharehub.gw.driver.spi.DriverManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 驱动 SPI 分族（ADR-018 §四 · 总纲 §1.5 供应商红线）。
 *
 * <p>纯单测：SPI 是纯逻辑，起 Spring 上下文只会让这些用例慢 8 秒。
 */
class DriverSpiTest {

    private static DeviceDriver driver(DriverManifest m) {
        return new DeviceDriver() {
            @Override public DriverManifest manifest() { return m; }
            @Override public String send(String sn, String c, Map<String, Object> p) { return "ok"; }
            @Override public NormalizedEvent parse(String raw) { return null; }
        };
    }

    private static DriverManifest pbManifest() {
        return new DriverManifest("V1", "POWERBANK", "POWERBANK", "VENDOR_CLOUD",
                Set.of("EJECT_SLOT", "REBOOT"), Set.of("EJECT_OK", "HEARTBEAT"));
    }

    // ─────────── 分族 ───────────

    /** 每个族只暴露「通用 + 本族」，互不串味。 */
    @Test
    void families_expose_generic_plus_own_commands_only() {
        assertThat(CommandFamily.commandsOf("POWERBANK"))
                .contains("EJECT_SLOT", "REBOOT")
                .doesNotContain("START_CHARGE", "OPEN_CELL");
        assertThat(CommandFamily.commandsOf("EV_PILE"))
                .contains("START_CHARGE", "REBOOT")
                .doesNotContain("EJECT_SLOT");
        assertThat(CommandFamily.commandsOf("LOCKER"))
                .contains("OPEN_CELL")
                .doesNotContain("START_CHARGE", "EJECT_SLOT");
    }

    /**
     * 充电宝族的事件是<b>物理事实</b>，不是业务语义。
     *
     * <p>{@code RENT_CONFIRMED}/{@code RETURNED} 已被更名为 {@code EJECT_OK}/{@code ITEM_INSERTED}
     * —— 「算不算某订单的归还」由 core 判定（换柜归还、误插回、非订单宝插回都是业务规则）。
     * 命名带业务语义，供应商边界迟早被写穿。
     */
    @Test
    void powerbank_events_are_physical_facts_not_business_semantics() {
        assertThat(CommandFamily.eventsOf("POWERBANK"))
                .contains("EJECT_OK", "ITEM_INSERTED")
                .doesNotContain("RENT_CONFIRMED", "RETURNED");
    }

    /** 充电桩族对齐 OCPP 原生概念，不自创 —— 自创等于给每家桩企做一次适配。 */
    @Test
    void ev_pile_events_align_with_ocpp() {
        assertThat(CommandFamily.eventsOf("EV_PILE"))
                .contains("TX_START", "TX_STOP", "METER_VALUE", "CONNECTOR_STATUS");
    }

    // ─────────── 红线：白名单在注册时就挡住越界能力 ───────────

    /**
     * <b>厂商的订单类能力注册即失败。</b>
     *
     * <p>充电宝厂商开放平台普遍连「租借下单」一起卖。声明这类能力会在构造 manifest 时抛异常
     * —— 挡在注册环节而不是下发环节：下发时才拒绝，意味着这条能力已经写进业务代码了。
     */
    @Test
    void vendor_order_capability_is_rejected_at_registration() {
        assertThatThrownBy(() -> new DriverManifest("V1", "POWERBANK", "POWERBANK", "VENDOR_CLOUD",
                Set.of("EJECT_SLOT", "CREATE_RENT_ORDER"), Set.of("EJECT_OK")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("族之外的指令")
                .hasMessageContaining("不得接入");
    }

    /** 未知指令族同样在注册时拒绝。 */
    @Test
    void unknown_family_is_rejected() {
        assertThatThrownBy(() -> new DriverManifest("V1", "SCOOTER", "SCOOTER", "DIRECT",
                Set.of("REBOOT"), Set.of("ONLINE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知指令族");
    }

    // ─────────── 路由与下发校验 ───────────

    @Test
    void registry_routes_by_vendor_and_device_type() {
        DriverRegistry reg = new DriverRegistry(List.of(driver(pbManifest())));

        assertThat(reg.find("V1", "POWERBANK")).isPresent();
        assertThat(reg.find("V1", "EV_PILE")).as("同厂商不同设备类型是不同 driver").isEmpty();
        assertThat(reg.dispatch("V1", "POWERBANK", "sn-1", "EJECT_SLOT", Map.of())).isEqualTo("ok");
    }

    /** 给充电宝发充电桩的指令 → 下发前就拒，不发出去等厂商返回看不懂的错误码。 */
    @Test
    void cross_family_command_is_refused_before_dispatch() {
        DriverRegistry reg = new DriverRegistry(List.of(driver(pbManifest())));

        assertThatThrownBy(() -> reg.dispatch("V1", "POWERBANK", "sn-1", "START_CHARGE", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于 POWERBANK 族");
    }

    /** 族里有、但这家厂商没声明支持的指令，同样拒绝。 */
    @Test
    void command_not_declared_by_this_vendor_is_refused() {
        DriverRegistry reg = new DriverRegistry(List.of(driver(pbManifest())));

        assertThatThrownBy(() -> reg.dispatch("V1", "POWERBANK", "sn-1", "UNLOCK", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未声明支持指令");
    }

    /**
     * 同一 {@code (vendor, deviceType)} 注册多个 driver → <b>启动即失败</b>。
     *
     * <p>放任的话运行期会随机选中一个，「为什么这台柜机行为不对」将变成不可复现的问题。
     */
    @Test
    void duplicate_registration_fails_at_startup() {
        assertThatThrownBy(() -> new DriverRegistry(List.of(driver(pbManifest()), driver(pbManifest()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注册了多个 driver");
    }
}
