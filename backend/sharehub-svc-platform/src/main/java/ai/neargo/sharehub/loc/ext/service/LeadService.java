package ai.neargo.sharehub.loc.ext.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.Lead;
import ai.neargo.sharehub.loc.ext.entity.LocLead;

/**
 * BD 拓展 CRM 商机。
 *
 * <p>归类为**配置/记录类**而非聚合根：{@code stage} 没有单向状态机
 * （谈崩了退回 CONTACTED、复活重谈都是 BD 日常），没有跨表副作用，
 * 故继承通用 CRUD（[骨架规约 §3]）。
 */
public interface LeadService extends CrudService<LocLead, Lead> {
}
