package ai.neargo.sharehub.agent.ext.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 代理设备/点位划拨记录（agt_assignment，[db-design §3.5]）。
 *
 * <p><b>append 表</b>：只插不改不删，DDL 无 {@code version}/{@code deleted}，
 * 故**不继承 BaseEntity**（[骨架规约 §4]）。
 *
 * <p>本表是**流水而非现状**：某柜机当前归谁，权威在 {@code dev_cabinet.agent_no} /
 * {@code loc_location.agent_no} 的冗余归属列（ADR-012，供 {@code AGENT} 数据范围直接过滤）；
 * 本表回答的是「谁在什么时候划给谁、又什么时候收回」。两者不可互相替代 ——
 * 用流水推现状要全表回放，用现状查历史则什么都查不到。
 */
@Data
@TableName("agt_assignment")
public class AgtAssignment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String assignNo;

    private String tenantId;

    private String agentNo;

    /** CABINET / LOCATION / SITE。 */
    private String targetType;

    /** 被划拨对象的业务键（cabinet_no / location_no / site_no）。 */
    private String targetNo;

    /** ASSIGN / REVOKE。 */
    private String action;

    /** 操作人 employee_no。 */
    private String operator;

    private String createdAt;

    /** 创建人（由 AuditMetaObjectHandler 自动填充；append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
