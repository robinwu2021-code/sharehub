// 三级导航 SSOT：域(L1) → 模块(L2) → 子功能(L3)。
// 依据 docs/technical/TDD-运营端三级导航.md 附录A（逐行对照，勿凭记忆增删）。
// - 域可见性是派生的（域内任一模块 canModule 命中），无独立权限码。
// - L3 可见性 = leaf.perm ? can(role, perm) : 跟随父模块。
// - soon = 待建：灰显不可点，不产生 404 入口。
// - phase = 产品分期：phase > CURRENT_PHASE 的叶子灰显不可点（按期屏蔽）。
//   Phase 1=MVP T1-T3 | Phase 2=规模化 T4-T6 | Phase 3=生态 T7-T9。
// - 深链沿用 ?tab= / ?view=；本文件为纯数据+纯函数（无 React），可单测。
import type { Role } from "./auth";
import { can, canModule } from "./permissions";
import type { Phase } from "./phase";
import { isPhaseLocked } from "./phase";

export type NavMode = "panel" | "miller";
export const NAV_MODE_DEFAULT: NavMode = "panel";
export const NAV_PREFS_STORAGE_KEY = "ops-nav-prefs";

// 布局常量（px）
export const RAIL_WIDTH = 56;
export const RAIL_EXPANDED_WIDTH = 168;
export const PANEL_WIDTH = 208;
export const MILLER_MODULE_WIDTH = 148;
export const MILLER_LEAF_WIDTH = 188;

export interface NavLeaf {
  href: string; // 详情/深链（可含 ?tab= / ?view=，可跨模块）
  label: string;
  perm?: string; // 细粒度权限码；无则跟随父模块 canModule
  soon?: boolean; // 待建：灰显不可点
  phase?: Phase; // 产品分期（缺省=P1）；phase > CURRENT_PHASE 时灰显不可点
  /**
   * L3 分组标题（按「机构/角色」或「对象」聚类，2026-07-29 结构优化）。
   * 渲染规则：同一 group 的连续叶子共用一个小标题；不设 group 的叶子直接平铺。
   * 约束：同 group 的叶子必须在 children 中相邻（单测 nav.test.ts 保证）。
   * 动机：对标简电云把财务按「平台/运营商/商户/会员」切成四个 L2——
   *      我们不拆模块（一页一模块的解析约束），改用 L3 分组达到同样的「一眼看清是谁的账」。
   */
  group?: string;
}

export interface NavModule {
  key: string;
  label: string;
  icon: string;
  module: string; // 权限码模块前缀（canModule 过滤）
  href: string; // 模块首页
  match?: string[]; // 路径归属前缀（默认 = href 的 path 部分）；如 system 模块归属 /system
  soon?: boolean; // 整模块待建（页面不存在）
  phase?: Phase; // 产品分期（缺省=P1）；整模块按期屏蔽
  children?: NavLeaf[];
}

export interface NavDomain {
  key: string;
  label: string;
  icon: string;
  pinBottom?: boolean; // Rail 固定底部
  modules: NavModule[];
}

