package ai.neargo.sharehub.alarm.eval;

import ai.neargo.sharehub.alarm.AlarmCause;
import ai.neargo.sharehub.alarm.AlarmSubjectType;
import ai.neargo.sharehub.alarm.ImpactScope;
import ai.neargo.sharehub.api.core.port.TradeAnomalyPort;
import ai.neargo.sharehub.api.core.port.TradeAnomalyPort.OrderAnomaly;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 付了款没拿到宝 / 还了宝还在计费（TDD/05 §4.3）。全局判定（不按站点分批）：订单异常一轮扫一次。
 * 持续时长交给引擎的 hold_minutes；这里只取「已异常超过 1 分钟」的，避免把正在出宝的单当成异常。
 */
@Component
public class TradeAnomalyEvaluator implements StateEvaluator {

    public static final String RENT_NOT_DELIVERED = "RENT_NOT_DELIVERED";
    public static final String RETURN_NOT_RECOGNIZED = "RETURN_NOT_RECOGNIZED";

    private final TradeAnomalyPort trade;

    public TradeAnomalyEvaluator(TradeAnomalyPort trade) {
        this.trade = trade;
    }

    @Override
    public Set<String> codes() {
        return Set.of(RENT_NOT_DELIVERED, RETURN_NOT_RECOGNIZED);
    }

    @Override
    public boolean siteBatched() {
        return false;
    }

    @Override
    public List<Finding> evaluate(EvalScope scope, LocalDateTime now) {
        List<Finding> out = new ArrayList<>();
        for (OrderAnomaly a : trade.undelivered(1, 500)) out.add(of(RENT_NOT_DELIVERED, AlarmCause.CANCEL_FAILED, a));
        for (OrderAnomaly a : trade.returnUnrecognized(1, 500)) out.add(of(RETURN_NOT_RECOGNIZED, AlarmCause.SN_SEEN, a));
        return out;
    }

    private static Finding of(String code, AlarmCause cause, OrderAnomaly a) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("orderNo", a.orderNo());
        if (a.cUserNo() != null) attrs.put("cUserNo", a.cUserNo());
        if (a.slotIndex() != null) attrs.put("slotIndex", String.valueOf(a.slotIndex()));
        if (a.since() != null) attrs.put("since", a.since().toString());
        return new Finding(code, AlarmSubjectType.ORDER, a.orderNo(), a.siteNo(), a.cabinetNo(), a.agentNo(), null, cause,
                ImpactScope.ORDER, 1, List.of(new Finding.Evidence(code, a.cabinetNo(), a.slotIndex(), a.since(), a.powerbankNo())), attrs);
    }
}
