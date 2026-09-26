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
                           /**
                            * 部门**编号**与**显示名**两个都给，口径同 roleNo / roleName：
                            * 列表显示用后者，编辑抽屉的下拉要用前者预选。
                            * 此前只给 deptName，于是运营端的「部门」只能做成自由文本框，
                            * 填进去的名字后端不认（写入面要 deptNo），被静默丢弃。
                            */
                           String deptNo, String deptName, String roleNo, String roleName,
                           /** 该员工的全部角色（iam_employee_role）。主角色 roleNo 只是其中显示用的那个。 */
                           java.util.List<String> roleNos,
                           String status) {
    }

    /**
     * 员工保存入参。**不再直接收实体** —— 实体当请求体时客户端传哪些字段就能改哪些字段
     * （见 known-entity-request-bodies.txt 头部）。
     *
     * <p>{@code roleNos} 的语义分三种，别混：
     * <ul>
     *   <li><b>null = 不动角色</b>。改个电话号码不该把这个人的角色清掉 ——
     *       此前的 syncPrimaryRole 是「删光再插一条」，多角色会被静默抹掉；</li>
     *   <li>非空列表 = 这就是他的全部角色，覆盖写；</li>
     *   <li>空列表 = 清掉附加角色；**主角色摘不掉**（它总会被并回来）——
     *       「页面显示 OPS、而授权表里没有 OPS」是最难查的那种不一致。
     *       要真正停掉一个人，改他的状态而不是清这张表。</li>
     * </ul>
     */
    public record EmployeeSaveReq(String employeeNo, String name, String phone, String email,
                                  String deptNo, String roleNo, java.util.List<String> roleNos,
                                  String status) {
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
    public record AuditLogEntry(String id, String actor, String actorName, String clientCode, String action,
                                String outcome, String traceId,
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
    public record AuditDetail(String id, String actor, String actorName, String clientCode, String action,
                              String outcome, String traceId,
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
