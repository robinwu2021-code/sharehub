package ai.neargo.sharehub.loc.ext.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 站点上的伙伴责任（loc_site_agent，ADR-027 §三）。
 *
 * <p><b>一行一责任</b>。责任不存成数组列 —— ai-shop 的教训：数组列上撤销单个角色
 * 变成读-改-写，两人同时改会互相覆盖，而覆盖的后果是<b>有人多分了钱且不报错</b>。
 * 多行方案里撤销就是删一行。
 *
 * <p><b>与 {@code loc_site.agent_no} 的关系</b>：那一列保留，语义收窄为「主经营方」
 * = 承担 {@code OPERATE} 的那个伙伴。它仍是数据范围与工单派单的锚点，
 * 本表不取代它、也不要求与它一致（只有 REFER 的站点 {@code agent_no} 为空 = 直营运维）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("loc_site_agent")
public class LocSiteAgent extends BaseEntity {

    // ⚠️ 本表几个可空列都标了 updateStrategy = ALWAYS。原因：MyBatis-Plus 的 updateById
    // **只写非 null 字段** —— 默认策略下「把生效期清空」「把牵线费清空」这类操作
    // 会被静默丢掉，保存成功、值没变、不报错。牵线费那一处的后果是**继续按旧金额付钱**。
    // 实测于 A2-4：撤销后复活的责任行带着上一次的 500 不放，怎么改都清不掉。

    private String siteNo;

    private String agentNo;

    /** 见 {@link ai.neargo.sharehub.loc.ext.SiteAgentRole}。 */
    private String role;

    /** 该责任对应的分润规则；空 = 用登记类型的默认费率。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String ruleNo;

    /**
     * 一次性对价（牵线费），签约时付；仅 {@code REFER} 用（V56）。
     *
     * <p><b>null = 没配，0 = 明确不付</b>。用比例表达一次性费用就得再找一个基数去乘，
     * 而那个基数无论选 GMV 还是进场费都是编出来的 —— 日后没人说得清这笔钱怎么来的。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private java.math.BigDecimal oneOffAmount;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime effectiveFrom;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime effectiveTo;

    /** 为什么是这个责任 —— 结算争议时的人话依据。 */
    private String remark;
}
