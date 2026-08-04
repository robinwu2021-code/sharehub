package ai.neargo.sharehub.platform.sys.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.TaxSetting;
import ai.neargo.sharehub.platform.sys.entity.SysTaxSetting;

/**
 * 税率与发票设置。按国家的配置行 → 继承通用 CRUD。
 *
 * <p>{@code country} 是自然键，UK 为 {@code (tenant_id, country)}；{@code '*'} 是默认行。
 */
public interface TaxSettingService extends CrudService<SysTaxSetting, TaxSetting> {
}
