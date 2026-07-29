// 覆盖范围：组织与权限 —— 员工、角色、审计日志、部门、员工绩效。
// （无租户管理，租户仅后端兼容层）
import type { PageQ, ArchiveQ } from "../query";
import type {
  PageResult, Employee, RoleRow, AuditEntry, Department, StaffPerformance, DataScope,
} from "../../types";

export interface OrgApi {
  listEmployees(q?: PageQ): Promise<PageResult<Employee>>;
  listRoles(q?: ArchiveQ): Promise<RoleRow[]>;
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

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  /** 内置角色（builtin）不可归档——登录鉴权依赖其存在，服务端同样要拦。 */
  archiveRole(roleNo: string): Promise<RoleRow>;
  unarchiveRole(roleNo: string): Promise<RoleRow>;
}
