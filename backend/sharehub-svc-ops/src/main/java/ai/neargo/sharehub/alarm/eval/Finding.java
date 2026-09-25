package ai.neargo.sharehub.alarm.eval;

import ai.neargo.sharehub.alarm.AlarmCause;
import ai.neargo.sharehub.alarm.AlarmSubjectType;
import ai.neargo.sharehub.alarm.ImpactScope;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 此刻成立的一个条件。<b>判定器只产出它，不写库</b> —— 开、关、处置全在引擎，判定器可以随意重跑。
 *
 * @param attrs 处置要用的附加信息（自愈器读：订单号、仓位、宝入柜时刻…）
 */
public record Finding(String code, AlarmSubjectType subjectType, String subjectNo, String siteNo, String cabinetNo,
                      String agentNo, String regionId, AlarmCause cause, ImpactScope scope, int inFlightOrders,
                      List<Evidence> evidence, Map<String, String> attrs) {

    /** attrs 键：判定器指定的优先级（LOW / MEDIUM / HIGH / URGENT），影响评估直接采用。 */
    public static final String ATTR_PRIORITY = "priority";

    /** attrs 键：值 "true" = 告警成立即以告警身份挂整柜停借（关闭时释放）。离线类由根因 OFFLINE 隐含，不必再标。 */
    public static final String ATTR_STOP_RENT = "stopRent";

    public String dedupKey() {
        return code + ":" + subjectType + ":" + subjectNo;
    }

    public record Evidence(String signal, String cabinetNo, Integer slot, LocalDateTime at, String note) {
    }
}
