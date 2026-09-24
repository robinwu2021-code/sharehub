// 组织域：租户 tenants / 租户配置 tenantConfigs / 员工 employees / 角色 roles /
// 操作审计 audits / 部门 departments / 员工绩效 staffPerformances。
import type {
  Tenant, TenantConfig, Employee, RoleRow, AuditEntry, AuditDetail, AuditFieldChange, DataScope,
  Department, StaffPerformance, PermissionItem, PageQuery,
} from "../../types";
import type { Role } from "../../auth"; // 仅取角色码联合类型（type-only，不引入 store 运行时）
import { roleHas } from "../../permissions";
import { notFound, fail } from "@/lib/biz-error";
import { VENDORS, p, iso } from "./internal";
import { paginate, kwHit, upsert, nextNo, liveHit, archiveRow, unarchiveRow } from "./helpers";
// 只取周期→天数换算：绩效周期必须与报表域同一套 REPORT_PERIODS，不自造窗口
import { daysOf } from "./report";
// 绩效真实派生自工单流转留痕（workorder → org 单向；workorder 不 import org，无环）
import { workOrders } from "./workorder";

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
// scopeRefs 里的 ID 一律引用真实主数据：REGION→system.regions.regionId、
// LOCATION→location.sites.siteNo、AGENT→agent.agents.agentNo（role-scope.test.ts 断言这一点）。
export const roles: RoleRow[] = [
  // permCount 不在这里写死：它是「已分配权限码数」的派生量，见下方 rolePermMap 回填。
  { roleNo: "R1", code: "ADMIN", name: "运营管理员", permCount: 0, memberCount: 3, builtin: true, dataScope: "ALL", scopeRefs: "", archivedAt: null },
  { roleNo: "R2", code: "OPS", name: "运维", permCount: 0, memberCount: 12, builtin: true, dataScope: "REGION", scopeRefs: "AE-DU,AE-AZ", archivedAt: null },
  { roleNo: "R3", code: "CS", name: "客服", permCount: 0, memberCount: 6, builtin: true, dataScope: "ALL", scopeRefs: "", archivedAt: null },
  { roleNo: "R4", code: "FINANCE", name: "财务", permCount: 0, memberCount: 4, builtin: true, dataScope: "ALL", scopeRefs: "", archivedAt: null },
  { roleNo: "R5", code: "BD", name: "拓展", permCount: 0, memberCount: 5, builtin: true, dataScope: "REGION", scopeRefs: "DU-MAR,DU-DEI,DU-DT", archivedAt: null },
  { roleNo: "R6", code: "VIEWER", name: "只读", permCount: 0, memberCount: 2, builtin: true, dataScope: "ALL", scopeRefs: "", archivedAt: null },
  { roleNo: "R7", code: "AGENT", name: "代理商", permCount: 0, memberCount: 9, builtin: true, dataScope: "AGENT", scopeRefs: "AG001,AG002", archivedAt: null },
];

// —————————————————————————————————————————————————————————————
// 功能权限：权限码目录 + 角色→权限码
// —————————————————————————————————————————————————————————————
/**
 * 权限码目录（mock 侧的 `iam_permission`）。SSOT 是 docs/requirements/功能权限清单.md §1–§14，
 * 这里逐条抄录（`module` 由 code 前缀派生，不重复写）。
 *
 * 两处刻意的取舍：
 * - §15 多租户 / 带 🔒 的口子码（`tenant:*` / `dashboard:platform:read` / `device:cabinet:assign`）
 *   **不入目录**：运营端按 ADR-011 不体现多租户，把它们摆进勾选树等于给出一批点了也没页面的权限。
 * - 角色写权用 `org:role:update` 而非清单里的 `org:role:write`：后端 IamAdminController 的
 *   @PreAuthorize 实际判的是 `org:role:update`（全站写侧动词统一为 update，write 是孤例）。
 *   目录跟着**被执行的那个码**走，清单 §11 待回改。
 */
