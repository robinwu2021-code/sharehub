package ai.neargo.sharehub.user.ad.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.user.ad.dto.AdDtos.AdCampaignVO;
import ai.neargo.sharehub.user.ad.entity.AdCampaign;

/**
 * 广告活动（ad_campaign）。P2 能力，首期只建表与 CRUD、不接分润
 * （未来纳入 {@code share_record.biz_type=AD}）→ 归配置类，继承通用 CRUD。
 */
public interface AdCampaignService extends CrudService<AdCampaign, AdCampaignVO> {

    /** 广告投放状态迁移，语义同活动。 */
    Object transition(String adNo, String action);
}
