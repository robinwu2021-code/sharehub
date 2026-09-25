package ai.neargo.sharehub.loc.ext.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 合同状态迁移日志（loc_contract_log，追加型）。审批记录页签的唯一数据源。 */
@Data
@TableName("loc_contract_log")
public class LocContractLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String contractNo;
    private String event;
    private String fromStatus;
    private String toStatus;
    private String operator;
    private String note;
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
}