const PERM_CATALOG: ReadonlyArray<readonly [string, string]> = [
  ["dashboard:overview:read", "经营总览 查看"],
  ["dashboard:todo:read", "待办聚合 查看"],

  ["device:cabinet:read", "机柜台账 查看"],
  ["device:cabinet:create", "机柜 新增"],
  ["device:cabinet:update", "机柜 编辑"],
  ["device:cabinet:delete", "机柜 归档"],
  ["device:cabinet:import", "台账 导入"],
  ["device:cabinet:export", "台账 导出"],
  ["device:slot:read", "仓位 查看"],
  ["device:powerbank:read", "充电宝 查看"],
  ["device:powerbank:update", "充电宝 报废/标记"],
  ["device:command:send", "远程指令 下发"],
  ["device:command:batch", "批量指令 下发"],
  ["device:inventory:read", "库存/调拨/盘点 查看"],
  ["device:inventory:transfer", "调拨 执行"],
  ["device:inventory:stocktake", "盘点 执行"],
  ["device:ota:read", "固件版本库 查看"],
  ["device:ota:publish", "固件 发布投放"],
  ["device:ota:rollback", "固件 回滚"],
  ["device:vendor:read", "供应商接入 查看"],
  ["device:vendor:config", "供应商接入 配置"],

  ["location:poi:read", "点位 查看"],
  ["location:poi:create", "点位 新增"],
  ["location:poi:update", "点位 编辑"],
  ["location:poi:delete", "点位 归档"],
  ["location:venue:read", "场地方 查看"],
  ["location:venue:create", "场地方 新增"],
  ["location:venue:update", "场地方 编辑"],
  ["location:venue:delete", "场地方 归档"],
  ["location:contract:read", "合同 查看"],
  ["location:contract:create", "合同 新增"],
  ["location:contract:update", "合同 编辑"],
  ["location:contract:delete", "合同 归档"],
  ["location:crm:read", "BD CRM 查看"],
  ["location:crm:update", "BD CRM 跟进"],
  ["location:analysis:read", "坪效分析 查看"],

  ["order:order:read", "订单 查看/详情"],
  ["order:order:export", "订单 导出"],
  ["order:exception:read", "异常订单 查看"],
  ["order:exception:handle", "异常订单 处置"],
  ["order:intervene:execute", "订单干预 执行"],
  ["order:refund:apply", "退款 申请"],
  ["order:refund:audit", "退款 审批"],
  ["order:reservation:cancel", "预约 取消"],
  ["order:deposit:manage", "押金 解冻/买断"],
  ["order:arrears:dun", "欠费 催缴"],

  ["pricing:plan:read", "计费模板 查看"],
  ["pricing:plan:create", "计费模板 新增"],
  ["pricing:plan:update", "计费模板 编辑"],
  ["pricing:plan:delete", "计费模板 归档"],
  // 2026-09-23：差异化定价退役后，这两个码由**时段倍率**继续使用（后端 PricingController
  // 的 pricing-schedules 三个端点、前端 fee-plans 的 canSchedule）。码没变，名字得改 ——
  // 权限树上写着一个已经不存在的功能，勾的人不知道自己在授权什么。
  ["pricing:rule:read", "时段倍率 查看"],
  ["pricing:rule:update", "时段倍率 编辑"],

  ["finance:share_rule:read", "分润规则 查看"],
  ["finance:share_rule:create", "分润规则 新增"],
  ["finance:share_rule:update", "分润规则 编辑"],
  ["finance:share_rule:delete", "分润规则 归档"],
  ["finance:share_record:read", "分润明细 查看"],
  ["finance:ledger:read", "账务分录 查看"],
  ["finance:settlement:read", "结算单 查看"],
  ["finance:settlement:generate", "结算单 生成"],
  ["finance:settlement:confirm", "结算单 确认"],
  ["finance:withdrawal:read", "提现 查看"],
  ["finance:withdrawal:audit", "提现 审核"],
  ["finance:withdrawal:apply", "提现 申请（代理自助）"],
  ["finance:recon:read", "对账 查看"],
  ["finance:recon:handle", "对账差错 处理"],
  ["finance:invoice:read", "发票 查看"],
  ["finance:invoice:issue", "发票 登记/开具"],
  ["finance:invoice:void", "发票 作废"],

  ["workorder:wo:read", "工单 查看/看板"],
  ["workorder:wo:create", "工单 开单"],
  ["workorder:wo:dispatch", "工单 派单/退回"],
  ["workorder:wo:handle", "工单 接单/处理/完成"],
  ["workorder:wo:close", "工单 验收关单"],
  ["workorder:sla:update", "SLA 配置"],
  ["workorder:inspection:update", "巡检计划 配置"],
  ["workorder:alarm:config", "告警代码/通知规则 配置"],

  ["user:cuser:read", "用户 查看"],
  ["user:risk:update", "风控/黑名单 处置"],
  ["user:member:read", "会员 查看"],
  ["user:member:update", "会员 编辑"],
  ["user:wallet:read", "钱包 查看"],

  ["marketing:coupon:read", "优惠券 查看"],
  ["marketing:coupon:create", "优惠券 新增"],
  ["marketing:coupon:update", "优惠券 编辑"],
  ["marketing:coupon:delete", "优惠券 归档"],
  ["marketing:coupon:issue", "优惠券 发放"],
  ["marketing:campaign:read", "活动 查看"],
  ["marketing:campaign:create", "活动 新增"],
  ["marketing:campaign:update", "活动 编辑"],
  ["marketing:push:send", "推送触达 发送"],

  ["cs:ticket:read", "报障工单 查看"],
  ["cs:ticket:handle", "报障工单 受理"],
  ["cs:session:read", "客服会话 查看"],

  ["org:dept:read", "组织架构 查看"],
  ["org:dept:create", "部门 新增"],
  ["org:dept:update", "部门 编辑"],
  ["org:dept:delete", "部门 归档"],
  ["org:employee:read", "员工 查看"],
  ["org:employee:create", "员工 新增"],
  ["org:employee:update", "员工 编辑"],
  ["org:employee:delete", "员工 归档"],
  ["org:role:read", "角色 查看"],
  ["org:role:update", "角色 增改删/授权"],
  ["org:role:assign", "员工 分配角色"],
  ["org:audit:read", "操作审计 查看"],
  ["org:performance:read", "绩效报表 查看"],

  ["report:device:read", "设备报表 查看"],
  ["report:device:export", "设备报表 导出"],
  ["report:location:read", "点位报表 查看"],
  ["report:location:export", "点位报表 导出"],
  ["report:finance:read", "财务报表 查看"],
  ["report:finance:export", "财务报表 导出"],
  ["report:screen:read", "实时大屏 查看"],
  ["report:custom:read", "自定义报表 查看"],

  ["system:notify_template:read", "通知模板 查看"],
  ["system:notify_template:update", "通知模板 编辑"],
  ["system:notify_log:read", "发送记录 查看"],
  ["system:notify_blacklist:read", "触达拉黑 查看"],
  ["system:notify_blacklist:update", "触达拉黑 处置"],
  ["system:dict:read", "参数字典/地区库 查看"],
  ["system:dict:update", "参数字典/地区库 编辑"],
  ["system:openapi:read", "OpenAPI 应用 查看"],
  ["system:openapi:config", "OpenAPI 应用/密钥 配置"],
  ["system:payment_channel:read", "支付渠道 查看"],
  ["system:payment_channel:update", "支付渠道 配置"],
  ["system:market:read", "多国家市场 查看"],
  ["system:market:update", "多国家市场 编辑"],
  ["system:biz_rule:update", "业务规则 编辑"],
  ["system:login_setting:update", "登录设置 编辑"],
  ["system:app_version:read", "应用版本 查看"],
  ["system:app_version:release", "应用版本 发布"],
  ["system:bank:read", "银行字典 查看"],
  ["system:bank:update", "银行字典 编辑"],
  ["system:problem:read", "问题类型 查看"],
  ["system:problem:update", "问题类型 编辑"],
  ["system:tax:update", "税率与发票设置 编辑"],

  ["agent:agent:read", "代理商 查看"],
  ["agent:agent:create", "代理商 新增"],
  ["agent:agent:update", "代理商 编辑"],
  ["agent:agent:delete", "代理商 归档"],
  ["agent:scope:assign", "设备/点位 划拨给代理"],
  ["agent:share:config", "代理分润 配置"],
  ["agent:settlement:read", "代理结算/收益 查看"],
  ["agent:performance:read", "代理绩效 查看"],
  ["agent:account:manage", "代理账号 开通/停用"],
];
export const permissions: PermissionItem[] = PERM_CATALOG.map(([code, name]) => ({
  code, module: code.split(":")[0], name,
}));
const PERM_CODES = new Set(permissions.map((x) => x.code));

