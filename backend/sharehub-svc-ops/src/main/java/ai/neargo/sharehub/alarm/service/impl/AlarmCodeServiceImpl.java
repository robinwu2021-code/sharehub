package ai.neargo.sharehub.alarm.service.impl;

import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.alarm.AlarmLevel;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmCode;
import ai.neargo.sharehub.alarm.entity.DevAlarmCode;
import ai.neargo.sharehub.alarm.mapper.DevAlarmCodeMapper;
import ai.neargo.sharehub.alarm.service.AlarmCodeService;
import org.springframework.stereotype.Service;

/**
 * 告警代码字典实现。行为全部来自 {@link AbstractCrudService}，本类只声明键/搜/筛/转 VO。
 *
 * <p>{@code keyPrefix()} 不覆盖 → {@code null} → {@code code} 是**自然键**
 * （{@code SLOT_STUCK} 这类对外有语义的码），新建必须由调用方显式给出，不代为取号。
 */
@Service
public class AlarmCodeServiceImpl extends AbstractCrudService<DevAlarmCode, AlarmCode>
        implements AlarmCodeService {

    public AlarmCodeServiceImpl(DevAlarmCodeMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "code";
    }

    @Override
    protected String keyOf(DevAlarmCode e) {
        return e.getCode();
    }

    @Override
    protected void setKey(DevAlarmCode e, String no) {
        e.setCode(no);
    }

    @Override
    protected String[] keywordColumns() {
        // 三语描述都可搜：运维按中文搜、外籍同事按英文搜，不该逼人切语言
        return new String[]{"code", "message", "message_en", "message_ar", "suggestion"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"level", "autoWorkOrder"};
    }

    @Override
    protected String orderColumn() {
        return "code";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected void beforeCreate(DevAlarmCode e) {
        if (e.getLevel() == null || e.getLevel().isBlank()) e.setLevel(AlarmLevel.WARN.name());
        if (e.getAutoWorkOrder() == null) e.setAutoWorkOrder(0); // 默认不自动开单，开单是要人干活的，得显式打开
    }

    @Override
    protected AlarmCode toVO(DevAlarmCode e) {
        return new AlarmCode(e.getCode(), e.getMessage(), e.getMessageEn(), e.getMessageAr(),
                e.getLevel(), e.getSuggestion(),
                e.getAutoWorkOrder() != null && e.getAutoWorkOrder() == 1);
    }
}
