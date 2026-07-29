// 覆盖范围：组织与权限 —— 员工、角色、审计日志、部门、员工绩效。
// 端点前缀：/api/platform/**
import { client } from "../http-client";
import type { OrgApi } from "../contracts/org";
import type { PageQ, ArchiveQ } from "../query";

export const orgHttp: OrgApi = {
  listEmployees: (q?: PageQ) => client.get("/api/platform/employees", q),
  listRoles: (q?: ArchiveQ) => client.get("/api/platform/roles", q),
  listAudits: (q?: PageQ) => client.get("/api/platform/audit-logs", q),

  // 员工扩展
  listDepartments: (q?: PageQ) => client.get("/api/platform/departments", q),
  listStaffPerformance: (q?: PageQ) => client.get("/api/platform/staff-performance", q),
  saveDepartment: (x) => client.post(x.deptNo ? `/api/platform/departments/${x.deptNo}` : "/api/platform/departments", x),
  saveRoleRow: (x) => client.post(x.roleNo ? `/api/platform/roles/${x.roleNo}` : "/api/platform/roles", x),
  saveEmployee: (x) => client.post(x.employeeNo ? `/api/platform/employees/${x.employeeNo}` : "/api/platform/employees", x),
  // 覆盖写（PUT），对齐《权限体系设计》§9「数据范围配置 PUT .../data-scope」
  saveRoleDataScope: (code, scope, values) =>
    client.put(`/api/platform/iam/roles/${code}/data-scope`, { scope, scopeValues: values ?? "" }),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  archiveRole: (no) => client.post(`/api/platform/roles/${no}/archive`, {}),
  unarchiveRole: (no) => client.post(`/api/platform/roles/${no}/unarchive`, {}),
};
