package ai.neargo.sharehub.dev;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 机柜状态机（TDD-运营核心流程/04）。状态只经动作改 —— 编辑接口不再收 status。
 *
 * <p>IN_TRANSIT（运输中）与 SHIP / RECEIVE 两条边随调拨（L1）接入；DDL 列注释已预留该值。
 */
@Component
public class CabinetStateMachine {

    private static final Map<String, Map<CabinetStatus, CabinetStatus>> TRANSITIONS = Map.of(
            "GO_LIVE", Map.of(CabinetStatus.IN_STOCK, CabinetStatus.DEPLOYED),
            "MARK_FAULT", Map.of(CabinetStatus.DEPLOYED, CabinetStatus.FAULT),
            "REPAIR", Map.of(CabinetStatus.FAULT, CabinetStatus.DEPLOYED),
            "UNDEPLOY", Map.of(CabinetStatus.DEPLOYED, CabinetStatus.IN_STOCK, CabinetStatus.FAULT, CabinetStatus.IN_STOCK),
            "RETIRE", Map.of(CabinetStatus.IN_STOCK, CabinetStatus.RETIRED, CabinetStatus.FAULT, CabinetStatus.RETIRED),
            // 调拨（V107）：发货 → 运输中，签收 → 回在库。由调拨单驱动，不单独出按钮
            "SHIP", Map.of(CabinetStatus.IN_STOCK, CabinetStatus.IN_TRANSIT),
            "RECEIVE", Map.of(CabinetStatus.IN_TRANSIT, CabinetStatus.IN_STOCK));

    public String next(String from, String event) {
        Map<CabinetStatus, CabinetStatus> m = TRANSITIONS.get(event);
        CabinetStatus to = m == null ? null : m.get(CabinetStatus.of(from));
        if (to == null) throw ai.neargo.sharehub.common.BizException.badRequest("error.state.illegal_transition", from, event);
        return to.name();
    }
}
