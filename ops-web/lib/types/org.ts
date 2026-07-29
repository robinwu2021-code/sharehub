// 覆盖范围：组织域（platform）——员工、角色与数据权限、部门、绩效、操作审计。

export interface Employee {
  employeeNo: string;
  name: string;
  phone: string;
  email: string; // 登录/通知邮箱
  deptName: string | null;
  roleName: string;
  status: "ACTIVE" | "LEFT";
}

// —— 角色 · 审计（platform 域）——
export type DataScope = "ALL" | "REGION" | "LOCATION" | "AGENT" | "SELF";
export interface RoleRow {
  roleNo: string;
  code: string;
  name: string;
  permCount: number;
  memberCount: number;
  builtin: boolean;
  dataScope: DataScope; // 数据权限范围
}
export interface AuditEntry {
  id: string;
  actor: string;
  action: string;
  target: string;
  detail: string;
  ip: string;
  createdAt: string;
}

// —— 员工 · 待建功能补全（platform 域）——
export interface Department {
  deptNo: string;
  name: string;
  parent: string;
  memberCount: number;
  leader: string;
}
export interface StaffPerformance {
  employeeNo: string;
  name: string;
  role: string;
  handled: number;
  avgResolveMins: number;
  score: number;
}
