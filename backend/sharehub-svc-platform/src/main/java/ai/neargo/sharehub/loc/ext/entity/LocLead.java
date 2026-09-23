package ai.neargo.sharehub.loc.ext.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * BD 拓展 CRM 商机（loc_lead，[db-design §3.4]）。
 *
 * <p>纯跟进型 CRM 记录，无状态机约束（{@code stage} 可前可后，BD 现实里会反复），
 * 故走通用 CRUD 而非手写聚合根。业务键前缀 {@code LD}（[db-design §1.4.1]）。
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
}
