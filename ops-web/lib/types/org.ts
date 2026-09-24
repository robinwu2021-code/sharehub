// 覆盖范围：组织域（platform）——员工、角色与数据权限、部门、绩效、操作审计。

import type { Archivable } from "./common";

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
export interface RoleRow extends Archivable {
  roleNo: string;
  code: string;
  name: string;
  permCount: number;
  memberCount: number;
  builtin: boolean;
  dataScope: DataScope; // 数据权限范围
  /**
   * 数据范围的具体取值，逗号分隔的 ID 列表（对应后端 iam_data_scope.scope_refs）：
   * - REGION   → regions.regionId，如 "AE-DU,AE-AZ"
   * - LOCATION → sites.siteNo，如 "ST300,ST305"
   * - AGENT    → agents.agentNo，如 "AG001"
   * - ALL / SELF → 语义上不需要附加值，一律为空串（保存时会被清空）
   */
  scopeValues?: string;
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
/** 单个字段的改动。新增记录的 before / 删除记录的 after 用 "—"（不是空串，空串是「清空成空值」的真实语义）。 */
export interface AuditFieldChange {
  field: string;
  before: string;
  after: string;
}
/**
 * 审计详情：列表行 + 改动前后对比。
 * `changes` 故意不放进列表响应——一页 10 行、每行几十个字段的 diff 会把列表体积撑爆，
 * 且列表里根本展示不了。点开才拉详情。
 */
export interface AuditDetail extends AuditEntry {
  /**
   * 链路追踪号，串联后端日志与本条留痕。
   * ⚠️ 目前恒为**空串**：`iam_audit_log` 没有这一列，后端也还没有 requestId/traceId 概念。
   * 渲染时把空串当「无」处理（短横），不要当成 0 长度的合法追踪号。
   */
  requestId: string;
  /** ⚠️ 同 requestId，表无此列，恒为空串。 */
  userAgent: string;
  /** 后端可能回带的补充字段（种子行为 null）；缺省不影响渲染。 */
  actorName?: string | null;
  targetType?: string | null;
  targetNo?: string | null;
  /** 空数组 = 该动作不改字段（远程指令、导出这类纯动作），不是「没记全」。 */
  changes: AuditFieldChange[];
}

/**
 * 权限码目录项。形状对齐后端 `iam_permission`（GET /api/platform/iam/permissions）：
 * code = `<模块>:<资源>:<动作>`，module = 模块前缀，name = 中文名。
 * 权限码 SSOT 是 docs/requirements/功能权限清单.md，后端表为权威副本。
 */
export interface PermissionItem {
  code: string;
  module: string;
  name: string;
}

// —— 员工 · 待建功能补全（platform 域）——
export interface Department {
  deptNo: string;
  name: string;
  /**
   * 上级部门的 **deptNo**（顶级为空串）。
   * 早先存的是部门中文名——名字不保证唯一，改个名就断链，树也就拼不出来；改成引用主键。
   * 约束：必须指向 departments 里真实存在的 deptNo（org-tree.test.ts 断言无孤儿）。
   */
  parent: string;
  memberCount: number;
  leader: string;
  /** 停用的部门不该再出现在「选部门」的下拉里，但档案要留着。 */
  status: "ACTIVE" | "DISABLED" | null;
}
export interface StaffPerformance {
  employeeNo: string;
  name: string;
  role: string;
  /** 统计周期（`2026-09`）。不知道数字覆盖哪段时间，这页的数就没法用。 */
  period: string | null;
  handled: number;
  avgResolveMins: number;
  score: number;
}
