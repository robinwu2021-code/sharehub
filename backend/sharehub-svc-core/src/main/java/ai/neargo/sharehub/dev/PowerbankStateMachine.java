package ai.neargo.sharehub.dev;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 充电宝资产生命周期状态机（[db-design §9A.1] 定稿 7 态，写法照 {@code wo/WoStateMachine}）。
 *
 * <pre>
 * IN_STOCK ──DEPLOY──▶ IN_CABINET ──RENT──▶ RENTED
 *                          ▲                  │
 *                          └──── RETURN ──────┤
 *                                             ├── OVERDUE ──▶ LOST ──RECOVER──▶ IN_CABINET
 *                                             ├── CONFIRM_LOST ─▶ LOST  （疑似丢失经人工核实，V113）
 *                                             ├── BUYOUT  ──▶ SOLD   (终态)
 *                                             └── FAULT_RETURN ─▶ FAULT
 * IN_CABINET ──REPORT_FAULT──▶ FAULT ──REPAIR──▶ IN_STOCK
 * FAULT / IN_STOCK / IN_CABINET ──SCRAP──▶ SCRAP (终态)
 * </pre>
 *
 * <p><b>位置不在状态机里</b>：{@code cabinetNo}/{@code slotIndex} 由调用方按事件同步维护，
 * 状态机只判定生命周期迁移是否合法（§9A.1 的核心判断，避免位置与状态两处漂移）。
 */
@Component
public class PowerbankStateMachine {

    /** 终态：不可再迁出。 */
    public static final Set<String> TERMINAL =
            Set.of(PowerbankStatus.SOLD.name(), PowerbankStatus.SCRAP.name());

    /**
     * event → (fromStatus → toStatus)。
     *
     * <p><b>状态是 {@link PowerbankStatus}，事件仍是字符串</b>：事件是动词、状态是名词。
     * 本机尤其需要这一层 —— 十条边里有四条的事件名与某个状态名高度相似
     * （{@code SCRAP} 事件 vs {@code SCRAP} 状态、{@code REPORT_FAULT} vs {@code FAULT}），
     * 裸串时把事件填进状态位不会有任何提示。
     */
    private static final Map<String, Map<PowerbankStatus, PowerbankStatus>> TRANSITIONS;

    static {
        Map<String, Map<PowerbankStatus, PowerbankStatus>> m = new LinkedHashMap<>();
        m.put("DEPLOY", Map.of(PowerbankStatus.IN_STOCK, PowerbankStatus.IN_CABINET));      // 投放/补货
        m.put("RENT", Map.of(PowerbankStatus.IN_CABINET, PowerbankStatus.RENTED));          // 借出
        m.put("RETURN", Map.of(PowerbankStatus.RENTED, PowerbankStatus.IN_CABINET));        // 归还（任意柜）
        m.put("FAULT_RETURN", Map.of(PowerbankStatus.RENTED, PowerbankStatus.FAULT));       // 坏机归还
        m.put("OVERDUE", Map.of(PowerbankStatus.RENTED, PowerbankStatus.LOST));             // 超时未归还
        m.put("CONFIRM_LOST", Map.of(PowerbankStatus.RENTED, PowerbankStatus.LOST));        // 疑似丢失经人工核实（V113；与 OVERDUE 同终点，来源不同）
        m.put("RECOVER", Map.of(PowerbankStatus.LOST, PowerbankStatus.IN_CABINET));         // 失而复得（LOST 是半终态）
        m.put("BUYOUT", Map.of(PowerbankStatus.RENTED, PowerbankStatus.SOLD,
                               PowerbankStatus.LOST, PowerbankStatus.SOLD));                // 买断付费（含超时买断）
        m.put("REPORT_FAULT", Map.of(PowerbankStatus.IN_CABINET, PowerbankStatus.FAULT,
                                     PowerbankStatus.IN_STOCK, PowerbankStatus.FAULT));     // 自检/上报故障
        m.put("REPAIR", Map.of(PowerbankStatus.FAULT, PowerbankStatus.IN_STOCK));           // 维修回仓
        m.put("SCRAP", Map.of(PowerbankStatus.FAULT, PowerbankStatus.SCRAP,
                              PowerbankStatus.IN_STOCK, PowerbankStatus.SCRAP,
                              PowerbankStatus.IN_CABINET, PowerbankStatus.SCRAP));
        TRANSITIONS = Map.copyOf(m);
    }

    /** 校验并返回目标状态；非法迁移抛异常。 */
    public String next(String from, String event) {
        Map<PowerbankStatus, PowerbankStatus> m = TRANSITIONS.get(event);
        PowerbankStatus to = m == null ? null : m.get(PowerbankStatus.of(from));
        if (to == null) {
            throw new IllegalArgumentException("充电宝状态非法迁移: " + from + " --" + event + "--> ?");
        }
        return to.name();
    }

    /**
     * 按「目标状态」反查事件并校验（运营端「报废/丢失」等直接改状态的入口用）。
     * 存在多条路径时取先登记的一条；无路径抛异常。
     */
    public String eventFor(String from, String to) {
        if (from != null && from.equals(to)) {
            throw new IllegalArgumentException("充电宝状态未变更: " + from);
        }
        PowerbankStatus fromSt = PowerbankStatus.of(from);
        for (Map.Entry<String, Map<PowerbankStatus, PowerbankStatus>> e : TRANSITIONS.entrySet()) {
            PowerbankStatus hit = e.getValue().get(fromSt);
            if (hit != null && hit.name().equals(to)) {
                return e.getKey();
            }
        }
        throw new IllegalArgumentException("充电宝状态非法迁移: " + from + " --> " + to);
    }

    public Set<String> events() {
        return TRANSITIONS.keySet();
    }
}
