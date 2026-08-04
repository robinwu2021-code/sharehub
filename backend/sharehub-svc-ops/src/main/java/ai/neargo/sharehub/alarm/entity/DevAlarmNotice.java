package ai.neargo.sharehub.alarm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警通知流水（dev_alarm_notice，[db-design §3.2]）。
 *
 * <p><b>append 表，故不继承 {@code BaseEntity}</b>：DDL 里没有 {@code version}/{@code deleted}
 * ——通知发出去就是既成事实，既不该被乐观锁改写，也不该被软删。
 * 只自带自增主键 + {@code created_at}。
 */
@Data
@TableName("dev_alarm_notice")
public class DevAlarmNotice {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务键 AN*。 */
    private String noticeNo;

    private String tenantId;

    /** 所属告警 → {@link DevAlarm#getAlarmNo()}。 */
    private String alarmNo;

    /** SMS/EMAIL/PUSH/WEBHOOK。 */
    private String channel;

    /** 接收方（存储即脱敏）。 */
    private String target;

    /** 发送时刻；空 = 尚未发生。 */
    private String sentAt;

    /** SENT/FAILED。 */
    private String status;

    private String failReason;

    /** 触发幂等键（V31）：重发是真发真扣钱，双击不该发两条。 */
    private String idempotencyKey;

    /** 重发来源 notice_no（V31）；NULL=首发。 */
    private String resendOf;

    private LocalDateTime createdAt;

    /** 创建人（由 AuditMetaObjectHandler 自动填充；append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
