package ai.neargo.sharehub.common.event.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事务性发件箱（ADR-019）。append 表，无 {@code version}/{@code deleted}，
 * 故不继承 {@code BaseEntity} —— 事件是既成事实，只允许改投递状态，内容不可改、不可删。
 *
 * <p><b>它是基础设施表</b>：每个服务都写自己的 outbox，不参与业务服务归属划分
 * （与 {@code sys_token} 同类，见 {@code module-graph.py} 的 {@code INFRA_TABLES}）。
 */
@Data
@TableName("sys_outbox")
public class SysOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventNo;
    private String tenantId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;

    /** 事件载荷 JSON。必须自带消费方所需全部字段。 */
    private String payload;

    /** PENDING / SENT / FAILED */
    private String status;

    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime sentAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
}
