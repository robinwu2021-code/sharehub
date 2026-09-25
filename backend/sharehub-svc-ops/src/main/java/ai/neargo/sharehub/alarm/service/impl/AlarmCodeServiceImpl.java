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
        e.setBuiltin(0);   // 内置码只由迁移种子写；从界面建的一律是自定义码
        validateBusiness(e);
    }

    /**
     * 2026-09-25 业务告警码：<b>内置码的身份不可改</b> —— 域 / 主体 / 判定方式 / 取代关系决定了判定器怎么产出它，
     * 改了等于换了一个码却沿用旧码值，历史告警的统计口径随之失真。可调的是阈值、时长、优先级、处置与开关。
     */
    @Override
    protected void beforeUpdate(DevAlarmCode e, DevAlarmCode current) {
        e.setBuiltin(current.getBuiltin());
        if (current.getBuiltin() != null && current.getBuiltin() == 1) {
            e.setDomain(current.getDomain());
            e.setSubjectType(current.getSubjectType());
            e.setEvalType(current.getEvalType());
            e.setSupersedes(current.getSupersedes());
        }
        validateBusiness(e);
    }

    private static void validateBusiness(DevAlarmCode e) {
        if (e.getDomain() != null) ai.neargo.sharehub.alarm.AlarmDomain.of(e.getDomain());
        if (e.getSubjectType() != null) ai.neargo.sharehub.alarm.AlarmSubjectType.of(e.getSubjectType());
        if (e.getEvalType() != null) ai.neargo.sharehub.alarm.AlarmEvalType.of(e.getEvalType());
        if (e.getDisposition() != null) ai.neargo.sharehub.alarm.AlarmDisposition.of(e.getDisposition());
        if (e.getMergeScope() != null) ai.neargo.sharehub.alarm.MergeScope.of(e.getMergeScope());
        if (e.getRecoverRule() != null) ai.neargo.sharehub.alarm.RecoverRule.of(e.getRecoverRule());
        if (e.getBasePriority() != null) ai.neargo.sharehub.wo.WoPriority.of(e.getBasePriority());
        for (Integer m : new Integer[]{e.getHoldMinutes(), e.getWindowMinutes(), e.getWoDelayMinutes(), e.getRecoverHoldMinutes()}) {
            if (m != null && (m < 0 || m > 7 * 24 * 60)) throw ai.neargo.sharehub.common.BizException.badRequest("error.alarm.duration_range", m);
        }
    }

    @Override
    protected AlarmCode toVO(DevAlarmCode e) {
        return new AlarmCode(e.getCode(), e.getMessage(), e.getMessageEn(), e.getMessageAr(),
                e.getLevel(), e.getSuggestion(),
                e.getAutoWorkOrder() != null && e.getAutoWorkOrder() == 1,
                e.getArchivedAt() == null ? null : e.getArchivedAt().toString(),
                e.getDomain() == null ? null : new ai.neargo.sharehub.alarm.dto.AlarmDtos.BusinessCode(e.getDomain(), e.getSubjectType(),
                        e.getEvalType(), e.getHoldMinutes(), e.getWindowMinutes(), e.getThreshold(), flag(e.getBusinessHoursOnly()),
                        e.getBasePriority(), flag(e.getImpactAdjust()), e.getDisposition(), e.getOwnerRole(), e.getWoDelayMinutes(),
                        e.getMergeScope(), e.getRecoverRule(), e.getRecoverHoldMinutes(), e.getSupersedes(), flag(e.getEnabled()),
                        flag(e.getBuiltin())));
    }

    private static boolean flag(Integer v) {
        return v != null && v == 1;
    }
}
