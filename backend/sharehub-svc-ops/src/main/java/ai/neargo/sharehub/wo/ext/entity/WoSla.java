package ai.neargo.sharehub.wo.ext.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * SLA 计时（wo_sla，[db-design §3.6]）—— <b>逐单一行</b>，UK({@code wo_no})。
 *
 * <p>⚠️ <b>与 {@link WoSlaRule} 是两回事，别混</b>：
 * <ul>
 *   <li>本表 = <b>某一张工单</b>的计时实例：什么时候该响应、什么时候该解决、有没有超、有没有升级；</li>
 *   <li>{@link WoSlaRule} = <b>按工单类型</b>的规则配置：FAULT 类 30 分钟响应 / 4 小时解决。</li>
 * </ul>
 * 开单时读规则算出本表的两个 due 时刻并落行；此后规则改了**不回溯**已开的单
 * （否则历史单的达标率会随配置漂移，考核就没法算了）。
 *
 * <p>{@code respondDueAt} 的计时终点是 {@code wo_order} 进入 {@code ACCEPTED}
 * —— 这就是 [db-design §9A.4] 坚持保留 {@code ACCEPTED} 态的原因：删了它响应 SLA 没有终点。
 *
 * <p>DDL 无 {@code version}/{@code deleted}/{@code tenant_id}，故**不继承 BaseEntity**
 * （见交付报告的规格分歧清单）。
 */
@Data
@TableName("wo_sla")
public class WoSla {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String woNo;

    /** 应响应（= 接单）截止时刻。 */
    private String respondDueAt;

    /** 应解决（= 完工）截止时刻。 */
    private String resolveDueAt;

    /** 响应是否已超时（0/1）。 */
    private Integer respondBreached;

    /** 解决是否已超时（0/1）。 */
    private Integer resolveBreached;

    /** 升级时刻；空 = 尚未升级（[db-design §1.5] 可空时间戳语义）。 */
    private String escalatedAt;
}
