// 覆盖范围：组织与权限 —— 员工、角色、审计日志、部门、员工绩效。
// 端点前缀：/api/platform/**
import { client } from "../http-client";
import type { OrgApi } from "../contracts/org";
import type { PageQ, ArchiveQ , ReportQ } from "../query";

/** 数据范围主体类型：运营端只配角色维度；员工维度（EMPLOYEE）后端支持但暂无入口。 */
const ROLE_SUBJECT = "ROLE";

export const orgHttp: OrgApi = {
  listEmployees: (q?: PageQ) => client.get("/api/platform/employees", q),
  listRoles: (q?: ArchiveQ) => client.get("/api/platform/roles", q),
  listAudits: (q?: PageQ) => client.get("/api/platform/audit-logs", q),

  // 员工扩展
  listDepartments: (q?: PageQ) => client.get("/api/platform/departments", q),
  listStaffPerformance: (q?: ReportQ) => client.get("/api/platform/staff-performance", q),
  saveDepartment: (x) => client.post(x.deptNo ? `/api/platform/departments/${x.deptNo}` : "/api/platform/departments", x),
  saveRoleRow: (x) => client.post(x.roleNo ? `/api/platform/roles/${x.roleNo}` : "/api/platform/roles", x),
  saveEmployee: (x) => client.post(x.employeeNo ? `/api/platform/employees/${x.employeeNo}` : "/api/platform/employees", x),
  // 覆盖写（PUT），对齐《权限体系设计》§9「数据范围配置」。
  // T0-4：真实端点是 OrgController 的 PUT /api/platform/data-scopes/{subjectType}/{subjectNo}
  // （不是 iam/roles/{code}/data-scope —— IamAdminController 只管 roles/{no}/permissions）。
  // subjectType 由调用方给（后端 SUBJECT_TYPES = ROLE|EMPLOYEE）；
  // body 字段名是 scopeType/scopeRefs（后端 DataScopeReq），不是 scope/scopeRefs。
  // subjectType 走参数：后端这个端点是通用的（SUBJECT_TYPES = ROLE|EMPLOYEE）。
  // 此前写死 ROLE，于是「某个员工要比他的角色看得更窄/更宽」做不到，
  // 只能为他单开一个角色 —— 而角色是给一类人用的，为一个人开一个角色会让角色表迅速失去意义。
  getDataScope: (subjectType, subjectNo) =>
    client.get(`/api/platform/data-scopes/${subjectType}/${subjectNo}`),
  saveDataScope: (subjectType, subjectNo, scope, values) =>
    client.put(`/api/platform/data-scopes/${subjectType}/${subjectNo}`, { scopeType: scope, scopeRefs: values ?? "" }),

  // 功能权限（S6）。目录与覆盖写都走 IamAdminController，前缀是 /api/platform/**iam**/**
  // （类上 @RequestMapping("/api/platform/iam")，与 PlatformController 的 /api/platform/roles 分开）。
  listPermissions: () => client.get("/api/platform/iam/permissions"),
  // 读侧（2026-07-30 后端已补）：直接读 iam_role_perm 表 —— 不走 PermissionService，
  // 那一层有进程内缓存，会滞后于刚提交的覆盖写，勾选树会回显旧值。
  // 未知角色返回 400（本项目无 404 映射，GlobalExceptionHandler 把 IllegalArgumentException 映射为 400）；
  // 「角色存在但无任何权限」是 200 + []，与「角色不存在」严格区分。
  listRolePermissions: (roleNo) => client.get(`/api/platform/iam/roles/${roleNo}/permissions`),
  // 覆盖写；body 形状对齐后端 `Map<String, List<String>>`，键名固定 perms。
  saveRolePermissions: (roleNo, perms) => client.put(`/api/platform/iam/roles/${roleNo}/permissions`, { perms }),

  // 详情（2026-07-30 后端已补）。⚠️ 但审计写入侧根本没接：全后端零处调用 AuditLogService.append，
  // iam_audit_log 是空表，列表数据来自内存种子。且该表没有 request_id/user_agent/前后值列，
  // 故 requestId/userAgent 恒为空串、changes 恒为 []（页面需把空串渲染成短横）。
  // 真正可用需要写入切面 + DDL 补列 + 引入 requestId 概念 —— 见任务台账。
  getAuditDetail: (id) => client.get(`/api/platform/audit-logs/${id}`),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  archiveRole: (no) => client.post(`/api/platform/roles/${no}/archive`, {}),
  unarchiveRole: (no) => client.post(`/api/platform/roles/${no}/unarchive`, {}),
};
