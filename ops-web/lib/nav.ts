// 三级导航 SSOT（V2 四级账户体系）：域(L1) → 模块(L2) → 子功能(L3)。
// 依据 docs/requirements/运营端功能清单-V2四级体系.md（14 域 / 27 模块 / 130 叶，逐行对照）。
// - 域可见性是派生的（域内任一模块 canModule 命中 且 至少一个可见叶）——见 visibleModules(A7)。
// - L3 可见性 = leaf.perm ? can(role, perm) : 跟随父模块。
// - phase = 产品分期：phase > CURRENT_PHASE 的叶子灰显不可点（按期屏蔽，不产生 404）。
//   Phase 1=MVP T1-T3 | Phase 2=规模化 T4-T6 | Phase 3=生态 T7-T9。P1 可点集合与老方案一致。
// - A6：同 path 多模块（finance 6 模块共享 /finance；orders 3 模块共享 /orders；marketing 2 模块）
//   由 findActiveModule 依 tab/view 归属消歧。
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
}

export interface NavModule {
  key: string;
  label: string;
  icon: string;
  module: string; // 权限码模块前缀（canModule 过滤）
  href: string; // 模块首页
  match?: string[]; // 路径归属前缀（默认 = href 的 path 部分）
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
  // 1. 概览（单模块域）
  {
    key: "overview",
    label: "概览",
    icon: "LayoutDashboard",
    modules: [
      { key: "dashboard", label: "经营看板", icon: "LayoutDashboard", module: "dashboard", href: "/" },
    ],
  },

  // 2. 运营管理
  {
    key: "operations",
    label: "运营管理",
    icon: "MapPin",
    modules: [
      {
        key: "location", label: "站点管理", icon: "MapPin", module: "location",
        href: "/locations?tab=sites", match: ["/locations"],
        children: [
          { href: "/locations?tab=sites", label: "站点总览", perm: "location:poi:read" },
          { href: "/locations?tab=points", label: "点位管理", perm: "location:poi:read" },
          { href: "/locations?tab=onboarding", label: "门店 Onboarding", perm: "location:venue:read", phase: 2 },
          { href: "/locations?tab=contracts", label: "进场合同", perm: "location:contract:read", phase: 2 },
          { href: "/locations?tab=lifecycle", label: "门店生命周期", perm: "location:venue:read", phase: 3 },
          { href: "/locations?tab=crm", label: "BD 拓展 CRM", phase: 3 },
        ],
      },
      {
        key: "pricing", label: "资费与分成", icon: "Tag", module: "pricing", href: "/pricing",
        children: [
          { href: "/pricing", label: "计费模板", perm: "pricing:rule:read" },
          { href: "/pricing?tab=diff", label: "差异化定价", perm: "pricing:rule:read", phase: 2 },
          { href: "/pricing?tab=schedule", label: "分时定价", perm: "pricing:rule:read", phase: 3 },
          { href: "/pricing?tab=station-revshare", label: "站点分成", perm: "finance:revshare:read", phase: 2 },
          { href: "/pricing?tab=merchant-revshare", label: "商户分成", perm: "finance:revshare:read", phase: 2 },
        ],
      },
      {
        key: "operations", label: "基础运营", icon: "SlidersHorizontal", module: "operations", href: "/operations",
        children: [
          { href: "/operations?tab=announcements", label: "运营公告", perm: "operations:announcement:read", phase: 2 },
          { href: "/operations?tab=app-versions", label: "App 版本", perm: "operations:app_version:read", phase: 2 },
          { href: "/operations?tab=battery-stats", label: "电池统计", perm: "operations:battery:read", phase: 2 },
          { href: "/operations?tab=bank-accounts", label: "银行账户", perm: "operations:bank_account:read", phase: 2 },
          { href: "/operations?tab=feedback", label: "用户反馈", perm: "operations:feedback:read", phase: 2 },
          { href: "/operations?tab=fx", label: "汇率与币种", perm: "operations:fx:read", phase: 2 },
        ],
      },
    ],
  },

