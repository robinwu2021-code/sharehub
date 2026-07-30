package ai.neargo.powerbank.loc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 站点实体（loc_site，ADR-013）。字段镜像 Dto.Site + 审计列，map-underscore 映射 snake_case。 */
@Data
@TableName("loc_site")
public class LocSite {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String siteNo;
    private String tenantId;
    private String name;
    private String venueName;
    private String agentNo;
    private String regionId;
    private String address;
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
}
