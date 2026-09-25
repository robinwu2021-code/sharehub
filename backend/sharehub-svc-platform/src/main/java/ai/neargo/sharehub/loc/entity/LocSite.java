package ai.neargo.sharehub.loc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 站点实体（loc_site，ADR-013）。字段镜像 Site + 审计列，map-underscore 映射 snake_case。 */
@Data
@TableName("loc_site")
public class LocSite {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String siteNo;
    private String tenantId;
    private String name;
    /**
     * 归属场地方编号。列在 V1 就有（`idx_site_venue`），实体此前漏映射 ——
     * 于是分成链路只能按 `venueName` 连表，而种子里就有同名站点/同名场地方，
     * 按名字连必然连错。分润生成一律按编号走。
     */
    private String venueNo;

    /**
     * 以哪个品牌对 C 端呈现（→ {@code md_brand.brand_no}）。
     *
     * <p><b>一站一品牌是硬约束</b>（2026-09-23 定）：分成、坪效、工单都按站点统计，
     * 一站两品牌会让「这笔钱算哪个品牌的」没有答案；ADR-028 的取价也把品牌当过滤条件，
     * 一台设备必须能解出唯一品牌。所以是一列，不是关系表。
     */
    private String brandNo;

    /**
     * 站点名（阿语）。列在 V1 就有，实体此前漏映射 —— 于是 `GET /api/ops/sites` 不返回它，
     * 而运营端编辑页有「站点名称（العربية）」这一格：**编辑已有站点时该格永远是空的**，
     * 保存就把库里的阿语名抹掉。三语（zh/en/ar）那条线上这类漏映射是静默的。
     */
    private String nameAr;
    /**
     * 营业时段展示文本（如 09:00-22:00）。列由 V31 为 C 端找柜补上，
     * 实体未映射 —— 运营端「营业时间」一格同样是空的，且保存会清掉 C 端在用的值。
     */
    private String openHours;

    private String venueName;
    private String agentNo;
    private String regionId;
    private String address;

    /**
     * 经纬度（DECIMAL(10,6)，V1 就有列，实体此前漏映射）。
     * 为空的后果不在运营端 —— 是 C 端「找附近的柜」算不出距离，整个找柜入口不可用。
     */
    private java.math.BigDecimal lng;

    private java.math.BigDecimal lat;
    /*
     * 这里**没有** pointCount / cabinetCount。
     *
     * 它们此前是实体字段，但**任何迁移都没建过这两列** —— 本机库里的是手工加的，
     * 干净库（生产）上一 SELECT 就报 Unknown column，站点列表整个 500。
     * 修法不是补列：计数是按关系聚合出来的（db-design §1.4「计数不是列，是聚合」），
     * 补成列之后必然与实际脱节 —— 运营端已经因为读这种计数列而出现过
     * 「列表说有 3 台机柜、待关注说一台都没有」。改为查询时现算，见 LocService#toSite。
     */
    private String sceneType;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
    @TableLogic
    private Integer deleted;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;

    /** 暂停营业原因（V40）。运营端要求必填 —— 此前无处可存，「为什么停」只能靠问人。 */
    private String pauseReason;

    private java.time.LocalDateTime pausedAt;

    // —— 2026-09-25 站点状态机（V96）——
    /** 平台员工运维责任人（代理运维责任人在 loc_site_agent.role=OPERATE，不在这里）。 */
    private String opsEmployeeNo;
    private java.time.LocalDateTime firstLiveAt;
    private java.time.LocalDate pauseUntil;
    private String withdrawReason;
    private java.time.LocalDate withdrawPlannedAt;
    private java.time.LocalDateTime withdrawStartedAt;
    private java.time.LocalDateTime closedAt;
}
