package ai.neargo.sharehub.loc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 点位实体（loc_location）。镜像 Location + 审计列。 */
@Data
@TableName("loc_location")
public class LocLocation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String locationNo;
    private String tenantId;
    private String name;
    private String siteNo;
    /** 归属代理（冗余·随站点级联，数据范围锚点；空=平台直营）。 */
    private String agentNo;
    private String siteName;
    private String spotDesc;
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
