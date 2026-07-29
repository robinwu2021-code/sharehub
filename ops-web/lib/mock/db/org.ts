// 组织域：租户 tenants / 租户配置 tenantConfigs / 员工 employees / 角色 roles /
// 操作审计 audits / 部门 departments / 员工绩效 staffPerformances。
import type {
  Tenant, TenantConfig, Employee, RoleRow, AuditEntry, DataScope,
  Department, StaffPerformance, PageQuery,
} from "../../types";
import { VENDORS, p, iso } from "./internal";
import { paginate, kwHit, upsert, nextNo } from "./helpers";

// —— 租户 + 配置 ——
export const tenants: Tenant[] = Array.from({ length: 8 }, (_, i) => ({
  tenantNo: `T${10 + i}`, name: p(["ChargeGo FZE", "PowerUp LLC", "VoltShare", "JuiceBox ME"], i),
  brandName: p(["ChargeGo", "PowerUp", "VoltShare", "JuiceBox"], i), status: i % 5 === 0 ? "SUSPENDED" : "ENABLED",
  plan: p(["standard", "pro", "enterprise"], i), cabinetCount: 10 + i * 6, expireAt: iso(-(90 + i * 30) * 86400_000),
}));
export const tenantConfigs: TenantConfig[] = tenants.map((t) => ({
  tenantNo: t.tenantNo, brandName: t.brandName, paymentProvider: "nearpay", currency: "AED",
  freeMinutes: 5, buyoutPrice: 60, enabledVendors: VENDORS.slice(0, 2),
}));

// —— 员工 / 角色 / 审计 ——
export const employees: Employee[] = Array.from({ length: 20 }, (_, i) => ({
  employeeNo: `E${100 + i}`, name: p(["Ali Hassan", "Omar Khan", "Sara Ahmed", "Wang Lei", "Fatima N."], i),
  phone: `+9715${String(1000000 + i * 137).slice(0, 7)}`, deptName: p(["运营", "运维", "客服", "财务"], i),
  email: `${p(["ali", "omar", "sara", "wang", "fatima"], i)}.${100 + i}@sharehub.ae`,
  roleName: p(["运维", "客服", "财务", "租户管理员"], i), status: i % 11 === 0 ? "LEFT" : "ACTIVE",
}));
// scopeValues 里的 ID 一律引用真实主数据：REGION→system.regions.regionId、
// LOCATION→location.sites.siteNo、AGENT→agent.agents.agentNo（role-scope.test.ts 断言这一点）。
export const roles: RoleRow[] = [
  { roleNo: "R1", code: "ADMIN", name: "运营管理员", permCount: 80, memberCount: 3, builtin: true, dataScope: "ALL", scopeValues: "" },
  { roleNo: "R2", code: "OPS", name: "运维", permCount: 22, memberCount: 12, builtin: true, dataScope: "REGION", scopeValues: "AE-DU,AE-AZ" },
  { roleNo: "R3", code: "CS", name: "客服", permCount: 16, memberCount: 6, builtin: true, dataScope: "ALL", scopeValues: "" },
  { roleNo: "R4", code: "FINANCE", name: "财务", permCount: 20, memberCount: 4, builtin: true, dataScope: "ALL", scopeValues: "" },
  { roleNo: "R5", code: "BD", name: "拓展", permCount: 15, memberCount: 5, builtin: true, dataScope: "REGION", scopeValues: "DU-MAR,DU-DEI,DU-DT" },
  { roleNo: "R6", code: "VIEWER", name: "只读", permCount: 12, memberCount: 2, builtin: true, dataScope: "ALL", scopeValues: "" },
  { roleNo: "R7", code: "AGENT", name: "代理商", permCount: 8, memberCount: 9, builtin: true, dataScope: "AGENT", scopeValues: "AG001,AG002" },
];
export const audits: AuditEntry[] = Array.from({ length: 40 }, (_, i) => ({
  id: `A${9000 + i}`, actor: p(["admin", "ali", "omar", "sara"], i),
  action: p(["设备远程弹出", "工单派单", "订单退款", "租户配置修改", "员工新增", "提现审核"], i),
  target: p(["CAB1005", "WO70012", "ORD500003", "T10", "E101", "WD3001"], i),
  detail: "操作成功", ip: `10.165.${i % 255}.${(i * 7) % 255}`, createdAt: iso(i * 1800_000),
}));

