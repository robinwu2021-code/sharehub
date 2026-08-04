package ai.neargo.sharehub.platform.md.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.MarketCountry;
import ai.neargo.sharehub.platform.md.entity.MdMarketCountry;

/** 多国家市场。纯主数据读写 → 继承通用 CRUD；{@code country_code} 是自然键。 */
public interface MarketService extends CrudService<MdMarketCountry, MarketCountry> {
}
