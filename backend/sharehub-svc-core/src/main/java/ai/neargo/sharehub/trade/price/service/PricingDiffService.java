package ai.neargo.sharehub.trade.price.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PricingDiff;
import ai.neargo.sharehub.trade.price.entity.PriceRule;

/** 差异化定价（price_rule）。配置类实体 → 继承通用 CRUD。 */
public interface PricingDiffService extends CrudService<PriceRule, PricingDiff> {
}
