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

    /** 负责人 employee_no。 */
    private String owner;

    /** 预计可铺站点数。 */
    private Integer expectSites;

    /** 下次跟进日 {@code yyyy-MM-dd}（DDL DATE）。 */
    private String nextFollowAt;
}