/**
 * 角色 → 已分配权限码（mock 侧的 `iam_role_perm`，覆盖写）。
 *
 * 内置七角色的初值**不另写一份**，而是拿 lib/permissions.ts 的通配映射（`device:*` 之类）
 * 在目录上展开得到 —— 两份手写清单必然漂移，而 permCount 与勾选树读的是同一个来源才自洽。
 */
// ⚠️ 这里用 roleHas 不是 can：can() 自 D6a 起收的是**后端下发的 perms**，
// 角色视角的判权改叫 roleHas（按 BACKEND_ROLE_PERMS 展开）。改回 can 会编译不过。
const rolePermMap: Record<string, string[]> = Object.fromEntries(
  roles.map((r) => [r.roleNo, permissions.filter((x) => roleHas(r.code as Role, x.code)).map((x) => x.code)]),
);
// permCount ≡ 已分配码数（org-perm.test.ts 断言这条恒等式）
roles.forEach((r) => { r.permCount = rolePermMap[r.roleNo].length; });

export const listRolePermissions = (roleNo: string): string[] => [...(rolePermMap[roleNo] ?? [])];

/**
 * 覆盖写角色功能权限。守卫与后端 IamAdminController.setRolePermissions 一致：
 * 角色必须存在、内置角色只读。多一条前端 mock 该管的：拒绝目录外的野码
 * （否则 UI 一个笔误就能往 iam_role_perm 里塞进永远不会被任何 @PreAuthorize 命中的死码）。
 */
