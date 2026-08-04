package ai.neargo.sharehub.user.marketing.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.CampaignVO;
import ai.neargo.sharehub.user.marketing.entity.MktCampaign;

/**
 * 营销活动（mkt_campaign）。规则本身是 JSON 配置、状态只有 DRAFT/RUNNING/ENDED 且无复杂前置校验
 * → 归「配置类」，继承通用 CRUD。真正的权益计算发生在下单链路，不在本服务。
 */
public interface CampaignService extends CrudService<MktCampaign, CampaignVO> {

    /** 状态迁移（start/pause/end）。合法迁移由 {@code CampaignStateMachine} 判定，与前端同一张表。 */
    Object transition(String campaignNo, String action);
}
