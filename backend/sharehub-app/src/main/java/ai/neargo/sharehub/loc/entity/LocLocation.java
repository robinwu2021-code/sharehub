package ai.neargo.powerbank.loc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 点位实体（loc_location）。镜像 Dto.Location + 审计列。 */
@Data
@TableName("loc_location")
public class LocLocation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String locationNo;
    private String tenantId;
    private String name;
    private String siteNo;
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
}
