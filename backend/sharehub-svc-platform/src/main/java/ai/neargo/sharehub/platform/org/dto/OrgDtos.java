package ai.neargo.sharehub.platform.org.dto;

import java.math.BigDecimal;

/**
 * platform/org 子域出参 VO 与入参体。
 *
 * <p>字段镜像 ops-web {@code lib/types/org.ts}（{@code Employee}/{@code Department}/
 * {@code StaffPerformance}/{@code AuditEntry}）。按 [骨架规约 §3] 用域内 dto 文件，
 * 不往已冻结的顶层 {@code dto/Dto.java} 追加。
 */
public final class OrgDtos {

    private OrgDtos() {
    }

    /**
     * 员工行，镜像前端 {@code Employee}。
     *
     * <p>{@code deptName}/{@code roleName} 是**关联表取名后的展示值**（前端不认 no），
     * {@code phone} 出参即掩码（[api/README §1.6]）。
     */
    public record Employee(String employeeNo, String name, String phone, String email,
                           String deptName, String roleName, String status) {
    }

    /**
     * 部门行，镜像前端 {@code Department}。
     *
     * <p>{@code memberCount} 由 {@code iam_employee} 实时聚合，**不是表里的列**
     * （[db-design §1.4]「计数列不是列」）；{@code parent} 出的是上级部门**名称**，非 {@code deptNo}。
     */
    public record Department(String deptNo, String name, String parent, long memberCount,
                             String leader, String path, Integer sort, String status) {
    }

    /** 员工绩效行，镜像前端 {@code StaffPerformance}。 */
    public record StaffPerformance(String employeeNo, String name, String role, String period,
                                   Integer handled, Integer avgResolveMins, BigDecimal score) {
    }

    /**
     * 审计行，镜像前端 {@code AuditEntry}。
     *
     * <p>前端只有单个 {@code target}，v2 表拆成了 {@code targetType}+{@code targetNo}：
     * 两者都出，{@code target} 是拼好的展示值，保证前端不改也能渲染。
     */
    public record AuditLogEntry(String id, String actor, String actorName, String action,
                                String targetType, String targetNo, String target,
                                String detail, String ip, String createdAt) {
    }

    /**
     * 单字段改动，镜像前端 {@code AuditFieldChange}。
     *
     * <p>新增记录的 {@code before} / 删除记录的 {@code after} 约定填 {@code "—"}（破折号）——
     * **不能用空串**：空串是「被清空成空值」的真实业务语义，两者混淆会让审计读者误判。
     */
    public record AuditFieldChange(String field, String before, String after) {
    }

    /**
     * 审计详情，镜像前端 {@code AuditDetail}（= {@code AuditEntry} + requestId/userAgent/changes）。
     *
     * <p><b>changes 为什么不进列表</b>：一页 10 行 × 每行几十字段的 diff 会把列表响应撑爆，
     * 且列表里也展示不了 —— 点开才拉详情（与前端注释同源）。
     *
     * <p><b>诚实性声明（重要）</b>：{@code iam_audit_log} 现有 DDL **没有** {@code request_id}
     * 与 {@code user_agent} 列，也**没有**任何字段级前后值列；且全仓当前无一处调用
     * {@code AuditLogService.append}（审计表实际为空，列表数据来自内存种子 {@code SeedData}）。
     * 因此这三项一律**如实出空**（空串 / 空数组），<b>不构造假 diff</b>：
     * 假的审计比没有审计更危险。补列方案见交付报告的 DDL 清单。
     */
    public record AuditDetail(String id, String actor, String actorName, String action,
                              String targetType, String targetNo, String target,
                              String detail, String ip, String createdAt,
                              String requestId, String userAgent, java.util.List<AuditFieldChange> changes) {
    }

    /** 数据权限（iam_data_scope）出参；{@code scopeRefs} 为逗号/JSON 数组文本。 */
    public record DataScopeEntry(String subjectType, String subjectNo, String scopeType, String scopeRefs) {
    }

    /** 角色行，镜像前端 {@code RoleRow}（org.ts）。计数列是聚合不是列（db-design §1.4）。 */
    public record RoleRowVO(String roleNo, String code, String name, Long permCount,
                            Long memberCount, Boolean builtin, String dataScope,
                            String scopeRefs, String archivedAt) {
    }

    /** 数据权限保存入参（{@code PUT /api/platform/data-scopes/{subjectType}/{subjectNo}}，补 G7 缺口）。 */
    public record DataScopeReq(String scopeType, String scopeRefs) {
    }
}
