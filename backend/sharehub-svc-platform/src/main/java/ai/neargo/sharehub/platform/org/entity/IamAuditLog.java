package ai.neargo.sharehub.platform.org.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计（iam_audit_log）—— **WORM append 表**（Write Once Read Many），[db-design §2.2]。
 *
 * <p><b>为什么不继承 BaseEntity</b>：审计行一旦落库就不可改、不可删，
 * 因此 DDL 里没有 {@code version}（无并发改）也没有 {@code deleted}（无软删）。
 * 对应地 {@code AuditLogService} 只提供 {@code append} 与 {@code page}，
 * <b>不提供 update/remove</b> —— 能改的审计等于没有审计。
 *
 * <p><b>与现有 DDL 的差异</b>：现有 {@code pb_core-loc-agt-iam.sql} 只有单列 {@code target}，
 * db-design v2 要求拆为 {@code target_type} + {@code target_no}（对象类型可检索、可按类型统计）。
 * 本实体按 v2 建，**需要一次 ALTER**（见交付报告）。
 */
@Data
@TableName("iam_audit_log")
public class IamAuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    /** 操作人 employee_no（系统动作为 SYSTEM）。 */
    private String actor;
    /** 操作人姓名快照。 */
    private String actorName;

    /** 从哪个端发起（OPS/AGENT/MP）。由会话 realm 派生，**不采信请求头**——见 ClientCode。
        历史行为 NULL：该列上线前没有这个信息，填任何值都是编造。 */
    private String clientCode;
    /** 权限码/动作，如 {@code org:employee:create}。 */
    private String action;
    /** 对象类型，如 EMPLOYEE / ROLE / CABINET。 */
    private String targetType;
    /** 对象业务键。 */
    private String targetNo;
    /** 脱敏摘要（JSON 文本）。 */
    private String detail;
    private String ip;
    private LocalDateTime createdAt;
    /** 创建人（由 AuditMetaObjectHandler 自动填充；append 表无更新语义，故不设 updatedBy）。 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
}