export function saveRolePermissions(roleNo: string, perms: string[]): RoleRow {
  const i = roles.findIndex((r) => r.roleNo === roleNo);
  if (i < 0) throw notFound("角色", "Role", roleNo);
  if (roles[i].builtin) throw fail("内置角色只读，不可改权限", "Built-in roles are read-only; their permissions cannot be changed", "الأدوار المدمجة للقراءة فقط ولا يمكن تغيير صلاحياتها");
  const unknown = perms.filter((c) => !PERM_CODES.has(c));
  if (unknown.length) fail(`权限码不在目录中：${unknown.join(", ")}`,
    `Permission codes not in the catalog: ${unknown.join(", ")}`,
    `رموز صلاحيات غير موجودة في الدليل: ${unknown.join(", ")}`);
  const next = [...new Set(perms)];
  rolePermMap[roleNo] = next;
  roles[i] = { ...roles[i], permCount: next.length };
  return roles[i];
}

// —————————————————————————————————————————————————————————————
// 操作审计
// —————————————————————————————————————————————————————————————
/**
 * 操作审计。
 *
 * **每 7 条掺一条非成功的**（i % 7）：全是 SUCCESS 的话，界面上「被拒绝/失败」
 * 那两种样式在 mock 下永远看不到，第一次见到它们就是在生产环境里 ——
 * 而那时它们长什么样、够不够显眼，已经来不及改了。
 *
 * 同理掺入 AGENT 与一条 SYSTEM 内部调用：运营端与代理端共用同一套审计，
 * 「分不分得出是谁做的」这件事必须在 mock 下就能看见。
 */
export const audits: AuditEntry[] = Array.from({ length: 40 }, (_, i) => {
  const denied = i % 7 === 3;
  const failed = i % 7 === 5;
  const internal = i % 11 === 4;
  return {
    id: `A${9000 + i}`,
    actor: internal ? "SYSTEM:sharehub-scheduler" : p(["admin", "ali", "omar", "sara"], i),
    actorName: internal ? undefined : p(["管理员", "Ali Hassan", "Omar Said", "Sara Aziz"], i),
    // 内部调用没有「从哪个端」—— 留空比编一个 OPS 诚实
    clientCode: internal ? undefined : (i % 5 === 2 ? "AGENT" : "OPS"),
    // 内部调用做的是定时任务那类事。让调度器去「员工新增」在语义上讲不通，
    // 而讲不通的假数据会让人照着它推断系统行为。
    action: internal ? "结算单生成" : p(["设备远程弹出", "工单派单", "订单退款", "租户配置修改", "员工新增", "提现审核"], i),
    outcome: denied ? "DENIED" : failed ? "FAILED" : "SUCCESS",
    // 32 位十六进制，与后端 W3C traceparent 里的 traceId 同形
    traceId: `${(i + 1).toString(16).padStart(8, "0")}${"a3ce929d0e0e4736c0ffee00".slice(0, 24)}`,
    target: internal ? "2026-09" : p(["CAB1005", "WO70012", "ORD500003", "T10", "E101", "WD3001"], i),
    detail: denied ? "无此权限，已拒绝" : failed ? "参数不合法，未执行" : "操作成功",
    ip: `10.165.${i % 255}.${(i * 7) % 255}`,
    createdAt: iso(i * 1800_000),
  };
});