  // 3. 设备管理
  {
    key: "devices",
    label: "设备管理",
    icon: "Cpu",
    modules: [
      {
        key: "device", label: "设备台账", icon: "Server", module: "device", href: "/devices",
        children: [
          { href: "/devices", label: "设备台账", perm: "device:cabinet:read" },
          { href: "/devices?tab=powerbanks", label: "充电宝管理", perm: "device:powerbank:read" },
          { href: "/devices?tab=monitor", label: "实时监控", perm: "device:cabinet:read" },
          { href: "/devices?tab=commands", label: "远程控制·指令记录", perm: "device:command:send" },
          { href: "/devices?tab=codes", label: "设备编码/激活码", perm: "device:cabinet:read", phase: 2 },
          { href: "/devices?tab=inventory", label: "库存调拨", perm: "device:inventory:read", phase: 2 },
          { href: "/devices?tab=slots", label: "仓位管理", perm: "device:slot:read", phase: 2 },
          { href: "/devices?tab=config", label: "设备配置", perm: "device:cabinet:update", phase: 2 },
          { href: "/devices?tab=command-logs", label: "指令日志", perm: "device:command:read", phase: 2 },
          { href: "/devices?tab=cards", label: "卡管理", perm: "device:card:read", phase: 2 },
          { href: "/devices?tab=rfid", label: "RFID", perm: "device:rfid:read", phase: 2 },
          { href: "/devices?tab=batteries", label: "电池台账", perm: "device:powerbank:read", phase: 2 },
          { href: "/devices?tab=ota", label: "固件 OTA", perm: "device:ota:read", phase: 2 },
          { href: "/devices?tab=gateway", label: "设备网关/协议适配", perm: "device:cabinet:read", phase: 2 },
          { href: "/devices?tab=by-operator", label: "按运营商查看", perm: "device:cabinet:read", phase: 3 },
        ],
      },
    ],
  },

  // 4. 告警中心
  {
    key: "alerts",
    label: "告警中心",
    icon: "Bell",
    modules: [
      {
        key: "alert", label: "告警规则", icon: "Bell", module: "alert", href: "/alerts",
        children: [
          { href: "/alerts", label: "告警记录", perm: "alert:record:read", phase: 2 },
          { href: "/alerts?tab=notifications", label: "告警通知", perm: "alert:notify:read", phase: 2 },
          { href: "/alerts?tab=codes", label: "告警码", perm: "alert:code:read", phase: 2 },
          { href: "/alerts?tab=rules", label: "通知规则", perm: "alert:rule:read", phase: 2 },
          { href: "/alerts?tab=faults", label: "设备故障", perm: "alert:fault:read", phase: 2 },
        ],
      },
      {
        key: "workorder", label: "工单管理", icon: "Wrench", module: "workorder", href: "/work-orders",
        children: [
          { href: "/work-orders?view=list", label: "工单列表", perm: "workorder:wo:read", phase: 2 },
          { href: "/work-orders?view=board", label: "工单看板", perm: "workorder:wo:read", phase: 2 },
          { href: "/work-orders?view=intake", label: "报障受理", perm: "workorder:wo:create", phase: 2 },
          { href: "/work-orders?view=sla", label: "SLA 管理", phase: 2 },
          { href: "/work-orders?view=inspection", label: "巡检计划", phase: 2 },
        ],
      },
    ],
  },

  // 5. 订单管理
  {
    key: "orders",
    label: "订单管理",
    icon: "ReceiptText",
    modules: [
      {
        key: "order", label: "订单", icon: "ReceiptText", module: "order", href: "/orders",
        children: [
          { href: "/orders", label: "订单列表", perm: "order:order:read" },
          { href: "/orders?tab=exceptions", label: "异常订单", perm: "order:exception:read" },
          { href: "/orders?tab=refunds", label: "退款/补偿", perm: "order:refund:apply" },
          { href: "/orders?tab=deposit", label: "押金与欠费", perm: "order:order:read", phase: 2 },
          { href: "/orders?tab=booking", label: "预约订单", perm: "order:order:read", phase: 2 },
          { href: "/orders?tab=disputes", label: "订单争议", perm: "order:exception:handle", phase: 2 },
        ],
      },
      {
        key: "freeorder", label: "免单", icon: "Gift", module: "order", href: "/orders?tab=free-users",
        match: ["/orders"],
        children: [
          { href: "/orders?tab=free-users", label: "免单用户", perm: "order:free:read", phase: 2 },
          { href: "/orders?tab=free-orders", label: "免单订单", perm: "order:free:read", phase: 2 },
        ],
      },
      {
        key: "orderstats", label: "订单统计", icon: "BarChart3", module: "order", href: "/orders?tab=stats-device",
        match: ["/orders"],
        children: [
          { href: "/orders?tab=stats-device", label: "按设备统计", perm: "order:order:read", phase: 2 },
          { href: "/orders?tab=stats-station", label: "按站点统计", perm: "order:order:read", phase: 2 },
          { href: "/orders?tab=stats-detail", label: "订单明细报表", perm: "order:order:export", phase: 2 },
        ],
      },
    ],
  },

