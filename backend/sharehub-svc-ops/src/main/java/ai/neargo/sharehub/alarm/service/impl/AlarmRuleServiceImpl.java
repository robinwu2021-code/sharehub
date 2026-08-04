package ai.neargo.sharehub.alarm.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRule;
import ai.neargo.sharehub.alarm.entity.DevAlarmRule;
import ai.neargo.sharehub.alarm.mapper.DevAlarmRuleMapper;
import ai.neargo.sharehub.alarm.service.AlarmRuleService;
import org.springframework.stereotype.Service;

/**
 * 告警通知规则实现。CRUD 走基类；本类额外承担静默窗口的**校验**与**判定**。
 */
@Service
public class AlarmRuleServiceImpl extends AbstractCrudService<DevAlarmRule, AlarmRule>
        implements AlarmRuleService {

    public AlarmRuleServiceImpl(DevAlarmRuleMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "rule_no";
    }

    @Override
    protected String keyOf(DevAlarmRule e) {
        return e.getRuleNo();
    }

    @Override
    protected void setKey(DevAlarmRule e, String no) {
        e.setRuleNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.ALARM_RULE; // AR*
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"rule_no", "alarm_code", "target"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"alarmCode", "channel", "method", "status"};
    }

    @Override
    protected void beforeCreate(DevAlarmRule e) {
        if (e.getMethod() == null || e.getMethod().isBlank()) e.setMethod("INSTANT");
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("ACTIVE");
        validateQuiet(e);
    }

    @Override
    protected void beforeUpdate(DevAlarmRule e, DevAlarmRule current) {
        validateQuiet(e);
    }

    @Override
    protected AlarmRule toVO(DevAlarmRule e) {
        return new AlarmRule(e.getRuleNo(), e.getAlarmCode(), e.getTarget(), e.getChannel(),
                e.getMethod(), e.getQuietStart(), e.getQuietEnd(),
                e.getEscalateMinutes(), e.getStatus());
    }

    // ——————————————————————— 静默窗口 ———————————————————————

    @Override
    public boolean inQuietWindow(DevAlarmRule rule, String hhmm) {
        if (rule == null || hhmm == null) return false;
        String from = rule.getQuietStart();
        String to = rule.getQuietEnd();
        if (!isHhmm(from) || !isHhmm(to)) return false; // 未配置窗口 = 不静默
        if (from.equals(to)) return false;              // 起止相同视为未配置，而非「全天静默」——全天静默应把规则置 INACTIVE

        // HH:mm 定长零填充，字典序即时间序，可直接比较，无需解析成时间对象。
        if (from.compareTo(to) < 0) {
            return hhmm.compareTo(from) >= 0 && hhmm.compareTo(to) < 0;   // 同日窗口 09:00→18:00
        }
        // 跨零点窗口 22:00→07:00：拆成 [from,24:00) ∪ [00:00,to)
        return hhmm.compareTo(from) >= 0 || hhmm.compareTo(to) < 0;
    }

    /** 静默窗口要么两端都给、要么都不给；只给一端是配置错误，落库后没人看得出来。 */
    private void validateQuiet(DevAlarmRule e) {
        boolean hasStart = isHhmm(e.getQuietStart());
        boolean hasEnd = isHhmm(e.getQuietEnd());
        if (blank(e.getQuietStart()) && blank(e.getQuietEnd())) return;
        if (!hasStart || !hasEnd) {
            throw new IllegalArgumentException("静默窗口必须成对给出且格式为 HH:mm: quietStart="
                    + e.getQuietStart() + ", quietEnd=" + e.getQuietEnd());
        }
        if (e.getEscalateMinutes() != null && e.getEscalateMinutes() < 0) {
            throw new IllegalArgumentException("escalateMinutes 不能为负: " + e.getEscalateMinutes());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** {@code CHAR(5)} 的 {@code HH:mm}，含范围校验 —— 只查长度会放过 {@code 99:99}。 */
    private static boolean isHhmm(String s) {
        if (s == null || s.length() != 5 || s.charAt(2) != ':') return false;
        try {
            int h = Integer.parseInt(s.substring(0, 2));
            int m = Integer.parseInt(s.substring(3, 5));
            return h >= 0 && h <= 23 && m >= 0 && m <= 59;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
