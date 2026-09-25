package ai.neargo.sharehub.inv;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 调拨单状态机（[db-design §3.3]）：{@code DRAFT → IN_TRANSIT → DONE}。
 *
 * <p>只有两步，但必须集中管：{@code DONE} 之后再改数量就是账实不符，
 * 而「已收货的单能不能退回在途」是个会反复被问的问题 —— 答案写在这里（不能），
 * 要退货应开一张反向调拨单，留两条痕，而不是把一条痕改回去。
 */
@Component
public class InvTransferStateMachine {

    /**
     * event → (from → to)。
     *
     * <p><b>状态是 {@link InvTransferStatus}，事件仍是字符串</b>：事件是动词、状态是名词。
     * 此前这里是三个 {@code String} 常量 —— 比裸串好，但挡不住外面传进来的任意字符串。
     */
    private static final Map<String, Map<InvTransferStatus, InvTransferStatus>> TRANSITIONS = Map.of(
            "SHIP", Map.of(InvTransferStatus.DRAFT, InvTransferStatus.IN_TRANSIT),      // 发出
            "RECEIVE", Map.of(InvTransferStatus.IN_TRANSIT, InvTransferStatus.DONE));   // 收货

    /** 校验并返回目标状态；非法迁移抛异常。 */
    public String next(String from, String event) {
        Map<InvTransferStatus, InvTransferStatus> m = TRANSITIONS.get(event);
        InvTransferStatus to = m == null ? null : m.get(InvTransferStatus.of(from));
        if (to == null) {
            throw new IllegalArgumentException("调拨单状态非法迁移: " + from + " --" + event + "--> ?");
        }
        return to.name();
    }

    /** 单据是否已终结（终态不可再改单头字段）。 */
    public boolean isTerminal(String status) {
        return InvTransferStatus.DONE.name().equals(status);
    }

    public Set<String> events() {
        return TRANSITIONS.keySet();
    }
}