/**
 * 各动作的字段级改动模板。
 * 「设备远程弹出」故意是空数组：下发指令不改任何业务字段，硬编两条假 diff 比不给更误导。
 * 新增类（员工新增）before 一律 "—"：空串是「被清空」的真实语义，不能拿来表示「原本不存在」。
 */
const AUDIT_CHANGES: Record<string, AuditFieldChange[]> = {
  设备远程弹出: [],
  工单派单: [
    { field: "状态", before: "CREATED", after: "DISPATCHED" },
    { field: "处理人", before: "—", after: "omar" },
    { field: "期望完成时间", before: "—", after: "2026-07-31 18:00" },
  ],
  订单退款: [
    { field: "退款状态", before: "PENDING", after: "APPROVED" },
    { field: "退款金额(AED)", before: "0.00", after: "12.50" },
  ],
  租户配置修改: [
    { field: "免费分钟数", before: "5", after: "10" },
    { field: "买断价(AED)", before: "60.00", after: "68.00" },
    { field: "启用供应商", before: "chuwei", after: "chuwei,dianbaoke" },
  ],
  员工新增: [
    { field: "姓名", before: "—", after: "Ali Hassan" },
    { field: "部门", before: "—", after: "运维部" },
    { field: "角色", before: "—", after: "运维" },
    { field: "状态", before: "—", after: "ACTIVE" },
  ],
  提现审核: [
    { field: "状态", before: "PENDING", after: "APPROVED" },
    { field: "审核意见", before: "—", after: "资料齐全，准予放款" },
  ],
};

/** 审计详情（含改动前后对比）。id 不存在时抛错，不返回空壳详情。 */
export function getAuditDetail(id: string): AuditDetail {
  const a = audits.find((x) => x.id === id);
  if (!a) throw notFound("审计记录", "Audit entry", id);
  return {
    ...a,
    // requestId 就是 traceId（后端 V60 起有 trace_id 列）。此前 mock 自造了一个
    // `req-xxx` 形态，与真后端对不上 —— 那种"看起来有值"的假数据最难发现。
    requestId: a.traceId ?? "",
    // 内部调用没有浏览器 —— 给定时任务挂一个 Mac Chrome 的 UA，
    // 会让排查的人以为「有人用浏览器触发了它」，而那是条死路。
    userAgent: a.actor.startsWith("SYSTEM:") ? ""
      : "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/126.0 Safari/537.36",
    // **被拒绝/未完成的操作没有改动任何字段** —— 真后端也是这样：
    // 请求在鉴权或校验处就被挡下，根本没到服务层，自然没有 diff 可记。
    // 不按 outcome 过滤的话，一条 DENIED 记录会列出一串「改动前后」，
    // 而复盘的人会照着它认定「改过了」。
    changes: a.outcome === "SUCCESS" ? (AUDIT_CHANGES[a.action] ?? []).map((c) => ({ ...c })) : [],
  };
}

// —— 员工域：部门 / 绩效 ——
// parent 存上级 deptNo（顶级为空串）。三层：运营中心 → 运维部 → 大区/支持组。
export const departments: Department[] = [
  { deptNo: "D1", name: "运营中心", parent: "", memberCount: 42, leader: "Ahmed Ops", status: "ACTIVE" },
  { deptNo: "D2", name: "运维部", parent: "D1", memberCount: 18, leader: "Omar Khan", status: "ACTIVE" },
  { deptNo: "D3", name: "客服部", parent: "D1", memberCount: 12, leader: "Sara Ahmed", status: "ACTIVE" },
  { deptNo: "D4", name: "财务部", parent: "D1", memberCount: 6, leader: "Fatima N.", status: "ACTIVE" },
  { deptNo: "D5", name: "市场拓展部", parent: "D1", memberCount: 9, leader: "Yusuf BD", status: "ACTIVE" },
  { deptNo: "D6", name: "Dubai 大区", parent: "D2", memberCount: 8, leader: "Ali Hassan", status: "ACTIVE" },
  { deptNo: "D7", name: "Abu Dhabi 大区", parent: "D2", memberCount: 5, leader: "Khalid R.", status: "ACTIVE" },
  { deptNo: "D8", name: "技术支持组", parent: "D2", memberCount: 4, leader: "Wang Lei", status: "ACTIVE" },
];
/**
 * 员工名册投影：只给工号/姓名/角色，三个指标恒 0 —— 真值由 {@link buildStaffPerformances}
 * 按周期从工单现算。留这层投影是为了「没接过单的人也要出现在绩效表里」：
 * 直接按工单聚合会让零单员工整行消失，看起来像少了人。
 */