  // 6. 充值管理
  {
    key: "topup",
    label: "充值管理",
    icon: "Wallet",
    modules: [
      {
        key: "topup", label: "充值", icon: "Wallet", module: "topup", href: "/topup",
        children: [
          { href: "/topup", label: "充值套餐", perm: "topup:package:read", phase: 2 },
          { href: "/topup?tab=orders", label: "充值订单", perm: "topup:order:read", phase: 2 },
        ],
      },
    ],
  },

  // 7. 会员管理
  {
    key: "members",
    label: "会员管理",
    icon: "Users",
    modules: [
      {
        key: "user", label: "会员", icon: "UserCircle", module: "user", href: "/users",
        children: [
          { href: "/users", label: "用户列表", perm: "user:cuser:read" },
          { href: "/users?tab=risk", label: "风控用户", perm: "user:risk:read", phase: 2 },
          { href: "/users?tab=blacklist", label: "黑名单", perm: "user:risk:update", phase: 2 },
          { href: "/users?tab=kyc", label: "KYC 认证", perm: "user:kyc:read", phase: 2 },
          { href: "/users?tab=credit", label: "信用分/免押", perm: "user:credit:read", phase: 2 },
          { href: "/users?tab=members", label: "会员/次卡", perm: "user:member:read", phase: 3 },
          { href: "/users?tab=wallets", label: "钱包", perm: "user:wallet:read", phase: 3 },
        ],
      },
    ],
  },

  // 8. 合作伙伴（四级主入口）
  {
    key: "partners",
    label: "合作伙伴",
    icon: "Handshake",
    modules: [
      {
        // partner 模块以 location 前缀收口可见性（商户=场地方 venue；VIEWER/OPS/AGENT 凭 location 可见）
        key: "partner", label: "合作伙伴", icon: "Handshake", module: "location", href: "/partners",
        children: [
          { href: "/partners?tab=merchants", label: "商户", perm: "location:venue:read" },
          { href: "/partners?tab=agents", label: "代理商档案", perm: "agent:agent:read" },
          { href: "/partners?tab=operators", label: "运营商", perm: "agent:operator:read", phase: 3 },
          { href: "/partners?tab=onboarding", label: "入驻审核", perm: "agent:onboarding:review", phase: 2 },
        ],
      },
      {
        key: "agent", label: "代理管理", icon: "UserCog", module: "agent", href: "/agents",
        children: [
          { href: "/agents?tab=commission", label: "分润配置", perm: "agent:settlement:read" },
          { href: "/agents?tab=assign", label: "设备/点位划拨", perm: "agent:scope:assign" },
          { href: "/agents?tab=accounts", label: "代理账号", perm: "agent:agent:update" },
          // 跨域深链：复用财务结算单，面包屑按 URL 归属交易与资金
          { href: "/finance?tab=settlements", label: "代理收益结算", perm: "agent:settlement:read" },
          { href: "/agents?tab=performance", label: "代理绩效", perm: "agent:performance:read", phase: 2 },
        ],
      },
    ],
  },

