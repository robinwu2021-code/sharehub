package ai.neargo.sharehub.gw.driver.spi;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 指令与事件的**分族定义**（ADR-018 §四）。
 *
 * <p>ADR-003 定的 SPI 方向正确，但它的指令集是充电宝专用的
 * （{@code EJECT_ANY}/{@code SLOT_REPORT}/{@code RETURNED}），充电桩一个都用不上。
 * 改为 <b>通用集 + 类型族</b>：每个设备类型只暴露「通用 + 本族」的能力。
 *
 * <h3>⚠️ 事件命名一律是物理事实，不是业务语义</h3>
 * 充电宝族的事件是 {@code EJECT_OK}（仓门弹出成功）与 {@code ITEM_INSERTED}（有宝插回），
 * <b>不是</b> {@code RENT_CONFIRMED} / {@code RETURNED}。
 * 「这算不算某个订单的归还」由 core 判定 —— 换柜归还、误插回、非订单宝插回
 * 全是 core 的业务规则，设备只报告它看见了什么。
 * <b>命名带业务语义，供应商边界迟早被写穿</b>（总纲 §1.5 红线）。
 *
 * <h3>充电桩族对齐 OCPP，不自创</h3>
 * {@code TX_START}/{@code TX_STOP}/{@code METER_VALUE}/{@code CONNECTOR_STATUS}
 * 是 OCPP 1.6J/2.0.1 的原生概念，绝大多数桩企原生支持。
 * 自创一套等于给每一家都做一次适配。
 */
public final class CommandFamily {

    private CommandFamily() {
    }

    public static final String GENERIC = "GENERIC";
    public static final String POWERBANK = "POWERBANK";
    public static final String EV_PILE = "EV_PILE";
    public static final String LOCKER = "LOCKER";

    /** 所有设备都支持的指令。 */
    private static final Set<String> GENERIC_COMMANDS =
            Set.of("REBOOT", "LOCATE", "QUERY_STATUS", "OTA_PUSH");

    /** 所有设备都会上报的事件。 */
    private static final Set<String> GENERIC_EVENTS =
            Set.of("ONLINE", "OFFLINE", "HEARTBEAT", "FAULT", "OTA_RESULT");

    private static final Map<String, Set<String>> FAMILY_COMMANDS = Map.of(
            POWERBANK, Set.of("EJECT_ANY", "EJECT_SLOT", "LOCK", "UNLOCK"),
            EV_PILE, Set.of("START_CHARGE", "STOP_CHARGE", "SET_CURRENT", "UNLOCK_CONNECTOR"),
            LOCKER, Set.of("OPEN_CELL", "QUERY_CELLS"));

    private static final Map<String, Set<String>> FAMILY_EVENTS = Map.of(
            // 物理事实：仓门弹出成功 / 有物品被插回。归还判定归 core。
            POWERBANK, Set.of("SLOT_REPORT", "EJECT_OK", "ITEM_INSERTED"),
            EV_PILE, Set.of("TX_START", "TX_STOP", "METER_VALUE", "CONNECTOR_STATUS"),
            LOCKER, Set.of("CELL_OPENED", "CELL_CLOSED", "OVERTIME"));

    /** 某设备类型允许的全部指令 = 通用 + 本族。 */
    public static Set<String> commandsOf(String family) {
        return union(GENERIC_COMMANDS, FAMILY_COMMANDS.getOrDefault(family, Set.of()));
    }

    /** 某设备类型允许的全部事件 = 通用 + 本族。 */
    public static Set<String> eventsOf(String family) {
        return union(GENERIC_EVENTS, FAMILY_EVENTS.getOrDefault(family, Set.of()));
    }

    /** 已知的族。未知族在注册驱动时就该被拒绝，而不是等到下发指令时。 */
    public static Set<String> known() {
        return FAMILY_COMMANDS.keySet();
    }

    /**
     * 校验指令是否属于该族。
     *
     * <p>用途之一是**防呆**：给充电桩发 {@code EJECT_SLOT} 应该在下发前被拒绝，
     * 而不是发出去让厂商云返回一个看不懂的错误码。
     */
    public static boolean supportsCommand(String family, String command) {
        return commandsOf(family).contains(command);
    }

    public static boolean supportsEvent(String family, String event) {
        return eventsOf(family).contains(event);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        return Stream.concat(a.stream(), b.stream()).collect(Collectors.toUnmodifiableSet());
    }

    /** 供运营端「设备类型能力」页展示。 */
    public static List<String> sortedCommands(String family) {
        return commandsOf(family).stream().sorted().toList();
    }

    public static List<String> sortedEvents(String family) {
        return eventsOf(family).stream().sorted().toList();
    }
}
