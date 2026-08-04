package ai.neargo.sharehub.user.ad.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.user.ad.dto.AdDtos.AdCampaignVO;
import ai.neargo.sharehub.user.ad.entity.AdCampaign;
import ai.neargo.sharehub.user.ad.mapper.AdCampaignMapper;
import ai.neargo.sharehub.user.ad.service.AdCampaignService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** 广告活动实现。 */
@Service
public class AdCampaignServiceImpl extends AbstractCrudService<AdCampaign, AdCampaignVO> implements AdCampaignService {

    public AdCampaignServiceImpl(AdCampaignMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "ad_no";
    }

    @Override
    protected String keyOf(AdCampaign e) {
        return e.getAdNo();
    }

    @Override
    protected void setKey(AdCampaign e, String no) {
        e.setAdNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.AD_CAMPAIGN; // AD —— 营销活动是 CMP，两者不可混用
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"ad_no", "advertiser", "creative"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"advertiserNo", "status"};
    }

    @Override
    protected void beforeCreate(AdCampaign e) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("DRAFT");
        if (e.getBudget() == null) e.setBudget(BigDecimal.ZERO);
        if (e.getCurrency() == null || e.getCurrency().isBlank()) e.setCurrency("AED"); // ADR-009
    }

    @Override
    protected AdCampaignVO toVO(AdCampaign e) {
        return new AdCampaignVO(e.getAdNo(), e.getAdvertiserNo(), e.getAdvertiser(), e.getCreative(),
                e.getBudget(), e.getCurrency(), e.getTargeting(),
                e.getStatus(), e.getStartAt(), e.getEndAt());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Object transition(String adNo, String action) {
        AdCampaign e = selectByKey(adNo);
        if (e == null) throw new IllegalArgumentException("广告投放不存在: " + adNo);
        e.setStatus(ai.neargo.sharehub.user.marketing.CampaignStateMachine.next(e.getStatus(), action));
        mapper.updateById(e);
        return toVO(selectByKey(adNo));
    }
}