  // 9. 财务管理（四级账本；6 模块共享 /finance，A6 tab 消歧）
  {
    key: "finance",
    label: "财务管理",
    icon: "DollarSign",
    modules: [
      {
        key: "fin_platform", label: "平台财务", icon: "DollarSign", module: "finance", href: "/finance",
        match: ["/finance"],
        children: [
          { href: "/finance", label: "平台流水", perm: "finance:platform_flow:read" },
          { href: "/finance?tab=platform-revenue", label: "平台收入", perm: "finance:platform_flow:read", phase: 2 },
        ],
      },
      {
        key: "fin_operator", label: "运营商财务", icon: "Building2", module: "finance", href: "/finance?tab=operator-flows",
        match: ["/finance"],
        children: [
          { href: "/finance?tab=operator-flows", label: "运营商流水", perm: "finance:operator_flow:read", phase: 3 },
          { href: "/finance?tab=operator-withdraw", label: "运营商提现", perm: "finance:operator_flow:read", phase: 3 },
        ],
      },
      {
        key: "fin_merchant", label: "商户财务", icon: "Store", module: "finance", href: "/finance?tab=merchant-flows",
        match: ["/finance"],
        children: [
          { href: "/finance?tab=merchant-flows", label: "商户流水", perm: "finance:merchant_flow:read", phase: 2 },
          { href: "/finance?tab=merchant-withdraw", label: "商户提现", perm: "finance:merchant_flow:read", phase: 2 },
        ],
      },
      {
        key: "fin_member", label: "会员财务", icon: "Wallet", module: "finance", href: "/finance?tab=member-flows",
        match: ["/finance"],
        children: [
          { href: "/finance?tab=member-flows", label: "会员流水", perm: "finance:member_flow:read", phase: 2 },
        ],
      },
      {
        key: "fin_share", label: "分润结算", icon: "Split", module: "finance", href: "/finance?tab=rules",
        match: ["/finance"],
        children: [
          { href: "/finance?tab=rules", label: "分润规则", perm: "finance:share_rule:read" },
          { href: "/finance?tab=records", label: "分润明细", perm: "finance:share_record:read" },
          // 跨域深链：代理分润配置在 /agents，FINANCE 岗从财务域直达
          { href: "/agents?tab=commission", label: "代理分润配置", perm: "agent:settlement:read" },
          { href: "/finance?tab=settlements", label: "结算单", perm: "finance:settlement:read" },
          { href: "/finance?tab=withdrawals", label: "提现审核", perm: "finance:withdrawal:read", phase: 2 },
        ],
      },
      {
        key: "fin_recon", label: "对账开票", icon: "FileText", module: "finance", href: "/finance?tab=ledger",
        match: ["/finance"],
        children: [
          { href: "/finance?tab=ledger", label: "账务分录", perm: "finance:ledger:read", phase: 2 },
          { href: "/finance?tab=reconcile", label: "对账", perm: "finance:reconcile:read", phase: 3 },
          { href: "/finance?tab=invoices", label: "发票", perm: "finance:invoice:read", phase: 3 },
          { href: "/finance?tab=tax", label: "税务与 VAT", perm: "finance:invoice:read", phase: 3 },
        ],
      },
    ],
  },

  // 10. 营销中心（活动 + 广告屏，共享 /marketing）
  {
    key: "marketing",
    label: "营销中心",
    icon: "Megaphone",
    modules: [
      {
        key: "marketing", label: "活动", icon: "Ticket", module: "marketing", href: "/marketing",
        match: ["/marketing"],
        children: [
          { href: "/marketing", label: "优惠券", perm: "marketing:coupon:read", phase: 3 },
          { href: "/marketing?tab=campaigns", label: "活动", perm: "marketing:coupon:read", phase: 3 },
          { href: "/marketing?tab=audience", label: "人群", perm: "marketing:coupon:read", phase: 3 },
          { href: "/marketing?tab=push", label: "推送触达", perm: "marketing:push:send", phase: 3 },
          { href: "/marketing?tab=referral", label: "邀请裂变", perm: "marketing:coupon:read", phase: 3 },
          { href: "/marketing?tab=banners", label: "Banner", perm: "marketing:coupon:read", phase: 3 },
        ],
      },
      {
        key: "adscreen", label: "广告屏", icon: "Monitor", module: "marketing", href: "/marketing?tab=ad-slots",
        match: ["/marketing"],
        children: [
          { href: "/marketing?tab=ad-slots", label: "广告位管理", perm: "marketing:coupon:read" },
          { href: "/marketing?tab=ad-campaigns", label: "广告活动", perm: "marketing:coupon:read", phase: 3 },
          { href: "/marketing?tab=ad-delivery", label: "投放与曝光", perm: "marketing:coupon:read", phase: 3 },
          { href: "/marketing?tab=ad-materials", label: "广告素材", perm: "marketing:coupon:read" },
          { href: "/marketing?tab=ad-playlist", label: "播放列表", perm: "marketing:coupon:read" },
          { href: "/marketing?tab=ad-emergency", label: "应急插播", perm: "marketing:coupon:read", phase: 3 },
          { href: "/marketing?tab=ad-screens", label: "广告屏设备", perm: "marketing:coupon:read" },
        ],
      },
    ],
  },