// —— 员工域：部门 / 绩效 ——
export const departments: Department[] = [
  { deptNo: "D1", name: "运营中心", parent: "-", memberCount: 42, leader: "Ahmed Ops" },
  { deptNo: "D2", name: "运维部", parent: "运营中心", memberCount: 18, leader: "Omar Khan" },
  { deptNo: "D3", name: "客服部", parent: "运营中心", memberCount: 12, leader: "Sara Ahmed" },
  { deptNo: "D4", name: "财务部", parent: "运营中心", memberCount: 6, leader: "Fatima N." },
  { deptNo: "D5", name: "市场拓展部", parent: "运营中心", memberCount: 9, leader: "Yusuf BD" },
  { deptNo: "D6", name: "Dubai 大区", parent: "运维部", memberCount: 8, leader: "Ali Hassan" },
  { deptNo: "D7", name: "Abu Dhabi 大区", parent: "运维部", memberCount: 5, leader: "Khalid R." },
  { deptNo: "D8", name: "技术支持组", parent: "运维部", memberCount: 4, leader: "Wang Lei" },
];
export const staffPerformances: StaffPerformance[] = Array.from({ length: 20 }, (_, i) => ({
  employeeNo: `E${100 + i}`, name: p(["Ali Hassan", "Omar Khan", "Sara Ahmed", "Wang Lei", "Fatima N."], i),
  role: p(["运维", "客服", "财务", "拓展"], i), handled: 20 + (i * 17) % 300,
  avgResolveMins: 30 + (i * 13) % 240, score: Number((3.5 + (i % 6) * 0.25).toFixed(1)),
}));

export const listEmployees = (q: PageQuery = {}) => paginate(employees, q.page, q.size, (x) => kwHit(q.keyword, x.employeeNo, x.name, x.phone, x.email));
export const listDepartments = (q: PageQuery = {}) => paginate(departments, q.page, q.size, (x) => kwHit(q.keyword, x.deptNo, x.name, x.leader));
export const listStaffPerformance = (q: PageQuery = {}) => paginate(staffPerformances, q.page, q.size, (x) => kwHit(q.keyword, x.employeeNo, x.name, x.role));

export const saveDepartment = (x: Partial<Department>) => upsert(departments, x, "deptNo", () => nextNo("D", departments));
export const saveRoleRow = (x: Partial<RoleRow>) => upsert(roles, x, "roleNo", () => nextNo("R", roles));

/** CSV 归一：去空白、去空项、去重，保持选择顺序。 */
export const normalizeScopeValues = (csv?: string): string =>
  [...new Set((csv ?? "").split(",").map((s) => s.trim()).filter(Boolean))].join(",");

/**
 * 角色数据权限落库（G7）：就地改 roles 数组，重开抽屉能读回。
 * ALL / SELF 语义上不带范围值，一律清空，避免残留脏数据被后端 DataScopeHandler 误用。
 */
export function saveRoleDataScope(roleCode: string, scope: DataScope, scopeValues?: string): RoleRow {
  const i = roles.findIndex((r) => r.code === roleCode);
  if (i < 0) throw new Error(`角色不存在：${roleCode}`);
  // ⚠️ AGENT 角色的数据范围**服务端强制**为「自己 agent_no」，不接受任何越权配置。
  // 功能权限清单 §二：「AGENT 数据范围强制 = 自己 agent_no」。
  // 前端已禁用该选项，但门必须锁在服务端——绕过 UI 直接调接口同样要被拒。
  // 后端实现本端点时须保留这条守卫。
  if (roleCode === "AGENT" && (scope !== "AGENT" || normalizeScopeValues(scopeValues))) {
    throw new Error("代理商角色的数据范围强制为自己 agent_no，不可更改或指定其它代理");
  }
  const values = scope === "ALL" || scope === "SELF" ? "" : normalizeScopeValues(scopeValues);
  roles[i] = { ...roles[i], dataScope: scope, scopeValues: values };
  return roles[i];
}
export const saveEmployee = (x: Partial<Employee>) => upsert(employees, x, "employeeNo", () => nextNo("E", employees, 100));
