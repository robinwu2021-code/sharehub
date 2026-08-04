package ai.neargo.sharehub.platform.tenant.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.tenant.dto.TenantDtos.TenantConfigEntry;
import ai.neargo.sharehub.platform.tenant.dto.TenantDtos.TenantEntry;
import ai.neargo.sharehub.platform.tenant.entity.Tenant;
import ai.neargo.sharehub.platform.tenant.entity.TenantConfig;

import java.util.List;

/** 租户开通/配置（🔒 休眠口子，仅 {@code /internal} 调用）。 */
public interface TenantService extends CrudService<Tenant, TenantEntry> {

    /** 某租户的配置项列表；{@code category} 可空。 */
    List<TenantConfigEntry> configs(String tenantNo, String category);

    /** upsert 单个配置项（按 {@code tenant_no + category + config_key}）。 */
    TenantConfigEntry saveConfig(TenantConfig body);
}
