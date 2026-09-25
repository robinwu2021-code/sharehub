package ai.neargo.sharehub.loc.ext.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 站点状态迁移日志（loc_site_status_log，追加型）。取代 loc_site_lifecycle_log 的写入。 */
@Data
@TableName("loc_site_status_log")
public class LocSiteStatusLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String siteNo;
    private String event;
    private String fromStatus;
    private String toStatus;
    private String operator;
    private String reason;
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
}
