package ai.neargo.sharehub.user.ad.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.user.ad.dto.AdDtos.AdSlotVO;
import ai.neargo.sharehub.user.ad.entity.AdSlot;

/** 广告位登记（ad_slot）。纯资源台账，无业务规则 → 继承通用 CRUD。 */
public interface AdSlotService extends CrudService<AdSlot, AdSlotVO> {
}