export const staffPerformances: StaffPerformance[] = Array.from({ length: 20 }, (_, i) => ({
  employeeNo: `E${100 + i}`, name: p(["Ali Hassan", "Omar Khan", "Sara Ahmed", "Wang Lei", "Fatima N."], i),
  role: p(["运维", "客服", "财务", "拓展"], i),
  // 统计周期：不知道数字覆盖哪段时间，这页的数就没法用
  period: "2026-09",
  handled: 0, avgResolveMins: 0, score: 0,
}));

/**
 * 员工绩效。**真正从工单派生**（2026-07-30 改）——此前是编的：
 * 工单种子不填流转留痕、`assigneeName` 用短名与员工名对不上，所以处理量和解决时长都算不出来，
 * 只能拿确定性噪声凑。现在种子补齐了 `handlerName`/`acceptedAt`/`completedAt` 且用员工真名，
 * 于是三个指标全部有据可依：
 *
 *  · `handled`        = 该员工在周期窗口内**完工**的工单数
 *  · `avgResolveMins` = 这些工单的 (完工 − 接单) 均值
 *  · `score`          = SLA 达成率 ×100（完工早于 `slaDueAt` 即达成）
 *
 * `score` 用 0~100 而不是原来的 3.5~5.0：页面 `gradeOf` 的档位阈值是 90/75，
 * 小数制会让**每个人都被判「待改进」**（该缺陷本轮一并修掉）。
 * 无完工工单的人：handled=0、时长 0、score 0 —— 页面把 0 单渲染成「—」而非「待改进」，
 * 「没接过单」和「干得差」是两回事。
 */
export function buildStaffPerformances(period?: string): StaffPerformance[] {
  const dayStrs = new Set(daysOf(period).map((d) => new Date(d * 86400_000).toISOString().slice(0, 10)));
  return staffPerformances.map((e) => {
    const mine = workOrders.filter((w) =>
      w.handlerName === e.name && w.completedAt && dayStrs.has(w.completedAt.slice(0, 10)));
    const mins = mine
      .filter((w) => w.acceptedAt && w.completedAt)
      .map((w) => Math.abs(new Date(w.completedAt!).getTime() - new Date(w.acceptedAt!).getTime()) / 60000);
    const onTime = mine.filter((w) => w.slaDueAt && w.completedAt
      && new Date(w.completedAt).getTime() <= new Date(w.slaDueAt).getTime()).length;
    return {
      ...e,
      handled: mine.length,
      avgResolveMins: mins.length ? Math.round(mins.reduce((a, b) => a + b, 0) / mins.length) : 0,
      score: mine.length ? Number(((onTime / mine.length) * 100).toFixed(1)) : 0,
    };
  });
}

export const listEmployees = (q: PageQuery = {}) => paginate(employees, q.page, q.size, (x) => kwHit(q.keyword, x.employeeNo, x.name, x.phone, x.email));
export const listDepartments = (q: PageQuery = {}) => paginate(departments, q.page, q.size, (x) => kwHit(q.keyword, x.deptNo, x.name, x.leader));
export const listStaffPerformance = (q: PageQuery & { period?: string } = {}) => paginate(buildStaffPerformances(q.period), q.page, q.size, (x) => kwHit(q.keyword, x.employeeNo, x.name, x.role));

/**
 * 部门落库。两道守卫都是为了保住「树能画出来」：
 * 孤儿 parent 会让整棵子树在树形视图里凭空消失，自环/成环会让递归渲染直接栈溢出。
 */
