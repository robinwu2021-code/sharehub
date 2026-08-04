package ai.neargo.sharehub.trade.price.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PricingDiff;
import ai.neargo.sharehub.trade.price.entity.PriceRule;
import ai.neargo.sharehub.trade.price.mapper.PriceRuleMapper;
import ai.neargo.sharehub.trade.price.service.PricingDiffService;
import org.springframework.stereotype.Service;

/** 差异化定价实现。全部行为来自基类，本类只声明键/搜索/筛选/VO 映射。 */
@Service
public class PricingDiffServiceImpl extends AbstractCrudService<PriceRule, PricingDiff> implements PricingDiffService {

    public PricingDiffServiceImpl(PriceRuleMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "rule_no";
    }

    @Override
    protected String keyOf(PriceRule e) {
        return e.getRuleNo();
    }

    @Override
    protected void setKey(PriceRule e, String no) {
        e.setRuleNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.PRICING_DIFF;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"rule_no", "location_name", "scene_type", "match_ref"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"planNo", "dimension", "siteNo", "sceneType", "currency"};
    }

    @Override
    protected void beforeCreate(PriceRule e) {
        if (e.getDimension() == null || e.getDimension().isBlank()) e.setDimension("SITE");
        if (e.getPriority() == null) e.setPriority(1);
        if (e.getCurrency() == null || e.getCurrency().isBlank()) e.setCurrency("AED");
    }

    @Override
    protected PricingDiff toVO(PriceRule e) {
        return new PricingDiff(e.getRuleNo(), e.getSceneType(), e.getLocationName(), e.getFreeMins(),
                e.getUnitPrice(), e.getDayCap(), e.getPriority(), e.getCurrency(), e.getSiteNo(),
                e.getDimension(), e.getMatchRef());
    }
}
