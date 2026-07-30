package ai.neargo.powerbank.loc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 场地方实体（loc_venue）。镜像 Dto.Venue + 审计列。 */
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
    private Integer locationCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
    @TableLogic
    private Integer deleted;
}
