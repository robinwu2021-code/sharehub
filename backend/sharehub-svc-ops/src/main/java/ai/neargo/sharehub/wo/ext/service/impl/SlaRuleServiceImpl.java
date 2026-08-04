package ai.neargo.sharehub.wo.ext.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.wo.ext.WorkOrderType;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.SlaRule;
import ai.neargo.sharehub.wo.ext.entity.WoSlaRule;
import ai.neargo.sharehub.wo.ext.mapper.WoSlaRuleMapper;
import ai.neargo.sharehub.wo.ext.service.SlaRuleService;
import org.springframework.stereotype.Service;

/** SLA 规则配置实现。 */
@Service
public class SlaRuleServiceImpl extends AbstractCrudService<WoSlaRule, SlaRule> implements SlaRuleService {

    public SlaRuleServiceImpl(WoSlaRuleMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "sla_no";
    }

    @Override
    protected String keyOf(WoSlaRule e) {
        return e.getSlaNo();
    }

    @Override
    protected void setKey(WoSlaRule e, String no) {
        e.setSlaNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.SLA_RULE;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"sla_no", "wo_type", "escalate_to"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"woType", "active"};
    }

    @Override
    protected void beforeCreate(WoSlaRule e) {
        normalize(e);
    }

    @Override
    protected void beforeUpdate(WoSlaRule e, WoSlaRule current) {
        if (e.getWoType() == null || e.getWoType().isBlank()) e.setWoType(current.getWoType());
        if (e.getActive() == null) e.setActive(current.getActive());
        normalize(e);
    }

    /** 类型收敛到 {@link WorkOrderType}；时限做基本合理性校验。 */
    private static void normalize(WoSlaRule e) {
        e.setWoType(WorkOrderType.of(e.getWoType()).name());
        if (e.getActive() == null) e.setActive(1);
        if (e.getResponseMins() == null) e.setResponseMins(0);
        if (e.getResolveMins() == null) e.setResolveMins(0);
        if (e.getResponseMins() < 0 || e.getResolveMins() < 0) {
            throw new IllegalArgumentException("SLA 时限不可为负");
        }
        // 响应必须早于解决：反了的话「已响应但未解决」这个区间不存在，超时判定会同时命中两条
        if (e.getResponseMins() > 0 && e.getResolveMins() > 0 && e.getResponseMins() > e.getResolveMins()) {
            throw new IllegalArgumentException("响应时限不可大于解决时限: "
                    + e.getResponseMins() + " > " + e.getResolveMins());
        }
    }

    @Override
    protected SlaRule toVO(WoSlaRule e) {
        return new SlaRule(e.getSlaNo(), e.getWoType(), e.getResponseMins(), e.getResolveMins(),
                e.getEscalateTo(), e.getActive() != null && e.getActive() == 1);
    }
}
