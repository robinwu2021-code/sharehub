package ai.neargo.sharehub.loc.ext.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * BD 拓展 CRM 商机（loc_lead，[db-design §3.4]）。
 *
 * <p>阶段走 {@link ai.neargo.sharehub.loc.ext.LeadStateMachine}（批次 B5，2026-09-25）；
 * 仍走通用 CRUD 保存属性，阶段迁移在 beforeUpdate / 跟进里校验。业务键前缀 {@code LD}（[db-design §1.4.1]）。
 *
 * <p>{@code contact} 是**掩码值**，明文落 {@code pb_pii}（ADR-009），本表不存明文。
 * {@code nextFollowAt} 是仅日期语义（DDL 为 {@code DATE}），故用 String 存 {@code yyyy-MM-dd}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("loc_lead")
public class LocLead extends BaseEntity {

    private String leadNo;

    private String regionId;

    private String venueName;

    /** 联系方式（掩码；明文落 pb_pii）。 */
    private String contact;

    /** NEW / CONTACTED / NEGOTIATING / SIGNED / LOST。 */
    private String stage;

    /**
     * 归属方业务号：{@code ownerType=STAFF} 时是 employee_no，{@code AGENT} 时是 agent_no。
     *
     * <p>一列存号、一列存类型。没有 ownerType 时这一列是有二义的 ——
     * 而二义的后果是拓展佣金算给错的人，且不报错。
     */
    private String owner;

    /** STAFF（自己人）/ AGENT（伙伴谈下来的，拓展佣金的依据）。见 V55。 */
    private String ownerType;

    /**
     * 这条商机最终落成的站点。
     *
     * <p>拓展归因只有落到站点上才能变成钱：责任行挂在「伙伴 × 站点」上，
     * 而商机谈的是场地、站点是之后才建的。签下并指定站点后才写 {@code DEVELOP} 责任行。
     */
    private String siteNo;

    /** 预计可铺站点数。 */
    private Integer expectSites;

    /** 下次跟进日 {@code yyyy-MM-dd}（DDL DATE）。 */
    private String nextFollowAt;

    // —— 批次 B（V104）——
    /** 场地地址：与场地名一起做 90 天查重。 */
    private String address;
    /** 签约转化生成 / 关联的场地方（也可在转化前手工关联已有场地方）。 */
    private String venueNo;
    /** 签约转化生成的合同草稿 —— 有值即已转化，不可重复转化。服务端写，编辑锁死。 */
    private String contractNo;
    /** 丢单原因：迁到 LOST 必填。 */
    private String lostReason;
    private java.time.LocalDateTime lostAt;
    /** 最近一次跟进；提醒与回收按它计。服务端写。 */
    private java.time.LocalDateTime lastFollowAt;
    private java.time.LocalDateTime remindAt;
    /** 1 = 在公共线索池（无负责人），只能经认领拿走。服务端写。 */
    private Integer inPool;
    private java.time.LocalDateTime pooledAt;
    private String prevOwner;
    private String competitorName;
    /** 竞品独家到期日：到期前 N 天自动重新激活（仅 LOST）。 */
    private java.time.LocalDate competitorExclusiveUntil;
    private java.time.LocalDateTime reactivatedAt;
    // 谈判条款：签约转化时带进合同草稿，不用重录
    private String shareMode;
    private java.math.BigDecimal shareRate;
    private java.math.BigDecimal entryFee;
    private java.math.BigDecimal guaranteeAmount;
    private Integer termMonths;
    private Boolean exclusiveFlag;
}
