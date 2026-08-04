package ai.neargo.sharehub.trade.price.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PricingSchedule;
import ai.neargo.sharehub.trade.price.entity.PriceSchedule;

/**
 * 活动 / 时段价（price_schedule）。配置类实体 → 继承通用 CRUD。
 *
 * <p>{@code multiplier} 是倍率（可 &gt; 1），保存时只校验「非负」，**不**按 0..1 卡。
 */
public interface PricingScheduleService extends CrudService<PriceSchedule, PricingSchedule> {
}