  // 11. 集成中心（仅 ADMIN）
  {
    key: "integrations",
    label: "集成中心",
    icon: "Plug",
    modules: [
      {
        key: "integration", label: "集成", icon: "Plug", module: "integration", href: "/integrations",
        children: [
          { href: "/integrations", label: "Neargo 支付", perm: "integration:neargo:read" },
          { href: "/integrations?tab=openapi", label: "OpenAPI 应用", perm: "integration:openapi:read", phase: 3 },
          { href: "/integrations?tab=pos", label: "POS 对接", perm: "integration:pos:read", phase: 3 },
          { href: "/integrations?tab=kiosk", label: "Kiosk 自助机", perm: "integration:kiosk:read", phase: 3 },
          { href: "/integrations?tab=maps", label: "地图服务", perm: "integration:maps:read", phase: 3 },
          { href: "/integrations?tab=kyc", label: "第三方 KYC", perm: "integration:kyc:read", phase: 3 },
        ],
      },
    ],
  },

  // 12. 消息中心
  {
    key: "messaging",
    label: "消息中心",
    icon: "MessageSquare",
    modules: [
      {
        key: "msg", label: "消息", icon: "MessageSquare", module: "msg", href: "/messaging",
        children: [
          { href: "/messaging", label: "发送记录", perm: "msg:record:read", phase: 2 },
          { href: "/messaging?tab=sessions", label: "客服会话", perm: "msg:record:read", phase: 2 },
          { href: "/messaging?tab=blocked", label: "黑名单", perm: "msg:blocked:read", phase: 2 },
          { href: "/messaging?tab=templates", label: "消息模板", perm: "msg:template:read" },
        ],
      },
    ],
  },

  // 13. 数据报表（sharehub 扩展）
  {
    key: "analytics",
    label: "数据报表",
    icon: "ChartColumn",
    modules: [
      {
        key: "report", label: "数据报表", icon: "ChartColumn", module: "report", href: "/reports",
        children: [
          { href: "/reports?tab=device", label: "设备运营分析", perm: "report:device:read", phase: 3 },
          { href: "/reports?tab=location", label: "点位坪效", perm: "report:location:read", phase: 3 },
          { href: "/reports?tab=site-analysis", label: "站点坪效", perm: "location:analysis:read", phase: 3 },
          { href: "/reports?tab=finance", label: "财务报表", perm: "report:finance:read", phase: 2 },
          { href: "/reports?tab=screen", label: "实时大屏", perm: "report:screen:read", phase: 3 },
          { href: "/reports?tab=custom", label: "自定义报表", perm: "report:custom:read", phase: 3 },
          { href: "/reports?tab=consumer", label: "消费者分析", perm: "report:consumer:read", phase: 3 },
        ],
      },
    ],
  },

