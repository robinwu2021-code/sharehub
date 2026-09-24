// 覆盖范围：组织与权限 —— 员工、角色、审计日志、部门、员工绩效。
// （无租户管理，租户仅后端兼容层）
import type { PageQ, ArchiveQ , ReportQ } from "../query";
import type {
  PageResult, Employee, RoleRow, AuditEntry, AuditDetail, Department, StaffPerformance, DataScope,
  PermissionItem,
  DataScopeSubject, DataScopeEntry,
} from "../../types";

export interface OrgApi {
  listEmployees(q?: PageQ): Promise<PageResult<Employee>>;
  listRoles(q?: ArchiveQ): Promise<RoleRow[]>;
  listAudits(q?: PageQ): Promise<PageResult<AuditEntry>>;

  // === 员工扩展 tab ===
  listDepartments(q?: PageQ): Promise<PageResult<Department>>;
  /** 员工绩效。period 复用报表域 ReportQ（同一套周期枚举）。⚠️ 数据源见 mock/db/org.ts 注释：
   *  目前非工单派生 —— 工单种子缺流转留痕且 assigneeName 与员工名对不上。 */
  listStaffPerformance(q?: ReportQ): Promise<PageResult<StaffPerformance>>;
  saveDepartment(x: Partial<Department> & { deptNo?: string }): Promise<Department>;
  saveRoleRow(x: Partial<RoleRow> & { roleNo?: string }): Promise<RoleRow>;
  saveEmployee(x: Partial<Employee> & { employeeNo?: string }): Promise<Employee>;

  /**
   * 角色数据权限（G7）：覆盖写该角色的数据范围。
   * scopeRefs 为逗号分隔的 ID 列表（REGION→regionId / LOCATION→siteNo / AGENT→agentNo）；
   * scope 为 ALL / SELF 时无附加值，服务端会清空。
   */
  /**
   * 读某个主体当前的数据范围。
   *
   * 角色不用它（`RoleRow` 出参已带 dataScope/scopeRefs），**员工必须用** ——
   * 员工列表不带范围信息，而保存是**整体覆盖**：
   * 抽屉打开时若显示的是空值，运营点一下保存就把原设置抹了。
   */
  getDataScope(subjectType: DataScopeSubject, subjectNo: string): Promise<DataScopeEntry>;
  /**
   * 覆盖写数据范围。`subjectType` 不再写死 ROLE ——
   * 后端这个端点本来就是 ROLE|EMPLOYEE 通用的，写死的那一版让
   * 「某个员工要比他的角色看得更窄/更宽」只能靠给他单开一个角色。
   */
  saveDataScope(subjectType: DataScopeSubject, subjectNo: string,
                scope: DataScope, scopeRefs?: string): Promise<DataScopeEntry>;

  // === 功能权限（S6 权限码勾选树）===
  /** 权限码目录，构建勾选树用。全量一次拉完（~150 条），不分页。 */
  listPermissions(): Promise<PermissionItem[]>;
  /** 某角色已分配的权限码。 */
  listRolePermissions(roleNo: string): Promise<string[]>;
  /** 覆盖写角色功能权限。内置角色服务端拒绝（builtin=1 只读）。 */
  saveRolePermissions(roleNo: string, perms: string[]): Promise<RoleRow>;

  /** 审计详情（含改动前后对比）。列表不带 changes，点开才拉。 */
  getAuditDetail(id: string): Promise<AuditDetail>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  /** 内置角色（builtin）不可归档——登录鉴权依赖其存在，服务端同样要拦。 */
  archiveRole(roleNo: string): Promise<RoleRow>;
  unarchiveRole(roleNo: string): Promise<RoleRow>;
}
