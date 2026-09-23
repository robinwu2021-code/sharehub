package ai.neargo.sharehub.loc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 场地方实体（loc_venue）。镜像 Venue + 审计列。 */
@Data
@TableName("loc_venue")
public class LocVenue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String venueNo;
    private String tenantId;
    private String name;
    private String contact;
    private String industry;
    /*
     * 这里**没有** locationCount：它是「这个场地方名下有几个站点」的聚合值，
     * 不是场地方的属性。存成列的后果见 V39 的说明（列表说有 3 个、点进去一个都没有），
     * 而且这一列在干净库里根本不存在 —— 2026-09-23 灌演示数据时它让服务没起来。
     * 改为查询时现算，见 LocService#venueSiteCounts。
     */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
    @TableLogic
    private Integer deleted;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;
}