export function saveDepartment(x: Partial<Department>): Department {
  const parent = x.parent ?? "";
  if (parent) {
    if (parent === x.deptNo) throw fail("上级部门不能是自己", "A department cannot be its own parent", "لا يمكن أن يكون القسم أبًا لنفسه");
    if (!departments.some((d) => d.deptNo === parent)) throw notFound("上级部门", "Parent department", parent);
    // 往上爬一遍：若沿 parent 链能回到自己，就是成环
    for (let cur = parent, hop = 0; cur && hop <= departments.length; hop++) {
      if (cur === x.deptNo) throw fail("上级部门不能选自己的下级（会形成环）", "A department cannot sit under one of its own children — that would form a cycle", "لا يمكن وضع القسم تحت أحد فروعه — سيشكّل ذلك حلقة");
      cur = departments.find((d) => d.deptNo === cur)?.parent ?? "";
    }
  }
  return upsert(departments, { ...x, parent }, "deptNo", () => nextNo("D", departments));
}

/** 角色落库。permCount 是派生量（= rolePermMap 里的码数），表单传什么都不作数，一律按实际码数回填。 */
export function saveRoleRow(x: Partial<RoleRow>): RoleRow {
  const { permCount: _derived, ...rest } = x;
  const isNew = !x.roleNo;
  const r = upsert(roles, rest, "roleNo", () => nextNo("R", roles));
  // 新角色一律从零授权开始：编号是「最大值+1」生成的，删过角色后可能复用到旧号，
  // 沿用 ??= 会让新角色悄悄继承前任的一整套权限。
  if (isNew) rolePermMap[r.roleNo] = []; else rolePermMap[r.roleNo] ??= [];
  r.permCount = rolePermMap[r.roleNo].length;
  return r;
}

/** CSV 归一：去空白、去空项、去重，保持选择顺序。 */
export const normalizeScopeValues = (csv?: string): string =>
  [...new Set((csv ?? "").split(",").map((s) => s.trim()).filter(Boolean))].join(",");

/**
 * 角色数据权限落库（G7）：就地改 roles 数组，重开抽屉能读回。
 * ALL / SELF 语义上不带范围值，一律清空，避免残留脏数据被后端 DataScopeHandler 误用。
 */
export function saveRoleDataScope(roleCode: string, scope: DataScope, scopeRefs?: string): RoleRow {
  const i = roles.findIndex((r) => r.code === roleCode);
  if (i < 0) throw notFound("角色", "Role", roleCode);
  // ⚠️ AGENT 角色的数据范围**服务端强制**为「自己 agent_no」，不接受任何越权配置。
  // 功能权限清单 §二：「AGENT 数据范围强制 = 自己 agent_no」。
  // 前端已禁用该选项，但门必须锁在服务端——绕过 UI 直接调接口同样要被拒。
  // 后端实现本端点时须保留这条守卫。
  if (roleCode === "AGENT" && (scope !== "AGENT" || normalizeScopeValues(scopeRefs))) {
    throw fail("代理商角色的数据范围强制为自己 agent_no，不可更改或指定其它代理", "An agent role is locked to its own agent_no; it cannot be changed or pointed at another agent", "دور الوكيل مقيّد برقم وكيله ولا يمكن تغييره أو توجيهه إلى وكيل آخر");
  }
  const values = scope === "ALL" || scope === "SELF" ? "" : normalizeScopeValues(scopeRefs);
  roles[i] = { ...roles[i], dataScope: scope, scopeRefs: values };
  return roles[i];
}
export const saveEmployee = (x: Partial<Employee>) => upsert(employees, x, "employeeNo", () => nextNo("E", employees, 100));

// —— G1 软删除：角色 ——
// 内置角色（builtin）不允许归档：登录/鉴权依赖它们存在，归档等于把人锁在门外。
// 这道门必须同时锁在服务端，前端只是提前拦一次。
export function archiveRole(no: string) {
  const r = roles.find((x) => x.roleNo === no);
  if (r?.builtin) throw fail("内置角色不可归档", "Built-in roles cannot be archived", "لا يمكن أرشفة الأدوار المدمجة");
  return archiveRow(roles, "roleNo", no);
}
export const unarchiveRole = (no: string) => unarchiveRow(roles, "roleNo", no);

/** 角色列表：角色数量少，不分页；默认过滤已归档（`showArchived` 打开才带出）。 */
export const listRoles = (q: PageQuery = {}) => roles.filter((r) => liveHit(r, q.showArchived));
