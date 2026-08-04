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
    public static final Set<String> TERMINAL = Set.of("SOLD", "SCRAP");

    // event → (fromStatus → toStatus)
    private static final Map<String, Map<String, String>> TRANSITIONS;

    static {
        Map<String, Map<String, String>> m = new LinkedHashMap<>();
        m.put("DEPLOY", Map.of("IN_STOCK", "IN_CABINET"));                       // 投放/补货
        m.put("RENT", Map.of("IN_CABINET", "RENTED"));                           // 借出
        m.put("RETURN", Map.of("RENTED", "IN_CABINET"));                         // 归还（任意柜）
        m.put("FAULT_RETURN", Map.of("RENTED", "FAULT"));                        // 坏机归还
        m.put("OVERDUE", Map.of("RENTED", "LOST"));                              // 超时未归还
        m.put("RECOVER", Map.of("LOST", "IN_CABINET"));                          // 失而复得（LOST 是半终态）
        m.put("BUYOUT", Map.of("RENTED", "SOLD", "LOST", "SOLD"));               // 买断付费（含超时买断）
        m.put("REPORT_FAULT", Map.of("IN_CABINET", "FAULT", "IN_STOCK", "FAULT"));// 自检/上报故障
        m.put("REPAIR", Map.of("FAULT", "IN_STOCK"));                            // 维修回仓
        m.put("SCRAP", Map.of("FAULT", "SCRAP", "IN_STOCK", "SCRAP", "IN_CABINET", "SCRAP"));
        TRANSITIONS = Map.copyOf(m);
    }

    /** 校验并返回目标状态；非法迁移抛异常。 */
    public String next(String from, String event) {
        Map<String, String> m = TRANSITIONS.get(event);
        String to = m == null ? null : m.get(from);
        if (to == null) {
            throw new IllegalArgumentException("充电宝状态非法迁移: " + from + " --" + event + "--> ?");
        }
        return to;
    }

    /**
     * 按「目标状态」反查事件并校验（运营端「报废/丢失」等直接改状态的入口用）。
     * 存在多条路径时取先登记的一条；无路径抛异常。
     */
    public String eventFor(String from, String to) {
        if (from != null && from.equals(to)) {
            throw new IllegalArgumentException("充电宝状态未变更: " + from);
        }
        for (Map.Entry<String, Map<String, String>> e : TRANSITIONS.entrySet()) {
            if (to != null && to.equals(e.getValue().get(from))) {
                return e.getKey();
            }
        }
        throw new IllegalArgumentException("充电宝状态非法迁移: " + from + " --> " + to);
    }

    public Set<String> events() {
        return TRANSITIONS.keySet();
    }
}