export const NAV: NavDomain[] = [
  {
    key: "overview",
    label: "概览",
    icon: "LayoutDashboard",
    modules: [
      { key: "dashboard", label: "经营看板", icon: "LayoutDashboard", module: "dashboard", href: "/" },
    ],
  },
  {
    key: "device-ops",
    label: "设备运营",
    icon: "Cpu",
    modules: [
      {
        key: "device", label: "设备管理", icon: "Server", module: "device", href: "/devices",
        children: [
          // 按「资产台账 / 在线运行 / 资产流转」分组
          { href: "/devices", label: "设备台账", perm: "device:cabinet:read", group: "资产台账" },
          { href: "/devices?tab=powerbanks", label: "充电宝管理", perm: "device:powerbank:read", group: "资产台账" },
          { href: "/devices?tab=monitor", label: "实时监控", perm: "device:cabinet:read", group: "在线运行" },
          { href: "/devices?tab=commands", label: "远程控制·指令记录", perm: "device:command:send", group: "在线运行" },
          { href: "/devices?tab=logs", label: "设备日志", perm: "device:cabinet:read", phase: 2, group: "在线运行" },
          { href: "/devices?tab=inventory", label: "库存调拨", perm: "device:inventory:read", phase: 2, group: "资产流转" },
          { href: "/devices?tab=ota", label: "固件 OTA", perm: "device:ota:read", phase: 2, group: "资产流转" },
          { href: "/devices?tab=codes", label: "设备编码", perm: "device:cabinet:read", phase: 2, group: "资产流转" },
        ],
      },
      {
        // 对标简电云「告警管理」域（补齐清单 A）：告警码字典 → 通知规则 → 记录 → 触达。
        // 我们比它多一环：记录带「关联工单号」，规则带「静默窗口/升级策略」，字典带「建议处置/自动开工单」。
        key: "alarm", label: "告警管理", icon: "BellRing", module: "workorder", href: "/alarms",
        children: [
          { href: "/alarms", label: "告警记录", perm: "workorder:wo:read", group: "告警处置" },
          { href: "/alarms?tab=notices", label: "告警通知", perm: "workorder:wo:read", group: "告警处置" },
          { href: "/alarms?tab=codes", label: "告警代码", perm: "workorder:wo:read", group: "规则配置" },
          { href: "/alarms?tab=rules", label: "通知规则", perm: "workorder:wo:read", group: "规则配置" },
        ],
      },
      {
        key: "workorder", label: "工单管理", icon: "Wrench", module: "workorder", href: "/work-orders",
        children: [
          { href: "/work-orders?view=list", label: "工单列表", perm: "workorder:wo:read" },
          { href: "/work-orders?view=board", label: "工单看板", perm: "workorder:wo:read" },
          { href: "/work-orders?view=sla", label: "SLA 管理", phase: 2 },
          { href: "/work-orders?view=inspection", label: "巡检计划", phase: 2 },
        ],
      },
    ],
  },
  {
    // 域含「站点与点位」+「代理商管理」：前者是物理场地，后者是商业渠道，
    // 标签取「渠道与场地」以覆盖两者（原「场地与拓展」语义不含代理，2026-07-29 导航审查 #1）。
    key: "place-bd",
    label: "渠道与场地",
    icon: "MapPin",
    modules: [
      {
        key: "location", label: "站点与点位", icon: "MapPin", module: "location", href: "/locations",
        children: [
          // 分组：物理资产（站点/点位）↔ 合作机构（场地方主体及其合同/进件/生命周期）
          { href: "/locations?tab=sites", label: "站点管理", perm: "location:poi:read", group: "场地资产" },
          { href: "/locations?tab=points", label: "点位管理", perm: "location:poi:read", group: "场地资产" },
          { href: "/locations?tab=analysis", label: "站点坪效", perm: "location:analysis:read", phase: 3, group: "场地资产" },
          { href: "/locations?tab=venues", label: "场地方", perm: "location:venue:read", group: "场地方机构" },
          { href: "/locations?tab=contracts", label: "进场合同", perm: "location:contract:read", phase: 2, group: "场地方机构" },
          { href: "/locations?tab=onboarding", label: "门店 Onboarding", perm: "location:venue:read", phase: 2, group: "场地方机构" },
          { href: "/locations?tab=lifecycle", label: "门店生命周期", perm: "location:venue:read", phase: 3, group: "场地方机构" },
          { href: "/locations?tab=crm", label: "BD 拓展 CRM", phase: 3, group: "场地方机构" },
        ],
      },
      {
        key: "agent", label: "代理商管理", icon: "Handshake", module: "agent", href: "/agents",
        children: [
          // 按「机构生命周期」分组：先建档授权，再谈钱，最后看经营。
          { href: "/agents", label: "代理商档案", perm: "agent:agent:read", group: "机构档案" },
          { href: "/agents?tab=accounts", label: "代理账号管理", perm: "agent:agent:update", group: "机构档案" },
          { href: "/agents?tab=assign", label: "设备/点位划拨", perm: "agent:scope:assign", group: "机构档案" },
          { href: "/agents?tab=commission", label: "分润配置", perm: "agent:settlement:read", group: "机构收益" },
          // 跨域深链（D3）：复用财务结算单，面包屑按 URL 归属交易与资金
          { href: "/finance?tab=settlements", label: "代理收益结算", perm: "agent:settlement:read", group: "机构收益" },
          { href: "/agents?tab=performance", label: "代理绩效", perm: "agent:performance:read", phase: 2, group: "机构经营" },
        ],
      },
    ],
  },
  {
    key: "trade-fin",
    label: "交易与资金",
    icon: "ReceiptText",
    modules: [
      {
        key: "order", label: "订单管理", icon: "ReceiptText", module: "order", href: "/orders",
        children: [
          // 按「交易流水 / 售后处置 / 特殊单据」分组（补齐清单 B）
          { href: "/orders", label: "订单列表", perm: "order:order:read", group: "交易流水" },
          { href: "/orders?tab=reservations", label: "预约订单", perm: "order:order:read", phase: 2, group: "交易流水" },
          { href: "/orders?tab=exceptions", label: "异常订单", perm: "order:exception:read", group: "售后处置" },
          { href: "/orders?tab=complaints", label: "投诉订单", perm: "order:exception:read", group: "售后处置" },
          { href: "/orders?tab=refunds", label: "退款记录", perm: "order:refund:audit", group: "售后处置" },
          { href: "/orders?tab=deposit", label: "押金与欠费", perm: "order:order:read", phase: 2, group: "特殊单据" },
          { href: "/orders?tab=free", label: "免费订单", perm: "order:order:read", phase: 2, group: "特殊单据" },
        ],
      },
      {
        key: "pricing", label: "计费定价", icon: "Tag", module: "pricing", href: "/pricing",
        children: [
          { href: "/pricing", label: "计费模板", perm: "pricing:rule:read" },
          { href: "/pricing?tab=diff", label: "差异化定价", phase: 2 },
          { href: "/pricing?tab=schedule", label: "活动/时段价", phase: 3 },
        ],
      },
      {
        key: "finance", label: "财务管理", icon: "Wallet", module: "finance", href: "/finance",
        children: [
          // 按「资金主体」分组（对标简电云 平台/运营商/商户/会员 四套财务视图）：
          // 分润与结算 = 跨主体的规则与产出；平台账 = 自家的账；伙伴账 = 代理商/场地方的钱；用户账 = C 端的钱。
          { href: "/finance?tab=rules", label: "分润规则", perm: "finance:share_rule:read", group: "分润与结算" },
          { href: "/finance?tab=records", label: "分润明细", perm: "finance:share_record:read", group: "分润与结算" },
          { href: "/finance?tab=summary", label: "分润统计", perm: "finance:share_record:read", phase: 2, group: "分润与结算" },
          { href: "/finance?tab=settlements", label: "结算单", perm: "finance:settlement:read", group: "分润与结算" },
          { href: "/finance?tab=ledger", label: "账务分录", perm: "finance:ledger:read", phase: 2, group: "平台账" },
          { href: "/finance?tab=reconcile", label: "对账", perm: "finance:reconcile:read", phase: 3, group: "平台账" },
          { href: "/finance?tab=invoices", label: "发票", perm: "finance:invoice:read", phase: 3, group: "平台账" },
          // 跨域深链（导航审查 #4）：FINANCE 岗管场地方分润在本模块、代理分润在 /agents，
          // 此处回链避免跨域跳转找不到入口；面包屑按 URL 归属「渠道与场地」。
          { href: "/agents?tab=commission", label: "代理分润配置", perm: "agent:settlement:read", group: "伙伴账" },
          { href: "/finance?tab=withdrawals", label: "提现审核", perm: "finance:withdrawal:read", phase: 2, group: "伙伴账" },
          { href: "/users?tab=wallets", label: "用户钱包", perm: "user:wallet:read", phase: 3, group: "用户账" },
          { href: "/finance?tab=recharges", label: "充值订单", perm: "user:wallet:read", phase: 3, group: "用户账" },
        ],
      },
    ],
  },
  {
    // 域含 用户/营销/客服：客服是被动运营支撑而非增长工具，
    // 标签取「用户与服务」（原「用户与增长」，2026-07-29 导航审查 #3）。
    key: "user-growth",
    label: "用户与服务",
    icon: "Users",
    modules: [
      {
        key: "user", label: "用户管理", icon: "UserCircle", module: "user", href: "/users",
        children: [
          // 按「用户主体 / 风险治理 / 用户资产」分组
          { href: "/users", label: "用户列表", perm: "user:cuser:read", phase: 2, group: "用户主体" },
          { href: "/users?tab=risk", label: "风控用户", perm: "user:risk:read", phase: 2, group: "风险治理" },
          { href: "/users?tab=blacklist", label: "黑名单", perm: "user:risk:update", phase: 2, group: "风险治理" },
          { href: "/users?tab=whitelist", label: "免费用户白名单", perm: "user:risk:update", phase: 2, group: "风险治理" },
          { href: "/users?tab=members", label: "会员/次卡", perm: "user:member:read", phase: 3, group: "用户资产" },
          { href: "/users?tab=wallets", label: "钱包", perm: "user:wallet:read", phase: 3, group: "用户资产" },
          { href: "/users?tab=recharge", label: "充值套餐", perm: "user:wallet:read", phase: 3, group: "用户资产" },
        ],
      },
      {
        key: "marketing", label: "营销管理", icon: "Ticket", module: "marketing", href: "/marketing",
        children: [
          // 公告管理：c-app 首页 Hub 的「公告条」需要运营端发布口，原清单遗漏（补齐清单 E1）
          { href: "/marketing?tab=notices", label: "公告管理", perm: "marketing:coupon:read", group: "运营内容" },
          { href: "/marketing", label: "优惠券", perm: "marketing:coupon:read", phase: 2, group: "促销玩法" },
          { href: "/marketing?tab=campaigns", label: "活动", phase: 2, group: "促销玩法" },
          { href: "/marketing?tab=push", label: "推送触达", perm: "marketing:push:send", phase: 3, group: "促销玩法" },
          { href: "/marketing?tab=referral", label: "邀请裂变", phase: 3, group: "促销玩法" },
          { href: "/marketing?tab=ad-slots", label: "广告位管理", phase: 3, group: "广告经营" },
          { href: "/marketing?tab=ad-campaigns", label: "广告活动", phase: 3, group: "广告经营" },
          { href: "/marketing?tab=ad-delivery", label: "投放与曝光", phase: 3, group: "广告经营" },
        ],
      },
      {
        // 复用项为跨域深链，可点
        key: "cs", label: "客服管理", icon: "Headset", module: "cs", href: "/cs",
        children: [
          { href: "/cs", label: "报障受理", phase: 2 },
          { href: "/cs?tab=sessions", label: "客服会话", phase: 2 },
          { href: "/orders", label: "退款/补偿", perm: "order:refund:apply" },
          { href: "/users", label: "黑名单处理", perm: "user:risk:update", phase: 2 },
        ],
      },
    ],
  },
  {
    key: "analytics",
    label: "数据报表",
    icon: "ChartColumn",
    modules: [
      {
        // 分期说明（导航审查 #2）：PDF V4 的「基础运营报表 P1」由「概览 › 经营看板」承载
        // （KPI/趋势/实时告警/待办中心/排名榜单），本模块全部为 P2/P3 的专题分析报表，
        // 故 P1 阶段本域整体灰显属预期。改阶段先改 docs/requirements/运营端功能清单.md §二·B。
        key: "report", label: "数据报表", icon: "ChartColumn", module: "report", href: "/reports",
        children: [
          { href: "/reports?tab=device", label: "设备运营分析", perm: "report:device:read", phase: 2 },
          { href: "/reports?tab=location", label: "点位坪效", perm: "report:location:read", phase: 2 },
          { href: "/reports?tab=finance", label: "财务报表", phase: 2 },
          { href: "/reports?tab=screen", label: "实时大屏", phase: 3 },
          { href: "/reports?tab=custom", label: "自定义报表", phase: 3 },
          { href: "/reports?tab=consumer", label: "消费者分析", phase: 3 },
        ],
      },
    ],
  },
  {
    key: "system",
    label: "系统与权限",
    icon: "Settings",
    pinBottom: true,
    modules: [
      {
        key: "org", label: "员工与权限", icon: "Users", module: "org", href: "/employees",
        children: [
          // 按「人与组织 / 授权 / 留痕」分组
          { href: "/employees?tab=employees", label: "员工", perm: "org:employee:read", group: "人与组织" },
          { href: "/employees?tab=org", label: "组织架构", phase: 2, group: "人与组织" },
          { href: "/employees?tab=roles", label: "角色权限", perm: "org:role:read", group: "授权" },
          { href: "/employees?tab=audit", label: "操作审计", perm: "org:audit:read", phase: 2, group: "留痕与考核" },
          { href: "/employees?tab=performance", label: "绩效报表", phase: 3, group: "留痕与考核" },
        ],
      },
      {
        // D1：菜单按 system 模块过滤；供应商页按钮级用 device:vendor:*（权限双源，后端 SSOT 不改）
        key: "system", label: "系统设置", icon: "Settings", module: "system", href: "/system?tab=vendors",
        match: ["/system"],
        children: [
          // 按「接入 / 消息触达 / 业务规则 / 基础字典 / 开放与市场」分组（补齐清单 E）。
          // 注：对方把 提现设置/预约设置/充电设置 拆三个菜单、7 个支付渠道各占一菜单；
          //     我们合并为「业务规则」一页与「支付渠道」一页 —— 少菜单噪音即"信息更清晰"。
          { href: "/system?tab=vendors", label: "供应商接入", perm: "device:vendor:read", group: "接入与支付" },
          { href: "/system?tab=payment", label: "支付渠道", perm: "system:payment_channel:read", group: "接入与支付" },
          { href: "/system?tab=notify", label: "通知模板", perm: "system:notify_template:read", group: "消息触达" },
          { href: "/system?tab=notify-log", label: "发送记录", perm: "system:notify_log:read", phase: 2, group: "消息触达" },
          { href: "/system?tab=notify-blacklist", label: "触达拉黑", perm: "system:notify_blacklist:read", phase: 2, group: "消息触达" },
          { href: "/system?tab=rules", label: "业务规则", perm: "system:biz_rule:update", phase: 2, group: "业务规则" },
          { href: "/system?tab=login", label: "登录设置", perm: "system:login_setting:update", phase: 2, group: "业务规则" },
          { href: "/system?tab=app-version", label: "应用版本", perm: "system:app_version:read", phase: 2, group: "业务规则" },
          { href: "/system?tab=dict", label: "参数字典", perm: "system:dict:read", group: "基础字典" },
          { href: "/system?tab=region", label: "地区库", perm: "system:dict:read", group: "基础字典" },
          { href: "/system?tab=banks", label: "银行管理", perm: "system:bank:read", phase: 2, group: "基础字典" },
          { href: "/system?tab=problems", label: "问题管理", perm: "system:problem:read", phase: 2, group: "基础字典" },
          { href: "/system?tab=params", label: "系统参数", perm: "system:param:read", group: "基础字典" },
          { href: "/system?tab=tax", label: "税率与发票", perm: "system:tax:update", phase: 3, group: "开放与市场" },
          { href: "/system?tab=markets", label: "多国家市场", perm: "system:market:read", phase: 3, group: "开放与市场" },
          { href: "/system?tab=openapi", label: "OpenAPI 应用", perm: "system:openapi:read", phase: 3, group: "开放与市场" },
        ],
      },
    ],
  },
];

