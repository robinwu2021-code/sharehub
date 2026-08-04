package ai.neargo.sharehub.user.marketing.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.CampaignVO;
import ai.neargo.sharehub.user.marketing.entity.MktCampaign;
import ai.neargo.sharehub.user.marketing.mapper.MktCampaignMapper;
import ai.neargo.sharehub.user.marketing.service.CampaignService;
import org.springframework.stereotype.Service;

/** 营销活动实现。 */
@Service
public class CampaignServiceImpl extends AbstractCrudService<MktCampaign, CampaignVO> implements CampaignService {

    public CampaignServiceImpl(MktCampaignMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "campaign_no";
    }

    @Override
    protected String keyOf(MktCampaign e) {
        return e.getCampaignNo();
    }

    @Override
    protected void setKey(MktCampaign e, String no) {
        e.setCampaignNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.CAMPAIGN; // CMP —— 注意不是广告活动的 AD
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"campaign_no", "name"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"kind", "status"};
    }

    @Override
    protected void beforeCreate(MktCampaign e) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("DRAFT");
    }

    @Override
    protected CampaignVO toVO(MktCampaign e) {
        return new CampaignVO(e.getCampaignNo(), e.getName(), e.getKind(), e.getRule(),
                e.getStatus(), e.getStartAt(), e.getEndAt());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Object transition(String campaignNo, String action) {
        MktCampaign e = selectByKey(campaignNo);
        if (e == null) throw new IllegalArgumentException("活动不存在: " + campaignNo);
        // 状态机与前端 CAMPAIGN_TRANSITIONS 同源：页面藏按钮不等于服务端会拒绝。
        e.setStatus(ai.neargo.sharehub.user.marketing.CampaignStateMachine.next(e.getStatus(), action));
        mapper.updateById(e);
        return toVO(selectByKey(campaignNo));
    }
}
