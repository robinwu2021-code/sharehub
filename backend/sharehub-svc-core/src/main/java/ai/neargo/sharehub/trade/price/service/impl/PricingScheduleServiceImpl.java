package ai.neargo.sharehub.trade.price.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PricingSchedule;
import ai.neargo.sharehub.trade.price.entity.PriceSchedule;
import ai.neargo.sharehub.trade.price.mapper.PriceScheduleMapper;
import ai.neargo.sharehub.trade.price.service.PricingScheduleService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 活动/时段价实现。
 *
 * <p>唯一的业务约束是倍率校验：{@code multiplier} 允许 &gt; 1（高峰加价），
 * 只拒绝负数 —— 若照抄「比率 0..1」的通用校验会把加价场景全部误杀。
 */
@Service
public class PricingScheduleServiceImpl extends AbstractCrudService<PriceSchedule, PricingSchedule>
        implements PricingScheduleService {

    /** 倍率上限：纯防呆（防止手滑多敲一位），非业务规则。 */
    private static final BigDecimal MULTIPLIER_MAX = new BigDecimal("99.9999");

    public PricingScheduleServiceImpl(PriceScheduleMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "rule_no";
    }

    @Override
    protected String keyOf(PriceSchedule e) {
        return e.getRuleNo();
    }

    @Override
    protected void setKey(PriceSchedule e, String no) {
        e.setRuleNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.PRICING_SCHEDULE;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"rule_no", "name", "period"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"active", "regionId"};
    }

    @Override
    protected void beforeCreate(PriceSchedule e) {
        if (e.getMultiplier() == null) e.setMultiplier(BigDecimal.ONE);
        if (e.getActive() == null) e.setActive(1);
        checkMultiplier(e.getMultiplier());
    }

    @Override
    protected void beforeUpdate(PriceSchedule e, PriceSchedule current) {
        if (e.getMultiplier() == null) e.setMultiplier(current.getMultiplier());
        if (e.getActive() == null) e.setActive(current.getActive());
        checkMultiplier(e.getMultiplier());
    }

    @Override
    protected PricingSchedule toVO(PriceSchedule e) {
        return new PricingSchedule(e.getRuleNo(), e.getName(), e.getPeriod(),
                e.getDays(), e.getTimeFrom(), e.getTimeTo(), e.getExpr(), e.getMultiplier(),
                e.getActive() != null && e.getActive() == 1);
    }

    /** 倍率非负且不离谱；**不**限制 ≤ 1。 */
    private static void checkMultiplier(BigDecimal m) {
        if (m == null || m.signum() < 0) {
            throw new IllegalArgumentException("multiplier 不能为负");
        }
        if (m.compareTo(MULTIPLIER_MAX) > 0) {
            throw new IllegalArgumentException("multiplier 超出上限 " + MULTIPLIER_MAX);
        }
    }
}