// ── 纯函数 helper（无 React 依赖，可单测） ──────────────────────────────

/** trailingSlash:true 下 pathname 带尾斜杠，比较前归一化。 */
export const normPath = (p: string) => p.replace(/\/+$/, "") || "/";

/** 拆 href 为 path + tab + view。 */
export function leafParts(href: string): { path: string; tab: string | null; view: string | null } {
  const [path, qs] = href.split("?");
  const sp = new URLSearchParams(qs);
  return { path: normPath(path), tab: sp.get("tab"), view: sp.get("view") };
}

/** L2 可见性 = canModule。 */
export function visibleModules(domain: NavDomain, role: Role | undefined): NavModule[] {
  return domain.modules.filter((m) => canModule(role, m.module));
}

/** L1 可见性派生：域内任一模块可见。 */
export function visibleDomains(role: Role | undefined): NavDomain[] {
  return NAV.filter((d) => visibleModules(d, role).length > 0);
}

/** L3 可见性 = leaf.perm ? can() : 跟随父模块。phase-locked 叶子保留（灰显）。 */
export function visibleLeaves(mod: NavModule, role: Role | undefined): NavLeaf[] {
  return (mod.children ?? []).filter((l) => (l.perm ? can(role, l.perm) : true));
}

/**
 * 把可见叶子按 group 聚成连续段，供 L2 面板渲染小标题。
 * - 无 group 的叶子聚成 `{ group: undefined }` 段（渲染时不出标题）。
 * - 只合并**相邻**同名 group（不跨段合并）——保证渲染顺序 = 数据顺序，不隐式重排。
 * - 一段内若所有叶子都被 phase 锁定，该段整体灰显（调用方可据此收起）。
 */
