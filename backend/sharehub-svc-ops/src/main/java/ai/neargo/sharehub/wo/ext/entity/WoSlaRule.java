package ai.neargo.sharehub.wo.ext.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * SLA 规则配置（wo_sla_rule，[db-design §3.6]）—— <b>按工单类型一条</b>，
 * UK({@code tenant_id}, {@code wo_type})。
 *
 * <p>⚠️ 与逐单计时的 {@link WoSla} 是两张表、两个概念，见 {@link WoSla} 类注释。
 *
 * <p>{@link #woType} 收敛为 {@link ai.neargo.sharehub.wo.ext.WorkOrderType} 的枚举值，
 * 由 service 层校验 —— UK 上的拼写错误不会报错，只会生成一条永远不生效的规则。
 *
 * <p>业务键前缀 {@code SLA}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wo_sla_rule")
public class WoSlaRule extends BaseEntity {

    private String slaNo;

    /** FAULT / REFILL / INSPECT / INSTALL / REMOVE / COMPLAINT / CLEAN。 */
    private String woType;

    /** 响应时限（分钟）：派单 → 接单。 */
    private Integer responseMins;

    /** 解决时限（分钟）：派单 → 完工。 */
    private Integer resolveMins;

    /** 超时升级到（role_no / employee_no）。 */
    private String escalateTo;

    /** 是否启用（0/1）。 */
    private Integer active;
}
