package ai.neargo.sharehub.platform.sys.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.SysParamEntry;
import ai.neargo.sharehub.platform.sys.entity.SysParam;

/**
 * 系统参数。键值型配置 → 继承通用 CRUD。
 *
 * <p>{@code paramKey} 是自然键，但 UK 是 {@code (tenant_id, param_key)}，
 * 按键查询在实现里带了 tenantId 条件。
 */
public interface SysParamService extends CrudService<SysParam, SysParamEntry> {
}
