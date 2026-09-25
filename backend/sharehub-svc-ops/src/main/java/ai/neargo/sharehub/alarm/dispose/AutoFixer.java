package ai.neargo.sharehub.alarm.dispose;

import ai.neargo.sharehub.alarm.entity.DevAlarm;

/** 系统自愈动作（disposition=AUTO_FIX）。实现必须幂等：对象已不在异常态时返回 true。 */
public interface AutoFixer {

    String code();

    /** @return true = 已修复（告警以 AUTO_FIXED 关闭）；false = 本次失败，引擎计数后重试或转兜底处置 */
    boolean fix(DevAlarm alarm);
}