export function groupedLeaves(leaves: NavLeaf[]): { group?: string; leaves: NavLeaf[] }[] {
  const out: { group?: string; leaves: NavLeaf[] }[] = [];
  for (const leaf of leaves) {
    const last = out[out.length - 1];
    if (last && last.group === leaf.group) last.leaves.push(leaf);
    else out.push({ group: leaf.group, leaves: [leaf] });
  }
  return out;
}

/** 叶子是否被产品分期屏蔽（phase > CURRENT_PHASE）。 */
export function isLeafLocked(leaf: NavLeaf): boolean {
  return isPhaseLocked(leaf.phase);
}

/** 模块是否被产品分期屏蔽（整模块 phase 或所有叶子均被锁）。 */
export function isModuleLocked(mod: NavModule, role: Role | undefined): boolean {
  if (isPhaseLocked(mod.phase)) return true;
  const leaves = visibleLeaves(mod, role);
  return leaves.length > 0 && leaves.every((l) => isLeafLocked(l));
}

/** 域整体待建：可见模块全为 soon（D2：Rail 灰显）。 */
export function isDomainSoon(domain: NavDomain, role: Role | undefined): boolean {
  const mods = visibleModules(domain, role);
  return mods.length > 0 && mods.every((m) => m.soon);
}

/** 单模块域且模块无子功能（概览）：不渲染 L2 面板，详情全宽。 */
export function isSingleModuleDomain(domain: NavDomain): boolean {
  return domain.modules.length === 1 && !(domain.modules[0].children?.length);
}

