// 覆盖范围：组织与权限 —— 员工、角色、审计日志、部门、员工绩效。
// 端点前缀：/api/platform/**
import { client } from "../http-client";
import type { OrgApi } from "../contracts/org";
import type { PageQ } from "../query";

export const orgHttp: OrgApi = {
  listEmployees: (q?: PageQ) => client.get("/api/platform/employees", q),
  listRoles: () => client.get("/api/platform/roles"),
  listAudits: (q?: PageQ) => client.get("/api/platform/audit-logs", q),

  // 员工扩展
  listDepartments: (q?: PageQ) => client.get("/api/platform/departments", q),
  listStaffPerformance: (q?: PageQ) => client.get("/api/platform/staff-performance", q),
  saveDepartment: (x) => client.post(x.deptNo ? `/api/platform/departments/${x.deptNo}` : "/api/platform/departments", x),
  saveRoleRow: (x) => client.post(x.roleNo ? `/api/platform/roles/${x.roleNo}` : "/api/platform/roles", x),
  saveEmployee: (x) => client.post(x.employeeNo ? `/api/platform/employees/${x.employeeNo}` : "/api/platform/employees", x),
};
