// 覆盖范围：组织与权限 —— 员工、角色、审计日志、部门、员工绩效。
// （无租户管理，租户仅后端兼容层）
import type { PageQ } from "../query";
import type {
  PageResult, Employee, RoleRow, AuditEntry, Department, StaffPerformance, DataScope,
} from "../../types";

export interface OrgApi {
  listEmployees(q?: PageQ): Promise<PageResult<Employee>>;
  listRoles(): Promise<RoleRow[]>;
  listAudits(q?: PageQ): Promise<PageResult<AuditEntry>>;

  // === 员工扩展 tab ===
  listDepartments(q?: PageQ): Promise<PageResult<Department>>;
  listStaffPerformance(q?: PageQ): Promise<PageResult<StaffPerformance>>;
  saveDepartment(x: Partial<Department> & { deptNo?: string }): Promise<Department>;
  saveRoleRow(x: Partial<RoleRow> & { roleNo?: string }): Promise<RoleRow>;
  saveEmployee(x: Partial<Employee> & { employeeNo?: string }): Promise<Employee>;

  /**
   * 角色数据权限（G7）：覆盖写该角色的数据范围。
   * scopeValues 为逗号分隔的 ID 列表（REGION→regionId / LOCATION→siteNo / AGENT→agentNo）；
   * scope 为 ALL / SELF 时无附加值，服务端会清空。
   */
  saveRoleDataScope(roleCode: string, scope: DataScope, scopeValues?: string): Promise<RoleRow>;
}
