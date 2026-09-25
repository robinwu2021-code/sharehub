package ai.neargo.sharehub.loc.ext.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.Lead;
import ai.neargo.sharehub.loc.ext.entity.LocLead;

/**
 * BD 拓展 CRM 商机。
 *
 * <p>属性保存继承通用 CRUD（[骨架规约 §3]）；阶段迁移在保存钩子里按
 * {@link ai.neargo.sharehub.loc.ext.LeadStateMachine} 校验（批次 B5：谈崩了退回 CONTACTED、LOST 后重新激活都是边）。
 */
public interface LeadService extends CrudService<LocLead, Lead> {

    /** 签下且归属伙伴、已有站点时写 DEVELOP 责任行（幂等）。签约转化后调用。 */
    void writeAttribution(String leadNo);
}
