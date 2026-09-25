package ai.neargo.sharehub.alarm.eval;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 定时扫描型判定器（STATE）。返回「此刻成立」的全部条件；不再返回的即视为消失（引擎按恢复规则处理）。
 */
public interface StateEvaluator {

    Set<String> codes();

    /**
     * true = 按站点批次判定（引擎只拿本批站点的未关闭告警对账）；
     * false = 全局判定（订单类：一轮一次，对账全部未关闭告警）。
     */
    default boolean siteBatched() {
        return true;
    }

    List<Finding> evaluate(EvalScope scope, LocalDateTime now);
}
