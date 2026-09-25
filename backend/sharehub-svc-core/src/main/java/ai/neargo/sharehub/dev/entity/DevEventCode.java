package ai.neargo.sharehub.dev.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 设备信号字典（dev_event_code，V97，全局表：无 tenant_id，故不继承 BaseEntity）。 */
@Data
@TableName("dev_event_code")
public class DevEventCode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String nameEn;
    private String nameAr;
    private String category;
    private String scope;
    private String protectiveAction;
    private String clearsCode;
    private String feeds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
    @TableLogic
    private Integer deleted;
}
