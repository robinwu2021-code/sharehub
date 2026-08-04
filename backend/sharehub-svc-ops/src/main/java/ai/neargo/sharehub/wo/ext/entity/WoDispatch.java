package ai.neargo.sharehub.wo.ext.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 派单/接单记录（wo_dispatch，[db-design §3.6]）。
 *
 * <p><b>append 表</b>：只插不改不删，无 {@code version}/{@code deleted}，**不继承 BaseEntity**。
 * 一张工单被退回重派会产生多行 —— 这正是要它的原因：{@code wo_order.assignee_no} 只留最后一个人，
 * 「这单转了几手、每手多久没接」只能从本表看。
 */
@Data
@TableName("wo_dispatch")
public class WoDispatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String woNo;

    /**
     * 受理人 employee_no。
     * <p>[db-design §3.6] 写作 {@code assignee_no}，DDL 实际列名是 {@code assignee_id}
     * （v1 遗留，见交付报告的规格分歧清单）。此处按 db-design 命名 Java 字段、用
     * {@link TableField} 钉到实际列，两边都不用改。
     */
    @TableField("assignee_id")
    private String assigneeNo;

    /** NEAREST / LOAD / MANUAL / GRAB。 */
    private String strategy;

    /** 本行记录的动作：DISPATCH / ACCEPT / REJECT。 */
    private String action;

    private String dispatchedAt;

    /** 服务端落库时刻（与 dispatchedAt 的差值可诊断时钟偏移/链路延迟）。由 AuditMetaObjectHandler 填充。 */

    @TableField(fill = FieldFill.INSERT)

    private java.time.LocalDateTime createdAt;

    /** 创建人（append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