/** 模块的路径归属前缀（含子路径如 /devices/detail）。 */
function moduleMatchPrefixes(mod: NavModule): string[] {
  return mod.match ?? [leafParts(mod.href).path];
}

/**
 * 由 pathname 反推当前 域+模块：最长前缀匹配；"/" 仅精确匹配。
 * 不做 RBAC 过滤——URL 已到达即需正确归属（页面自身有权限兜底）。
 */
export function findActiveModule(pathname: string): { domain: NavDomain; module: NavModule } | undefined {
  const p = normPath(pathname);
  let best: { domain: NavDomain; module: NavModule; len: number } | undefined;
  for (const domain of NAV) {
    for (const module of domain.modules) {
      for (const prefix of moduleMatchPrefixes(module)) {
        const hit = prefix === "/" ? p === "/" : p === prefix || p.startsWith(prefix + "/");
        if (hit && (!best || prefix.length > best.len)) best = { domain, module, len: prefix.length };
      }
    }
  }
  return best && { domain: best.domain, module: best.module };
}

/**
 * 当前模块的可见叶子中，命中项下标：
 * 先按 path+query 精确匹配；模块首页（无 tab/view）默认高亮首个可点叶子。
 * 未命中返回 -1。
 */
export function activeLeafIndex(
  leaves: NavLeaf[], pathname: string, tab: string | null, view: string | null,
): number {
  const p = normPath(pathname);
  const exact = leaves.findIndex((l) => {
    if (l.soon || isLeafLocked(l)) return false;
    const parts = leafParts(l.href);
    if (parts.path !== p) return false;
    if (parts.tab) return parts.tab === tab;
    if (parts.view) return parts.view === view;
    return !tab && !view;
  });
  if (exact >= 0) return exact;
  if (!tab && !view) {
    return leaves.findIndex((l) => !l.soon && !isLeafLocked(l) && leafParts(l.href).path === p);
  }
  return -1;
}

