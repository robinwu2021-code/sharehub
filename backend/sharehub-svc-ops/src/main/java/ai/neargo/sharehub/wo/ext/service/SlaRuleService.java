package ai.neargo.sharehub.wo.ext.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.SlaRule;
import ai.neargo.sharehub.wo.ext.entity.WoSlaRule;

/**
 * SLA <b>规则配置</b>（{@code wo_sla_rule}）—— 配置类，走通用 CRUD。
 *
 * <p>⚠️ 不要与逐单计时的 {@code wo_sla} 混为一谈，见
 * {@link ai.neargo.sharehub.wo.ext.entity.WoSla} 类注释。本接口不产生也不修改任何计时行。
 */
public interface SlaRuleService extends CrudService<WoSlaRule, SlaRule> {
}
