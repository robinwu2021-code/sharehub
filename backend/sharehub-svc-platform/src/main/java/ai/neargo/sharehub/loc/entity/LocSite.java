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
    private String sceneType;
    private Integer pointCount;
    private Integer cabinetCount;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
    @TableLogic
    private Integer deleted;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;
}