/** 域/模块的默认落地地址：首个可点叶子（排除 soon 和 phase-locked），无则模块首页。 */
export function moduleDefaultHref(mod: NavModule, role: Role | undefined): string {
  const leaf = visibleLeaves(mod, role).find((l) => !l.soon && !isLeafLocked(l));
  return leaf?.href ?? mod.href;
}
export function domainDefaultHref(domain: NavDomain, role: Role | undefined): string | undefined {
  const mod = visibleModules(domain, role).find((m) => !m.soon && !isModuleLocked(m, role));
  return mod && moduleDefaultHref(mod, role);
}

/** 叶子是否不可点：待建 或 分期锁定（渲染层统一判定）。 */
export function isLeafDisabled(leaf: NavLeaf): boolean {
  return !!leaf.soon || isLeafLocked(leaf);
}

/**
 * 按 URL 判断当前路由是否被产品分期锁定（页面级兜底用）。
 * 返回锁定它的阶段 Phase（>CURRENT_PHASE），未锁定返回 undefined。
 * 与 activeLeafIndex 不同：此处「无视锁定」匹配目标叶，才能识别到被锁叶。
 */
export function routeLockedPhase(
  pathname: string, tab: string | null, view: string | null, role: Role | undefined,
): Phase | undefined {
  const hit = findActiveModule(pathname);
  if (!hit) return undefined;
  if (isPhaseLocked(hit.module.phase)) return hit.module.phase;
  const p = normPath(pathname);
  const leaves = visibleLeaves(hit.module, role);
  let leaf = leaves.find((l) => {
    const parts = leafParts(l.href);
    if (parts.path !== p) return false;
    if (parts.tab) return parts.tab === tab;
    if (parts.view) return parts.view === view;
    return !tab && !view;
  });
  // 模块首页（无 tab/view）：落到该 path 的首叶，兜底模块首叶
  if (!leaf && !tab && !view) {
    leaf = leaves.find((l) => leafParts(l.href).path === p) ?? leaves[0];
  }
  return leaf && isLeafLocked(leaf) ? leaf.phase : undefined;
}

/** 面包屑：域 › 模块 › 子功能（子功能可能无）。 */
export function breadcrumb(
  pathname: string, tab: string | null, view: string | null, role: Role | undefined,
): string[] {
  const hit = findActiveModule(pathname);
  if (!hit) return [];
  const crumbs = [hit.domain.label];
  if (!isSingleModuleDomain(hit.domain)) crumbs.push(hit.module.label);
  const leaves = visibleLeaves(hit.module, role);
  const idx = activeLeafIndex(leaves, pathname, tab, view);
  if (idx >= 0 && leaves[idx].label !== hit.module.label) crumbs.push(leaves[idx].label);
  return crumbs;
}
