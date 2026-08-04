package ai.neargo.sharehub.alarm.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRule;
import ai.neargo.sharehub.alarm.entity.DevAlarmRule;

/** 告警通知规则。配置类 → 继承通用 CRUD；静默窗口的语义校验在 impl 的 {@code beforeCreate/beforeUpdate}。 */
public interface AlarmRuleService extends CrudService<DevAlarmRule, AlarmRule> {

    /**
     * 判断某时刻是否落在规则的静默窗口内（发通知前必调）。
     *
     * <p>放在 service 而不是调用方，是因为**跨零点窗口**（{@code 22:00}→{@code 07:00}）
     * 是最容易被各调用方各写错一遍的地方。
     *
     * @param rule 规则
     * @param hhmm 待判断时刻，{@code HH:mm}
     * @return true = 在静默窗口内，本次不应发送
     */
    boolean inQuietWindow(DevAlarmRule rule, String hhmm);
}