  // 14. 系统权限（pinBottom）
  {
    key: "access",
    label: "系统权限",
    icon: "Settings",
    pinBottom: true,
    modules: [
      {
        key: "org", label: "员工与权限", icon: "Users", module: "org", href: "/employees",
        children: [
          { href: "/employees?tab=employees", label: "员工", perm: "org:employee:read" },
          { href: "/employees?tab=roles", label: "角色权限", perm: "org:role:read" },
          { href: "/employees?tab=data-scope", label: "数据策略", perm: "org:data_scope:read", phase: 2 },
          { href: "/employees?tab=audit", label: "操作审计", perm: "org:audit:read", phase: 2 },
          { href: "/employees?tab=org", label: "组织架构", phase: 2 },
          { href: "/employees?tab=performance", label: "绩效报表", phase: 3 },
        ],
      },
      {
        key: "system", label: "系统设置", icon: "Settings", module: "system", href: "/system?tab=vendors",
        match: ["/system"],
        children: [
          { href: "/system?tab=vendors", label: "供应商接入" },
          { href: "/system?tab=dict", label: "参数字典", perm: "system:dict:read" },
          { href: "/system?tab=region", label: "地区库" },
          { href: "/system?tab=params", label: "系统参数" },
          { href: "/system?tab=markets", label: "多国家市场", phase: 3 },
          { href: "/system?tab=i18n", label: "多语言文案", phase: 3 },
          { href: "/system?tab=compliance", label: "合规与备份", phase: 3 },
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

/** L3 可见性 = leaf.perm ? can() : 跟随父模块。phase-locked 叶子保留（灰显）。 */
export function visibleLeaves(mod: NavModule, role: Role | undefined): NavLeaf[] {
  return (mod.children ?? []).filter((l) => (l.perm ? can(role, l.perm) : true));
}

/** L2 可见性 = canModule 且（无子功能 或 至少一个可见叶）——A7 空模块过滤。 */
export function visibleModules(domain: NavDomain, role: Role | undefined): NavModule[] {
  return domain.modules.filter((m) => {
    if (!canModule(role, m.module)) return false;
    if (!m.children || m.children.length === 0) return true; // 无叶模块（如概览看板）
    return visibleLeaves(m, role).length > 0; // A7：命中但无可见叶 → 不渲染空 L2
  });
}

/** L1 可见性派生：域内任一模块可见。 */
export function visibleDomains(role: Role | undefined): NavDomain[] {
  return NAV.filter((d) => visibleModules(d, role).length > 0);
}

/** 叶子是否被产品分期屏蔽（phase > CURRENT_PHASE）。 */
export function isLeafLocked(leaf: NavLeaf): boolean {
  return isPhaseLocked(leaf.phase);
}

/** 模块是否被产品分期屏蔽（整模块 phase 或所有可见叶均被锁）。 */
export function isModuleLocked(mod: NavModule, role: Role | undefined): boolean {
  if (isPhaseLocked(mod.phase)) return true;
  const leaves = visibleLeaves(mod, role);
  return leaves.length > 0 && leaves.every((l) => isLeafLocked(l));
}

/** 域整体待建：可见模块全为 soon（Rail 灰显）。 */
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

/** 模块是否拥有匹配 (path, tab, view) 的叶子（A6 消歧用）。 */
function moduleOwnsLeaf(mod: NavModule, path: string, tab: string | null, view: string | null): boolean {
  return (mod.children ?? []).some((l) => {
    const lp = leafParts(l.href);
    if (lp.path !== path) return false;
    if (lp.tab) return lp.tab === tab;
    if (lp.view) return lp.view === view;
    return !tab && !view;
  });
}

/**
 * 由 pathname(+tab/view) 反推当前 域+模块：
 * 1) 最长前缀匹配；"/" 仅精确匹配；
 * 2) A6：同 path 多模块共存时，按 tab/view 归属到拥有该叶的模块；
 *    无 tab/view 时归属到自身 href 无 tab 的「基座」模块，兜底首个候选。
 * 不做 RBAC 过滤——URL 已到达即需正确归属（页面自身有权限兜底）。
 */
export function findActiveModule(
  pathname: string, tab: string | null = null, view: string | null = null,
): { domain: NavDomain; module: NavModule } | undefined {
  const p = normPath(pathname);
  let bestLen = -1;
  const cands: { domain: NavDomain; module: NavModule }[] = [];
  for (const domain of NAV) {
    for (const module of domain.modules) {
      for (const prefix of moduleMatchPrefixes(module)) {
        const hit = prefix === "/" ? p === "/" : p === prefix || p.startsWith(prefix + "/");
        if (!hit) continue;
        if (prefix.length > bestLen) { bestLen = prefix.length; cands.length = 0; cands.push({ domain, module }); }
        else if (prefix.length === bestLen) cands.push({ domain, module });
      }
    }
  }
  if (cands.length === 0) return undefined;
  if (cands.length === 1) return cands[0];
  // A6：按 tab/view 归属
  const byLeaf = cands.find((c) => moduleOwnsLeaf(c.module, p, tab, view));
  if (byLeaf) return byLeaf;
  if (!tab && !view) {
    const base = cands.find((c) => {
      const mp = leafParts(c.module.href);
      return !mp.tab && !mp.view;
    });
    if (base) return base;
  }
  return cands[0];
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
  const hit = findActiveModule(pathname, tab, view);
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
  const hit = findActiveModule(pathname, tab, view);
  if (!hit) return [];
  const crumbs = [hit.domain.label];
  if (!isSingleModuleDomain(hit.domain)) crumbs.push(hit.module.label);
  const leaves = visibleLeaves(hit.module, role);
  const idx = activeLeafIndex(leaves, pathname, tab, view);
  if (idx >= 0 && leaves[idx].label !== hit.module.label) crumbs.push(leaves[idx].label);
  return crumbs;
}
