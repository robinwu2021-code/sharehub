package ai.neargo.sharehub.wo.ext.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 现场处理留痕（wo_handle，[db-design §3.6]）。
 *
 * <p><b>append 表</b>，**不继承 BaseEntity**。一次工单可多次提交处理进展
 * （前端 {@code WO_TRANSITIONS.process} 是 {@code PROCESSING→PROCESSING} 自环，
 * 只留痕不改状态），故一单多行是常态。
 *
 * <p>{@code partChanged}/{@code deviceChanged} 是 {@code TINYINT(1)}，
 * 按 [骨架规约 §4] 布尔列用 {@link Integer}。它们不是装饰性字段 ——
 * 换件/换设备类工单要求「完工人 ≠ 验收人」（[db-design §9A.4]），验收环节据此判定是否必须复核。
 */
@Data
@TableName("wo_handle")
public class WoHandle {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String woNo;

    /** 处理人 employee_no。DDL 实际列名 {@code assignee_id}，见 {@link WoDispatch#getAssigneeNo()} 的说明。 */
    @TableField("assignee_id")
    private String assigneeNo;

    /** 现场照片 URL 数组的 JSON 原文。 */
    private String photos;

    private String note;

    /** 是否换件（0/1）。 */
    private Integer partChanged;

    /** 是否换设备（0/1）。 */
    private Integer deviceChanged;

    private String handledAt;

    /** 服务端落库时刻（与 handledAt 的差值可诊断时钟偏移/链路延迟）。由 AuditMetaObjectHandler 填充。 */

    @TableField(fill = FieldFill.INSERT)

    private java.time.LocalDateTime createdAt;

    /** 创建人（append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
