// 组织域：租户 tenants / 租户配置 tenantConfigs / 员工 employees / 角色 roles /
// 操作审计 audits / 部门 departments / 员工绩效 staffPerformances。
import type {
  Tenant, TenantConfig, Employee, RoleRow, AuditEntry,
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
export const roles: RoleRow[] = [
  { roleNo: "R1", code: "ADMIN", name: "运营管理员", permCount: 80, memberCount: 3, builtin: true, dataScope: "ALL" },
  { roleNo: "R2", code: "OPS", name: "运维", permCount: 22, memberCount: 12, builtin: true, dataScope: "REGION" },
  { roleNo: "R3", code: "CS", name: "客服", permCount: 16, memberCount: 6, builtin: true, dataScope: "ALL" },
  { roleNo: "R4", code: "FINANCE", name: "财务", permCount: 20, memberCount: 4, builtin: true, dataScope: "ALL" },
  { roleNo: "R5", code: "BD", name: "拓展", permCount: 15, memberCount: 5, builtin: true, dataScope: "REGION" },
  { roleNo: "R6", code: "VIEWER", name: "只读", permCount: 12, memberCount: 2, builtin: true, dataScope: "ALL" },
  { roleNo: "R7", code: "AGENT", name: "代理商", permCount: 8, memberCount: 9, builtin: true, dataScope: "AGENT" },
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
export const saveEmployee = (x: Partial<Employee>) => upsert(employees, x, "employeeNo", () => nextNo("E", employees, 100));
