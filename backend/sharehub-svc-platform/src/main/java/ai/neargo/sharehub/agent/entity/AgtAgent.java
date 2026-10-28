package ai.neargo.sharehub.agent.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 代理商实体（agt_agent，ADR-012）。镜像 Agent；主键/隔离键/审计列见 {@link BaseEntity}。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agt_agent")
public class AgtAgent extends BaseEntity implements ai.neargo.sharehub.common.crud.Archivable {
    private String agentNo;
    private String name;
    /**
     * retired（V123）：已拆成 {@link #contactName}/{@link #phone}/{@link #email}。
     * 形状不明的存量留在这里待人工归位，**不要再读写它**。
     */
    private String contact;

    /** 联系人姓名。 */
    private String contactName;
    /**
     * 联系手机 —— **业务联系方式，不是登录身份**。
     * 谁能登录代理端看 {@code agt_principal.phone_hash}；开通登录时从这里灌一次过去，
     * 此后两者各走各的（改这里不会改登录方式）。
     */
    private String phone;
    /** 联系邮箱，同上。 */
    private String email;

    /**
     * 位置（V157，商家核心资料，需求方案 D6）：地址 + 坐标三者同时写入。
     * 补齐后系统建默认站点；缺位置或行业时不能划拨设备。
     */
    private String address;
    private java.math.BigDecimal lng;
    private java.math.BigDecimal lat;
    /** 行业（V157）：与场地方同一词表 {@link ai.neargo.sharehub.loc.VenueIndustry}。 */
    private String industry;
    /** 默认站点（V157）：补齐位置时系统所建，商家端不出现「站点」这个词。 */
    private String defaultSiteNo;
    private String regionScope;

    /**
     * 登记类型：{@code AGENT} 代理商 / {@code CITY_PARTNER} 城市合伙人（ADR-027 §一）。
     *
     * <p>两者不是叫法之别：代理商出资 + 运维，城市合伙人还包拓展与效果管理，
     * <b>拿的钱性质不同</b>，分润说不清按什么分就是从这里开始的。
     *
     * <p>类型只定「他大体是哪种人」。「他在**这个站点**做了什么」由 A2 的
     * {@code loc_site_agent}（伙伴 × 站点 × 责任）表达 —— 两者都要，
     * 否则「这个人在 A 站是介绍人、在 B 站是代理商」无处安放。
     */
    private String agentType;
    /**
     * 档案上的默认分润比例 —— 列名是 V1 就定的 `default_share_rate`。
     *
     * <p><b>必须显式指定列名</b>：字段叫 `shareRate`，按驼峰推导会得到 `share_rate`，
     * 而那不是这张表的列。2026-09-23 曾据此加过一个 `share_rate` 影子列（V42），
     * 结果是新值写进影子列、真列 `default_share_rate` 恒为 0 —— 界面显示正常，
     * 而任何读真列的地方拿到的都是 0。V44 已删影子列。
     *
     * <p>⚠️ **分账不读这一列**，以 `share_rule` 为准（见 ShareGeneratorImpl）。
     */
    @com.baomidou.mybatisplus.annotation.TableField("default_share_rate")
    private Double shareRate;
    /*
     * 这里**没有** cabinetCount：同 LocVenue，机柜数是聚合值不是属性。
     * shareRate 保留 —— 它是档案上的真实配置（列 `default_share_rate`，V1 就有），
     * 但**分账以 share_rule 为准**，别拿这一列去算钱。
     */
    private String status;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;
}
