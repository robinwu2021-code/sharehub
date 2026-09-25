package ai.neargo.sharehub.alarm.dispose;

import ai.neargo.sharehub.alarm.entity.DevAlarm;
import ai.neargo.sharehub.alarm.eval.TradeAnomalyEvaluator;
import ai.neargo.sharehub.api.core.port.TradeAnomalyPort;
import ai.neargo.sharehub.api.core.port.TradeAnomalyPort.OrderAnomaly;
import ai.neargo.sharehub.api.core.port.TradeRemedyPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** L0 交易自愈器（TDD/05 §7.2）：付了款没拿到宝 → 撤单；宝已在柜中 → 按识别时刻结单。 */
@Configuration
public class TradeFixers {

    @Bean
    AutoFixer rentNotDeliveredFixer(TradeRemedyPort remedy) {
        return new AutoFixer() {
            @Override
            public String code() {
                return TradeAnomalyEvaluator.RENT_NOT_DELIVERED;
            }

            @Override
            public boolean fix(DevAlarm a) {
                return remedy.cancelUndelivered(a.getSubjectNo(), "出宝失败自动撤单（告警 " + a.getAlarmNo() + "）");
            }
        };
    }

    @Bean
    AutoFixer returnNotRecognizedFixer(TradeRemedyPort remedy, TradeAnomalyPort anomalies) {
        return new AutoFixer() {
            @Override
            public String code() {
                return TradeAnomalyEvaluator.RETURN_NOT_RECOGNIZED;
            }

            @Override
            public boolean fix(DevAlarm a) {
                OrderAnomaly o = anomalies.orderInfo(a.getSubjectNo());
                if (o == null) return true;   // 订单不在了：无需再处理
                // 计费截止 = 宝首次在柜中被识别的时刻，不是发现问题的时刻
                return remedy.finishAt(o.orderNo(), o.cabinetNo(), o.slotIndex(), o.since());
            }
        };
    }
}
