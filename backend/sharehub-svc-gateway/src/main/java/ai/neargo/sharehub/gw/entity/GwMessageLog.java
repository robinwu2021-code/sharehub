package ai.neargo.sharehub.gw.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上下行报文留痕（gw_message_log，[db-design §四]）。append 表（月分区、短 TTL），
 * 无 {@code version}/{@code deleted}，故不继承 {@code BaseEntity}。
 *
 * <p>与 {@link GwCommandLog} 合成「设备日志」读模型（[db-design §3.1]）：
 * 本表出 {@code stream=REPORT/direction=UP} 的半边，指令表出 {@code COMMAND/DOWN} 的半边，
 * 按 {@code occurred_at} 排在同一时间轴 —— 竞品只有单向设备上报，
 * 双流合一后「下发了什么 → 设备回了什么」的因果在一屏内可读。
 *
 * <p>{@link #raw} 是脱敏后的原始报文（DDL 为 {@code VARBINARY(2048)}）；
 * {@link #parsedEvent} 是解析后的结构化事件 JSON。两者都留：解析器有 bug 时靠原文复盘。
 */
@Data
@TableName("gw_message_log")
public class GwMessageLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sn;

    private String cabinetNo;

    private String vendorCode;

    /** UP/DOWN。 */
    private String direction;

    /** 事件类型（心跳/借出上报/归还上报/故障…）。 */
    private String eventType;

    /** 原始报文（脱敏）。 */
    private String raw;

    /** 解析后事件，JSON 原文。 */
    private String parsedEvent;

    /** OK/TIMEOUT/FAILED。 */
    private String result;

    /** 发生时刻（与指令日志同轴排序的键）。 */
    private String occurredAt;

    private LocalDateTime createdAt;

    /** 创建人（由 AuditMetaObjectHandler 自动填充；append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
