package ai.neargo.sharehub.user.ad.service.impl;

import ai.neargo.sharehub.common.BizException;
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


    /**
     * <b>状态不接受客户端传入</b> —— 只能由 {@code POST /api/user/ad-campaigns/{adNo}/{action}}（launch/pause/stop） 走状态机改。
     *
     * 实体就是请求体（见 {@code known-entity-request-bodies.txt}），而 MyBatis-Plus 的
     * {@code updateById} 只写非 null 字段 —— 不锁的话保存端点就是绕过状态机的第二条路：
     * {@code POST /api/user/ad-campaigns/{adNo}  {"status":"ENDED"}}
     * 一条边都不用走，直接落终态。运营端的迁移表、两端的边卡口、mock 守卫，
     * 守的全是动作端点那条路；<b>卡口守住一条路、另一条敞着，等于没守</b>。
     *
     * 写法同 {@code FreeWhitelistServiceImpl.save}（那里早就这么锁了）。
     */
    @Override
    protected void beforeUpdate(AdCampaign e, AdCampaign current) {
        e.setStatus(current.getStatus());
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
        if (e == null) throw BizException.notFound(adNo);
        e.setStatus(ai.neargo.sharehub.user.marketing.CampaignStateMachine.next(e.getStatus(), action));
        mapper.updateById(e);
        return toVO(selectByKey(adNo));
    }
}
