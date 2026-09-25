package ai.neargo.sharehub.user.marketing.service.impl;

import ai.neargo.sharehub.common.BizException;
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


    /**
     * <b>状态不接受客户端传入</b> —— 只能由 {@code POST /api/user/campaigns/{campaignNo}/{action}}（start/pause/end） 走状态机改。
     *
     * 实体就是请求体（见 {@code known-entity-request-bodies.txt}），而 MyBatis-Plus 的
     * {@code updateById} 只写非 null 字段 —— 不锁的话保存端点就是绕过状态机的第二条路：
     * {@code POST /api/user/campaigns/{campaignNo}  {"status":"ENDED"}}
     * 一条边都不用走，直接落终态。运营端的迁移表、两端的边卡口、mock 守卫，
     * 守的全是动作端点那条路；<b>卡口守住一条路、另一条敞着，等于没守</b>。
     *
     * 写法同 {@code FreeWhitelistServiceImpl.save}（那里早就这么锁了）。
     */
    @Override
    protected void beforeUpdate(MktCampaign e, MktCampaign current) {
        e.setStatus(current.getStatus());
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
        if (e == null) throw BizException.notFound(campaignNo);
        // 状态机与前端 CAMPAIGN_TRANSITIONS 同源：页面藏按钮不等于服务端会拒绝。
        e.setStatus(ai.neargo.sharehub.user.marketing.CampaignStateMachine.next(e.getStatus(), action));
        mapper.updateById(e);
        return toVO(selectByKey(campaignNo));
    }
}
