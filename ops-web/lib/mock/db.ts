// 完整 mock 数据集（覆盖 docs/api 全部域）+ 通用查询/CRUD helper。
// 仅 NEXT_PUBLIC_USE_MOCK=1 时经 lib/api/mock.ts 使用；切真实后端后不参与。
import type {
  Cabinet, Slot, RentOrder, WorkOrder, Tenant, Employee, DashboardStats, DashboardAlert,
  UserRisk, UserBlacklist, AgentCommission, VenueOnboarding, SiteLifecycle,
  Location, Venue, Contract, ShareRule, Settlement, Withdrawal, Vendor,
  CUser, Coupon, RoleRow, AuditEntry, TenantConfig, PageResult, PageQuery,
  OrderStatus, WorkOrderStatus, WorkOrderType, Agent, PricePlan, LedgerEntry, Site,
  Powerbank, CabinetMonitor, CommandRecord, InventoryTransfer, OtaRollout,
  SlaRule, InspectionPlan, Lead, SiteAnalysis, AgentAssignment, AgentPerformance,
  AgentAccount, OrderException, PricingDiff, PricingSchedule, ShareRecord, Reconcile,
  Invoice, Member, Wallet, Campaign, PushMessage, Referral, AdSlot, AdCampaign,
  AdDelivery, Department, StaffPerformance, CsTicket, CsSession, ReportDevice,
  ReportLocation, ReportFinance, ReportScreen, ReportCustom, NotifyTemplate,
  DictEntry, Region, SysParam, OpenApiApp,
  DepositRecord, MarketCountry, ConsumerSegment,
  AlarmRecord, AlarmNotice, AlarmCode, AlarmRule,
  OrderComplaint, RefundRecord, ComplaintIssueType, ComplaintResolution,
  Notice, PaymentChannel,
  DeviceLog, DeviceCodeBatch, Reservation, FreeOrder, FreeOrderStats,
  FreeUserWhitelist, RechargePackage, WhitelistReason,
  // 系统设置待建 8 项（规格 §9~§16）
  NotifyLog, NotifyLogStats, NotifyBlacklist, BizRules, LoginSetting,
  AppVersion, BankEntry, ProblemEntry, TaxSetting,
} from "../types";

const VENDORS = ["cd-tech", "sd-power", "chargenow"];
const LOCS = ["Dubai Mall L1", "Mall of Emirates", "DXB T3", "Marina Walk", "City Centre Deira", "Yas Mall", "Ibn Battuta"];
const VENUE_NAMES = ["Emaar Malls", "Majid Al Futtaim", "DXB Airports", "Aldar", "Nakheel"];
const p = <T,>(a: T[], i: number) => a[i % a.length];
const iso = (offsetMs: number) => new Date(Date.UTC(2026, 6, 11, 12, 0, 0) - offsetMs).toISOString();

// —— 设备 ——
export const cabinets: Cabinet[] = Array.from({ length: 48 }, (_, i) => {
  const total = p([6, 8, 12], i);
  const online = i % 9 !== 0;
  return {
    cabinetNo: `CAB${1000 + i}`, sn: `SN${90000 + i}`, vendorCode: p(VENDORS, i),
    model: p(["X6", "S8", "M12"], i), locationNo: `LOC${200 + (i % LOCS.length)}`,
    locationName: p(LOCS, i), slotTotal: total, availableCount: (i * 7) % (total + 1),
    onlineStatus: online ? "ONLINE" : "OFFLINE", status: i % 13 === 0 ? "FAULT" : "DEPLOYED",
    fwVersion: p(["1.2.0", "1.3.1", "1.4.0"], i), lastHeartbeatAt: online ? iso(i * 60000) : null,
  };
});
export function slotsOf(cabinetNo: string): Slot[] {
  const cab = cabinets.find((c) => c.cabinetNo === cabinetNo);
  const total = cab?.slotTotal ?? 8;
  return Array.from({ length: total }, (_, i) => {
    const filled = i < (cab?.availableCount ?? 0);
    return {
      slotIndex: i + 1, powerbankNo: filled ? `PB${cabinetNo.slice(3)}${i + 1}` : null,
      battery: filled ? 40 + ((i * 13) % 60) : null, lockStatus: filled ? "LOCKED" : "UNLOCKED",
      health: i === total - 1 && cab?.status === "FAULT" ? "FAULT" : "OK",
    };
  });
}

// —— 订单 ——
const OSTATUS: OrderStatus[] = ["IN_USE", "SETTLED", "CLOSED", "RETURNED", "EXCEPTION", "CREATED"];
export const orders: RentOrder[] = Array.from({ length: 120 }, (_, i) => {
  const st = p(OSTATUS, i);
  const dur = st === "IN_USE" || st === "CREATED" ? null : 20 + ((i * 17) % 300);
  return {
    orderNo: `ORD${500000 + i}`, cUserNo: `U${3000 + (i % 40)}`, cabinetNo: p(cabinets, i).cabinetNo,
    returnCabinetNo: st === "SETTLED" || st === "CLOSED" ? p(cabinets, i + 3).cabinetNo : null,
    powerbankNo: `PB${1000 + i}`, locationName: p(LOCS, i), status: st, rentStartAt: iso(i * 3600_000),
    rentEndAt: dur ? iso(i * 3600_000 - dur * 60000) : null, durationMin: dur,
    feeAmount: dur ? Math.min(30, Math.ceil(dur / 30) * 3) : 0, depositAmount: 50, currency: "AED",
  };
});

// —— 工单 ——
const WTYPE: WorkOrderType[] = ["FAULT", "REFILL", "INSPECT", "COMPLAINT", "CLEAN"];
const WSTATUS: WorkOrderStatus[] = ["CREATED", "DISPATCHED", "PROCESSING", "DONE", "CLOSED"];
export const workOrders: WorkOrder[] = Array.from({ length: 64 }, (_, i) => ({
  woNo: `WO${70000 + i}`, type: p(WTYPE, i), source: p(["ALERT", "USER", "VENUE", "MANUAL"] as const, i),
  priority: p(["LOW", "MEDIUM", "HIGH"] as const, i), cabinetNo: p(cabinets, i).cabinetNo,
  locationName: p(LOCS, i), status: p(WSTATUS, i), assigneeName: i % 3 === 0 ? null : p(["Ali", "Omar", "Sara", "Wang"], i),
  slaDueAt: iso(-(i % 5) * 3600_000), description: p(["柜机离线", "缺货补货", "定期巡检", "用户投诉未弹出", "清洁维护"], i),
  createdAt: iso(i * 5400_000),
}));

// —— 租户 + 配置 ——
export const tenants: Tenant[] = Array.from({ length: 8 }, (_, i) => ({
  tenantNo: `T${10 + i}`, name: p(["ChargeGo FZE", "PowerUp LLC", "VoltShare", "JuiceBox ME"], i),
  brandName: p(["ChargeGo", "PowerUp", "VoltShare", "JuiceBox"], i), status: i % 5 === 0 ? "SUSPENDED" : "ENABLED",
  plan: p(["standard", "pro", "enterprise"], i), cabinetCount: 10 + i * 6, expireAt: iso(-(90 + i * 30) * 86400_000),
}));
export const tenantConfigs: TenantConfig[] = tenants.map((t) => ({
  tenantNo: t.tenantNo, brandName: t.brandName, paymentProvider: "nearpay", currency: "AED",
  freeMinutes: 5, capTotal: 60, enabledVendors: VENDORS.slice(0, 2),
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

// —— 场所：场地方 → 站点 → 点位 → 合同（ADR-013）——
const REGIONS = ["Dubai North", "Dubai Marina", "Deira", "DXB", "JBR"];
export const sites: Site[] = Array.from({ length: 12 }, (_, i) => ({
  siteNo: `ST${300 + i}`, name: p(LOCS, i), venueName: p(VENUE_NAMES, i),
  agentNo: i % 3 === 0 ? null : `AG${String((i % 9) + 1).padStart(3, "0")}`, regionId: p(REGIONS, i),
  address: `${p(LOCS, i)}, Dubai, UAE`, sceneType: p(["商场", "机场", "餐饮", "地铁", "写字楼"], i),
  pointCount: 1 + (i % 4), cabinetCount: 2 + (i * 3) % 10, status: i % 8 === 0 ? "PAUSED" : "ACTIVE",
}));
export const locations: Location[] = Array.from({ length: 30 }, (_, i) => {
  const site = sites[i % sites.length];
  return {
    locationNo: `LOC${200 + i}`, name: `${site.name} · ${p(["L1东门", "L2中庭", "B1出口", "主入口", "美食广场"], i)}`,
    siteNo: site.siteNo, siteName: site.name, spotDesc: p(["近扶梯", "收银台旁", "入口右侧", "电梯口"], i),
    cabinetCount: 1 + (i % 3), status: i % 9 === 0 ? "PAUSED" : "ACTIVE",
  };
});
export const venues: Venue[] = VENUE_NAMES.map((name, i) => ({
  venueNo: `VEN${300 + i}`, name, contact: `+9714${String(2000000 + i * 311).slice(0, 7)}`,
  industry: p(["零售", "航空", "地产", "餐饮"], i), locationCount: 3 + i * 2,
}));
export const contracts: Contract[] = Array.from({ length: 18 }, (_, i) => ({
  contractNo: `CT${400 + i}`, venueName: p(VENUE_NAMES, i), siteName: p(LOCS, i),
  shareRate: [0.15, 0.2, 0.25, 0.3][i % 4], entryFee: (i % 4) * 500, startAt: iso(i * 30 * 86400_000),
  endAt: iso(-(365 - i * 10) * 86400_000), status: i % 9 === 0 ? "EXPIRED" : "ACTIVE",
}));

// —— 财务：分润 / 结算 / 提现 ——
export const shareRules: ShareRule[] = Array.from({ length: 12 }, (_, i) => ({
  ruleNo: `SR${600 + i}`, dimension: i % 3 === 0 ? "AGENT" : "VENUE", payeeName: p([...VENUE_NAMES, "Agent-North", "Agent-South"], i),
  mode: i % 4 === 0 ? "CHANNEL_SPLIT" : "LEDGER", rate: [0.15, 0.2, 0.25][i % 3], priority: (i % 3) + 1,
}));
export const settlements: Settlement[] = Array.from({ length: 24 }, (_, i) => ({
  settleNo: `STL${700 + i}`, payeeType: i % 3 === 0 ? "AGENT" : "VENUE", payeeName: p([...VENUE_NAMES, "Agent-North"], i),
  period: `2026-${String((i % 6) + 1).padStart(2, "0")}`, totalAmount: 800 + (i * 137) % 4000, currency: "AED",
  status: p(["GEN", "CONFIRMED", "PAID"] as const, i),
}));
// 提现：APPLY/AUDIT = 未审批（审批四列为空）；PAYING/PAID = 已通过；FAILED = 已驳回（必带原因）
const WD_REJECT = ["银行账户与合同主体不一致", "本期结算单未确认，暂缓打款", "超出单笔提现限额，需拆单重申"];
export const withdrawals: Withdrawal[] = Array.from({ length: 20 }, (_, i) => {
  const status = p(["APPLY", "AUDIT", "PAYING", "PAID", "FAILED"] as const, i);
  const amount = 500 + (i * 211) % 3000;
  const audited = status === "PAYING" || status === "PAID" || status === "FAILED";
  return {
    withdrawNo: `WD${3000 + i}`, payeeName: p([...VENUE_NAMES, "Agent-North"], i), amount,
    // 手续费 = 金额 0.6%，下限 2 AED（与提现渠道成本口径一致）
    fee: Number(Math.max(2, amount * 0.006).toFixed(2)),
    currency: "AED", status, appliedAt: iso(i * 43200_000),
    auditorName: audited ? p(["Sara Ahmed", "Omar Khan", "admin"], i) : null,
    auditedAt: audited ? iso(i * 43200_000 - 7200_000) : null,
    rejectReason: status === "FAILED" ? p(WD_REJECT, i) : null,
  };
});

// —— 账务分录（复式：每笔业务借贷成对）——
const ACCTS = ["现金-nearpay", "平台收入", "应付场地方", "应付代理", "押金负债"];
export const ledger: LedgerEntry[] = Array.from({ length: 60 }, (_, i) => {
  const pair = Math.floor(i / 2);
  const debit = i % 2 === 0;
  const amt = 3 + (pair * 7) % 25;
  return {
    entryNo: `LE${9000 + i}`, voucherNo: `V${2000 + pair}`, orderNo: `ORD${500000 + pair}`,
    account: debit ? "现金-nearpay" : p(ACCTS.slice(1), pair), direction: debit ? "DEBIT" : "CREDIT",
    amount: amt, currency: "AED", summary: debit ? "收款入账" : p(["平台分成", "场地方分润", "代理分润", "押金冻结"], pair),
    createdAt: iso(i * 1800_000),
  };
});

// —— 供应商接入 ——
export const vendors: Vendor[] = [
  { vendorCode: "cd-tech", name: "CD Technology", accessMode: "TCP", status: "ENABLED", apiBase: null, deviceCount: cabinets.filter((c) => c.vendorCode === "cd-tech").length },
  { vendorCode: "sd-power", name: "SD Power", accessMode: "MQTT", status: "ENABLED", apiBase: null, deviceCount: cabinets.filter((c) => c.vendorCode === "sd-power").length },
  { vendorCode: "chargenow", name: "ChargeNow Cloud", accessMode: "HTTP_API", status: "ENABLED", apiBase: "https://api.chargenow.example", deviceCount: cabinets.filter((c) => c.vendorCode === "chargenow").length },
];

// —— C 端用户 / 优惠券 ——
export const cUsers: CUser[] = Array.from({ length: 60 }, (_, i) => ({
  cUserNo: `U${3000 + i}`, nickname: p(["Ahmed", "Mohammed", "Fatima", "Layla", "Yusuf", "李明"], i),
  phone: `+9715${String(5000000 + i * 173).slice(0, 7)}`, creditScore: 550 + (i * 7) % 300,
  blacklisted: i % 17 === 0, orders: (i * 3) % 40, registeredAt: iso(i * 86400_000),
}));
export const coupons: Coupon[] = Array.from({ length: 14 }, (_, i) => ({
  couponNo: `CP${800 + i}`, name: p(["新人立减", "满减券", "周末折扣", "会员专享"], i),
  type: i % 2 === 0 ? "CUT" : "DISCOUNT", value: i % 2 === 0 ? [3, 5, 10][i % 3] : [8, 9][i % 2],
  threshold: (i % 3) * 10, stock: 1000 + i * 100, issued: (i * 137) % 900, status: i % 6 === 0 ? "PAUSED" : "ACTIVE",
}));

// —— 代理商 ——
export const agents: Agent[] = Array.from({ length: 9 }, (_, i) => ({
  agentNo: `AG${String(i + 1).padStart(3, "0")}`, name: p(["North Hub", "Marina Partner", "Deira Agent", "Airport Ops", "JBR Franchise"], i),
  contact: `+9715${String(6000000 + i * 271).slice(0, 7)}`, regionScope: p(["Dubai North", "Dubai Marina", "Deira", "DXB", "JBR"], i),
  shareRate: [0.3, 0.35, 0.4][i % 3], cabinetCount: 4 + i * 3, status: i % 6 === 0 ? "SUSPENDED" : "ENABLED",
}));

// —— 计费模板 ——
export const pricePlans: PricePlan[] = [
  { planNo: "PP001", name: "标准（默认）", freeMinutes: 5, unitMinutes: 30, unitPrice: 3, capDaily: 30, capTotal: 60, currency: "AED", scope: "默认", status: "ACTIVE" },
  { planNo: "PP002", name: "机场高价", freeMinutes: 3, unitMinutes: 30, unitPrice: 5, capDaily: 50, capTotal: 99, currency: "AED", scope: "机场点位", status: "ACTIVE" },
  { planNo: "PP003", name: "商场优惠", freeMinutes: 10, unitMinutes: 60, unitPrice: 2, capDaily: 20, capTotal: 49, currency: "AED", scope: "商场点位", status: "ACTIVE" },
  { planNo: "PP004", name: "旧活动价", freeMinutes: 15, unitMinutes: 30, unitPrice: 2, capDaily: 20, capTotal: 40, currency: "AED", scope: "活动", status: "DISABLED" },
];

// —— 工作台 ——
export const userRisks: UserRisk[] = [
  { riskNo: "RK0001", userNo: "U-0012", nickname: "Ali Hassan", phone: "+971501230001", creditScore: 420, riskLevel: "HIGH", reason: "多次逾期未还", flaggedAt: "2026-07-10T09:00:00Z" },
  { riskNo: "RK0002", userNo: "U-0034", nickname: "Sara Al", phone: "+971501230002", creditScore: 550, riskLevel: "MEDIUM", reason: "异常订单", flaggedAt: "2026-07-08T14:00:00Z" },
  { riskNo: "RK0003", userNo: "U-0056", nickname: "Omar K", phone: "+971501230003", creditScore: 390, riskLevel: "HIGH", reason: "疑似欺诈", flaggedAt: "2026-07-05T10:00:00Z" },
  { riskNo: "RK0004", userNo: "U-0078", nickname: "Fatima N", phone: "+971501230004", creditScore: 580, riskLevel: "MEDIUM", reason: "信用不足", flaggedAt: "2026-07-03T08:00:00Z" },
];

export const userBlacklist: UserBlacklist[] = [
  { blacklistNo: "BL0001", userNo: "U-0090", nickname: "Test Bot", phone: "+971501230099", reason: "恶意刷单", blacklistedAt: "2026-07-01T12:00:00Z", releasedAt: null, status: "ACTIVE" },
  { blacklistNo: "BL0002", userNo: "U-0091", nickname: "Spam User", phone: "+971501230098", reason: "骚扰客服", blacklistedAt: "2026-06-20T10:00:00Z", releasedAt: null, status: "ACTIVE" },
  { blacklistNo: "BL0003", userNo: "U-0092", nickname: "Old Block", phone: "+971501230097", reason: "历史黑名单", blacklistedAt: "2026-05-15T09:00:00Z", releasedAt: "2026-07-01T00:00:00Z", status: "RELEASED" },
];

export const agentCommissions: AgentCommission[] = [
  { ruleNo: "AC0001", agentNo: "AGT001", agentName: "Dubai South Agency", dimension: "GMV", rate: 0.12, mode: "CHANNEL_SPLIT", effectiveAt: "2026-01-01", status: "ACTIVE" },
  { ruleNo: "AC0002", agentNo: "AGT002", agentName: "Abu Dhabi Partners", dimension: "GMV", rate: 0.10, mode: "LEDGER", effectiveAt: "2026-01-01", status: "ACTIVE" },
  { ruleNo: "AC0003", agentNo: "AGT003", agentName: "Sharjah Ops", dimension: "ORDER_COUNT", rate: 0.08, mode: "LEDGER", effectiveAt: "2026-03-01", status: "INACTIVE" },
];

export const venueOnboardings: VenueOnboarding[] = [
  { onboardingNo: "OB0001", venueName: "Al Barsha Mall", contact: "Ahmed +971501110001", industry: "购物中心", requestedAt: "2026-07-10T10:00:00Z", status: "PENDING", reviewAt: null, reviewNote: null },
  { onboardingNo: "OB0002", venueName: "Dragon Mart 2", contact: "Lin +971501110002", industry: "商贸城", requestedAt: "2026-07-08T09:00:00Z", status: "APPROVED", reviewAt: "2026-07-09T14:00:00Z", reviewNote: "资料齐全，已通过" },
  { onboardingNo: "OB0003", venueName: "Dune Hotel", contact: "Sara +971501110003", industry: "酒店", requestedAt: "2026-07-05T11:00:00Z", status: "REJECTED", reviewAt: "2026-07-06T10:00:00Z", reviewNote: "流量不足，建议重新评估" },
  { onboardingNo: "OB0004", venueName: "City Walk Shops", contact: "Omar +971501110004", industry: "零售街区", requestedAt: "2026-07-12T08:00:00Z", status: "PENDING", reviewAt: null, reviewNote: null },
];

export const siteLifecycles: SiteLifecycle[] = [
  { siteNo: "SITE001", siteName: "Dubai Mall L1", stage: "ACTIVE", stageAt: "2026-01-10", owner: "Ali Hassan", currency: "AED", gmvLtm: 28400 },
  { siteNo: "SITE002", siteName: "Dubai Mall B2", stage: "LIVE", stageAt: "2026-06-01", owner: "Ali Hassan", currency: "AED", gmvLtm: 3200 },
  { siteNo: "SITE003", siteName: "DIFC Gate", stage: "SIGNED", stageAt: "2026-07-01", owner: "Sara Ops", currency: "AED", gmvLtm: 0 },
  { siteNo: "SITE004", siteName: "Karama Center", stage: "CHURNED", stageAt: "2026-05-15", owner: "BD Team", currency: "AED", gmvLtm: 410 },
  { siteNo: "SITE005", siteName: "Deira City Centre", stage: "PROSPECTING", stageAt: "2026-07-10", owner: "BD Team", currency: "AED", gmvLtm: 0 },
];

const dashboardAlerts: DashboardAlert[] = [
  { id: "ALT001", type: "OFFLINE", cabinetNo: "CAB1003", message: "CAB1003 离线超过 10 分钟", href: "/devices?q=CAB1003" },
  { id: "ALT002", type: "EXCEPTION", cabinetNo: "CAB1007", message: "CAB1007 出现弹仓失败订单", href: "/orders?tab=exceptions" },
  { id: "ALT003", type: "TIMEOUT", cabinetNo: "CAB1011", message: "CAB1011 工单超过 SLA 时限", href: "/work-orders" },
];

export const dashboard: DashboardStats = {
  gmvToday: 4820, ordersToday: 386,
  activeCabinets: cabinets.filter((c) => c.onlineStatus === "ONLINE").length,
  onlineRate: cabinets.filter((c) => c.onlineStatus === "ONLINE").length / cabinets.length,
  openWorkOrders: workOrders.filter((w) => w.status !== "CLOSED" && w.status !== "DONE").length,
  currency: "AED",
  trend: Array.from({ length: 7 }, (_, i) => ({ day: `D-${6 - i}`, gmv: 3000 + ((i * 613) % 2500), orders: 250 + ((i * 71) % 200) })),
  todos: { pendingWorkOrders: workOrders.filter((w) => w.status === "CREATED").length, pendingRefunds: 3, pendingWithdrawals: 2 },
  alerts: dashboardAlerts,
  rankings: [
    { rank: 1, siteName: "Dubai Mall L1", gmv: 4820, orderCount: 386, currency: "AED" },
    { rank: 2, siteName: "MOE Floor 2", gmv: 3150, orderCount: 252, currency: "AED" },
    { rank: 3, siteName: "DIFC Gate", gmv: 2840, orderCount: 231, currency: "AED" },
    { rank: 4, siteName: "Karama Center", gmv: 1920, orderCount: 154, currency: "AED" },
    { rank: 5, siteName: "Global Village E5", gmv: 1540, orderCount: 127, currency: "AED" },
  ],
};

// —— 通用 helper ——
export function paginate<T>(all: T[], page = 1, size = 10, filter?: (t: T) => boolean): PageResult<T> {
  const rows = filter ? all.filter(filter) : all;
  const start = (page - 1) * size;
  return { list: rows.slice(start, start + size), total: rows.length, page, size };
}
export const kwHit = (kw: string | undefined, ...fields: (string | null | undefined)[]) =>
  !kw || fields.some((f) => (f ?? "").toLowerCase().includes(kw.toLowerCase()));

/** 通用 mock 新增/编辑：有业务键→就地更新，无→生成键后置顶插入。返回落地记录。 */
export function upsert<T>(
  arr: T[], item: Partial<T>, keyField: keyof T, mkKey: () => string,
): T {
  const key = item[keyField] as unknown as string | undefined;
  if (key) {
    const i = arr.findIndex((x) => (x[keyField] as unknown as string) === key);
    if (i >= 0) { arr[i] = { ...arr[i], ...item }; return arr[i]; }
  }
  const created = { ...item, [keyField]: key || mkKey() } as T;
  arr.unshift(created);
  return created;
}
/** 生成新业务号：前缀 + 递增序号（基于当前数组长度）。 */
export const nextNo = (prefix: string, arr: unknown[], base = 900) => `${prefix}${base + arr.length}`;

export const saveCoupon = (c: Partial<Coupon>) => upsert(coupons, c, "couponNo", () => nextNo("CP", coupons));

// ============================================================================
// 待建功能补全 · mock 数据 + 分页 list 函数（MENA / AED / Dubai·Abu Dhabi 场景）
// list 函数签名统一：接收 {page,size,keyword?} 返回 PageResult<T>
// ============================================================================
const OPERATORS = ["admin", "Ali Hassan", "Omar Khan", "Sara Ahmed", "Wang Lei"];
const phone = (i: number, prefix = "+9715") => `${prefix}${String(1000000 + i * 173).slice(0, 7)}`;
const cabNo = (i: number) => p(cabinets, i).cabinetNo;

// —— 设备域 ——
export const powerbanks: Powerbank[] = Array.from({ length: 30 }, (_, i) => {
  const st = p(["IN_CABINET", "IN_CABINET", "RENTED", "FAULT", "RETIRED"] as const, i);
  return {
    powerbankNo: `PB${20000 + i}`, cabinetNo: cabNo(i),
    battery: st === "RENTED" ? 20 + (i * 7) % 60 : 60 + (i * 11) % 40,
    status: st, health: st === "FAULT" ? "FAULT" : "OK", cycles: 40 + (i * 37) % 900,
  };
});
export const cabinetMonitors: CabinetMonitor[] = Array.from({ length: 24 }, (_, i) => {
  const online = i % 8 !== 0;
  return {
    cabinetNo: cabNo(i), locationName: p(LOCS, i), online,
    heartbeatAt: online ? iso(i * 45000) : iso(i * 3600_000),
    signal: online ? 55 + (i * 13) % 45 : 0, temp: 28 + (i * 3) % 18,
    faultCount: i % 5 === 0 ? (i % 3) + 1 : 0,
  };
});
export const commandRecords: CommandRecord[] = Array.from({ length: 30 }, (_, i) => {
  const type = p(["EJECT", "LOCK", "REBOOT", "LOCATE"] as const, i);
  return {
    commandId: `CMD${880000 + i}`, cabinetNo: cabNo(i), type,
    slotIndex: type === "EJECT" || type === "LOCK" ? (i % 8) + 1 : null,
    status: p(["ACKED", "ACKED", "SENT", "TIMEOUT", "FAILED"] as const, i),
    operator: p(OPERATORS, i), createdAt: iso(i * 900_000),
  };
});
export const inventoryTransfers: InventoryTransfer[] = Array.from({ length: 16 }, (_, i) => ({
  transferNo: `TR${60000 + i}`, fromLocation: p(LOCS, i), toLocation: p(LOCS, i + 2),
  powerbankCount: 5 + (i * 3) % 40, status: p(["DRAFT", "IN_TRANSIT", "DONE"] as const, i),
  operator: p(OPERATORS, i), createdAt: iso(i * 43200_000),
}));
export const otaRollouts: OtaRollout[] = Array.from({ length: 14 }, (_, i) => {
  const st = p(["PENDING", "RUNNING", "DONE", "ROLLBACK"] as const, i);
  return {
    rolloutNo: `OTA${5000 + i}`, fwVersion: p(["1.4.0", "1.4.1", "1.5.0", "2.0.0"], i),
    vendorCode: p(VENDORS, i), strategy: i % 3 === 0 ? "FULL" : "GRAY",
    progress: st === "DONE" ? 100 : st === "PENDING" ? 0 : 10 + (i * 13) % 80,
    status: st, createdAt: iso(i * 86400_000),
  };
});

// —— 工单域 ——
export const slaRules: SlaRule[] = Array.from({ length: 12 }, (_, i) => ({
  slaNo: `SLA${100 + i}`, woType: p(["FAULT", "REFILL", "INSPECT", "COMPLAINT", "CLEAN"], i),
  responseMins: p([15, 30, 60], i), resolveMins: p([120, 240, 480], i),
  escalateTo: p(["运维主管", "区域经理", "运营总监"], i), active: i % 7 !== 0,
}));
export const inspectionPlans: InspectionPlan[] = Array.from({ length: 14 }, (_, i) => ({
  planNo: `IP${200 + i}`, route: `${p(LOCS, i)} → ${p(LOCS, i + 1)}`,
  frequency: p(["每日", "每周", "双周", "每月"], i), nextAt: iso(-(i % 7) * 86400_000),
  assignee: p(["Ali Hassan", "Omar Khan", "Sara Ahmed", "Wang Lei"], i), active: i % 8 !== 0,
}));

// —— 场所域 ——
export const leads: Lead[] = Array.from({ length: 20 }, (_, i) => ({
  leadNo: `LD${3000 + i}`, venueName: p([...VENUE_NAMES, "Dubai Marina Mall", "The Dubai Fountain", "Global Village"], i),
  contact: phone(i), stage: p(["NEW", "CONTACTED", "NEGOTIATING", "SIGNED", "LOST"] as const, i),
  owner: p(["BD-Layla", "BD-Yusuf", "BD-Ahmed"], i), expectSites: 1 + (i * 3) % 12,
  updatedAt: iso(i * 21600_000),
}));
export const siteAnalyses: SiteAnalysis[] = Array.from({ length: 12 }, (_, i) => ({
  siteNo: `ST${300 + i}`, siteName: p(LOCS, i), revenue: 8000 + (i * 1337) % 40000,
  orders: 200 + (i * 71) % 1800, turnover: Number((1.2 + (i % 7) * 0.6).toFixed(1)),
  paybackDays: 90 + (i * 17) % 300, cabinetCount: 2 + (i * 3) % 12, currency: "AED",
}));

// —— 代理商域 ——
export const agentAssignments: AgentAssignment[] = agents.map((a, i) => ({
  agentNo: a.agentNo, agentName: a.name, region: a.regionScope,
  cabinetCount: a.cabinetCount, siteCount: 1 + (i * 2) % 8,
}));
export const agentPerformances: AgentPerformance[] = agents
  .map((a, i) => ({
    agentNo: a.agentNo, agentName: a.name, gmv: 12000 + (i * 4337) % 90000,
    cabinetCount: a.cabinetCount, onlineRate: Number((0.82 + (i % 9) * 0.02).toFixed(2)),
    rank: 0, currency: "AED",
  }))
  .sort((x, y) => y.gmv - x.gmv)
  .map((a, i) => ({ ...a, rank: i + 1 }));
export const agentAccounts: AgentAccount[] = Array.from({ length: 12 }, (_, i) => {
  const a = p(agents, i);
  return {
    accountNo: `AA${7000 + i}`, agentNo: a.agentNo, agentName: a.name, loginPhone: phone(i, "+9714"),
    status: i % 6 === 0 ? "DISABLED" : "ACTIVE", dataScope: p(["本代理数据", "区域数据", "指定站点"], i),
    createdAt: iso(i * 86400_000),
  };
});

// —— 订单域 ——
export const orderExceptions: OrderException[] = Array.from({ length: 20 }, (_, i) => ({
  orderNo: `ORD${520000 + i}`, type: p(["NOT_EJECTED", "NOT_RETURNED", "OVERTIME_BUYOUT", "DOUBLE_CHARGE"] as const, i),
  cabinetNo: cabNo(i), userNo: `U${3000 + (i % 40)}`, amount: Number((3 + (i * 7) % 97).toFixed(2)),
  currency: "AED", status: i % 3 === 0 ? "HANDLED" : "OPEN", createdAt: iso(i * 5400_000),
}));

// —— 计费域 ——
export const pricingDiffs: PricingDiff[] = Array.from({ length: 12 }, (_, i) => ({
  ruleNo: `PD${400 + i}`, scene: p(["机场", "商场", "餐饮", "地铁", "写字楼"], i), locationName: p(LOCS, i),
  freeMins: p([3, 5, 10], i), unitPrice: p([2, 3, 5], i), dayCap: p([20, 30, 50], i),
  priority: (i % 3) + 1, currency: "AED",
}));
export const pricingSchedules: PricingSchedule[] = Array.from({ length: 12 }, (_, i) => ({
  ruleNo: `PS${500 + i}`, name: p(["周末上浮", "节假日上浮", "夜间优惠", "斋月特惠", "早高峰"], i),
  period: p(["周六-周日", "公共假日", "22:00-06:00", "斋月全月", "07:00-09:00"], i),
  multiplier: Number((0.8 + (i % 5) * 0.15).toFixed(2)), active: i % 6 !== 0,
}));

// —— 财务域 ——
export const shareRecords: ShareRecord[] = Array.from({ length: 24 }, (_, i) => {
  const dim = i % 3 === 0 ? "AGENT" : "VENUE";
  return {
    recordNo: `SREC${9000 + i}`, orderNo: `ORD${500000 + i}`, dimension: dim,
    payeeName: dim === "AGENT" ? p(agents, i).name : p(VENUE_NAMES, i),
    amount: Number((1 + (i * 7) % 20 + (i % 10) / 10).toFixed(2)),
    rate: p([0.15, 0.2, 0.25, 0.3], i), currency: "AED", createdAt: iso(i * 3600_000),
  };
});
export const reconciles: Reconcile[] = Array.from({ length: 12 }, (_, i) => {
  const nearpay = 30000 + (i * 3137) % 50000;
  const diff = i % 4 === 0 ? (i % 2 === 0 ? 1 : -1) * (12 + (i * 3) % 80) : 0;
  return {
    batchNo: `RC${2026000 + i}`, period: `2026-${String((i % 12) + 1).padStart(2, "0")}`,
    nearpayTotal: nearpay, ledgerTotal: nearpay - diff, diff, currency: "AED",
    status: diff === 0 ? "MATCHED" : "DIFF", createdAt: iso(i * 86400_000),
  };
});
export const invoices: Invoice[] = Array.from({ length: 18 }, (_, i) => ({
  invoiceNo: `INV${2026000 + i}`, payeeName: p([...VENUE_NAMES, "North Hub", "Marina Partner"], i),
  amount: Number((500 + (i * 337) % 8000).toFixed(2)), vatTrn: `100${String(1000000000000 + i * 137).slice(0, 12)}`,
  currency: "AED", status: p(["DRAFT", "ISSUED", "ISSUED", "VOID"] as const, i), issuedAt: iso(i * 172800_000),
}));

// —— 用户域 ——
const NICKS = ["Ahmed", "Mohammed", "Fatima", "Layla", "Yusuf", "李明", "Noura", "Khalid"];
export const members: Member[] = Array.from({ length: 24 }, (_, i) => ({
  userNo: `U${3000 + i}`, nickname: p(NICKS, i), level: p(["SILVER", "GOLD", "PLATINUM"] as const, i),
  points: (i * 137) % 5000, cardType: p(["无", "月卡", "季卡", "年卡"], i), expireAt: iso(-(30 + i * 15) * 86400_000),
}));
// 钱包用户号与 cUsers 一一对应（U3000+）；orderCount 直接取该用户在 cUsers 里的订单数，保证两页数据自洽
const RECHARGE_DENOM = [20, 50, 100];
export const wallets: Wallet[] = Array.from({ length: 24 }, (_, i) => {
  const userNo = `U${3000 + i}`;
  const orderCount = cUsers.find((u) => u.cUserNo === userNo)?.orders ?? (i * 3) % 40;
  const rechargeCount = Math.floor(orderCount / 4) + 1; // 约每 4 单充值一次
  const denom = p(RECHARGE_DENOM, i);
  return {
    userNo, nickname: p(NICKS, i), balance: Number(((i * 7) % 200 + (i % 10) / 10).toFixed(2)),
    bonus: Number(((i * 3) % 50).toFixed(2)), currency: "AED", updatedAt: iso(i * 43200_000),
    orderCount,
    // 客单价 4.5~7.5 AED（与租借计费口径一致）
    orderAmount: Number((orderCount * (4.5 + (i % 7) * 0.5)).toFixed(2)),
    rechargeCount,
    rechargeAmount: Number((rechargeCount * denom).toFixed(2)),
  };
});

// —— 营销域 ——
export const campaigns: Campaign[] = Array.from({ length: 14 }, (_, i) => ({
  campaignNo: `CMP${800 + i}`, name: p(["新人首借免费", "满3送1", "周末半价", "斋月回馈", "邀请有礼", "会员日"], i),
  kind: p(["满减", "折扣", "赠券", "积分"], i), rule: p(["满10减3", "首单立减5", "第2小时免费", "邀请返5AED"], i),
  status: p(["DRAFT", "RUNNING", "RUNNING", "ENDED"] as const, i),
  startAt: iso((i + 3) * 86400_000), endAt: iso(-(i + 10) * 86400_000),
}));
export const pushMessages: PushMessage[] = Array.from({ length: 16 }, (_, i) => ({
  pushNo: `PM${900 + i}`, title: p(["借充电宝立享优惠", "您有一张券即将过期", "新点位上线通知", "斋月特惠开启"], i),
  channel: i % 3 === 0 ? "SUBSCRIBE" : "APP_PUSH", audience: p(["全部用户", "活跃用户", "沉睡用户", "白金会员"], i),
  sentCount: i % 4 === 0 ? 0 : 500 + (i * 337) % 20000, status: i % 4 === 0 ? "DRAFT" : "SENT",
  sentAt: iso(i * 86400_000),
}));
export const referrals: Referral[] = Array.from({ length: 20 }, (_, i) => ({
  inviteNo: `RF${4000 + i}`, inviter: p(NICKS, i), invitee: p(NICKS, i + 3),
  reward: p([5, 8, 10], i), status: i % 3 === 0 ? "PENDING" : "REWARDED",
  createdAt: iso(i * 43200_000), currency: "AED",
}));
export const adSlots: AdSlot[] = Array.from({ length: 20 }, (_, i) => ({
  slotNo: `AS${600 + i}`, cabinetNo: cabNo(i), position: i % 2 === 0 ? "SCREEN" : "BODY",
  size: i % 2 === 0 ? p(["1080x1920", "720x1280"], i) : p(["A4贴片", "半身贴"], i),
  status: i % 3 === 0 ? "IDLE" : "OCCUPIED", createdAt: iso(i * 86400_000),
}));
export const adCampaigns: AdCampaign[] = Array.from({ length: 14 }, (_, i) => ({
  adNo: `AD${700 + i}`, advertiser: p(["Emirates NBD", "Careem", "Noon", "Talabat", "Etisalat"], i),
  creative: p(["品牌视频30s", "开屏图", "轮播图", "互动H5"], i), targeting: p(["全城", "机场点位", "商场点位", "白金会员"], i),
  status: p(["DRAFT", "RUNNING", "RUNNING", "ENDED"] as const, i),
  startAt: iso((i + 2) * 86400_000), endAt: iso(-(i + 12) * 86400_000),
}));
export const adDeliveries: AdDelivery[] = Array.from({ length: 24 }, (_, i) => ({
  deliveryNo: `DLV${5000 + i}`, adNo: `AD${700 + (i % 14)}`, slotNo: `AS${600 + (i % 20)}`,
  impressions: 1000 + (i * 733) % 50000, plays: 800 + (i * 511) % 40000,
  date: iso(i * 86400_000).slice(0, 10),
}));

// —— 员工域 ——
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

// —— 客服域 ——
export const csTickets: CsTicket[] = Array.from({ length: 24 }, (_, i) => ({
  ticketNo: `TK${8000 + i}`, userNo: `U${3000 + (i % 40)}`, cabinetNo: cabNo(i),
  issue: p(["充电宝未弹出", "扣费异常", "归还后仍计费", "设备离线", "押金未退", "APP闪退"], i),
  channel: p(["APP", "WhatsApp", "电话", "邮件"], i), status: p(["OPEN", "PROCESSING", "CLOSED"] as const, i),
  createdAt: iso(i * 3600_000),
}));
export const csSessions: CsSession[] = Array.from({ length: 20 }, (_, i) => ({
  sessionNo: `CS${9000 + i}`, userNo: `U${3000 + (i % 40)}`, agentName: p(["Sara Ahmed", "Noura K.", "客服机器人"], i),
  lastMessage: p(["好的，已为您处理退款", "请提供订单号", "问题已解决，感谢反馈", "正在为您查询…"], i),
  status: i % 3 === 0 ? "CLOSED" : "ACTIVE", updatedAt: iso(i * 1800_000),
}));

// —— 报表域 ——
export const reportDevices: ReportDevice[] = LOCS.map((loc, i) => ({
  locationName: loc, onlineRate: Number((0.88 + (i % 6) * 0.02).toFixed(2)),
  turnover: Number((1.5 + (i % 5) * 0.7).toFixed(1)), faultRate: Number((0.01 + (i % 5) * 0.008).toFixed(3)),
  cabinetCount: 3 + (i * 3) % 14,
}));
export const reportLocations: ReportLocation[] = Array.from({ length: 12 }, (_, i) => {
  const revenue = 10000 + (i * 1337) % 50000;
  const cost = 4000 + (i * 733) % 20000;
  return {
    siteName: p(LOCS, i), revenue, cost, payback: 90 + (i * 17) % 240,
    roi: Number(((revenue - cost) / cost).toFixed(2)), currency: "AED",
  };
});
export const reportFinances: ReportFinance[] = Array.from({ length: 12 }, (_, i) => {
  const gmv = 80000 + (i * 6337) % 120000;
  const share = Math.round(gmv * 0.35);
  const settle = Math.round(share * 0.9);
  return {
    period: `2026-${String(i + 1).padStart(2, "0")}`, gmv, share, settle, net: gmv - share, currency: "AED",
  };
});
export const reportScreens: ReportScreen[] = [
  { metric: "今日GMV", value: 4820, unit: "AED", trend: 0.12 },
  { metric: "今日订单", value: 386, unit: "单", trend: 0.08 },
  { metric: "在线柜机", value: 43, unit: "台", trend: -0.02 },
  { metric: "在线率", value: 92, unit: "%", trend: 0.01 },
  { metric: "借出中充电宝", value: 218, unit: "个", trend: 0.05 },
  { metric: "待处理工单", value: 12, unit: "单", trend: -0.15 },
  { metric: "活跃用户", value: 1264, unit: "人", trend: 0.09 },
  { metric: "翻台率", value: 3.4, unit: "次/日", trend: 0.06 },
];
export const reportCustoms: ReportCustom[] = [
  { dim: "Dubai Mall L1", metric: "GMV", value: 42800 },
  { dim: "Mall of Emirates", metric: "GMV", value: 38600 },
  { dim: "DXB T3", metric: "GMV", value: 51200 },
  { dim: "Marina Walk", metric: "GMV", value: 22400 },
  { dim: "City Centre Deira", metric: "GMV", value: 19800 },
  { dim: "Yas Mall", metric: "GMV", value: 33500 },
  { dim: "Ibn Battuta", metric: "GMV", value: 27100 },
];

// —— 系统域 ——
export const notifyTemplates: NotifyTemplate[] = Array.from({ length: 14 }, (_, i) => ({
  templateNo: `NT${100 + i}`, name: p(["借出成功通知", "归还提醒", "扣费通知", "验证码", "工单派单通知", "提现结果", "营销推送"], i),
  channel: p(["SMS", "EMAIL", "PUSH", "WHATSAPP"] as const, i), lang: i % 2 === 0 ? "ar" : "en",
  status: i % 7 === 0 ? "DISABLED" : "ENABLED",
}));
export const dictEntries: DictEntry[] = Array.from({ length: 20 }, (_, i) => ({
  dictNo: `DC${1000 + i}`, group: p(["order_status", "wo_type", "scene_type", "pay_channel"], i),
  code: p(["IN_USE", "FAULT", "MALL", "NEARPAY", "SETTLED", "REFILL"], i),
  label: p(["使用中", "故障", "商场", "NearPay", "已结算", "补货"], i), sort: i + 1, enabled: i % 9 !== 0,
}));
export const regions: Region[] = [
  { regionId: "AE", name: "阿联酋", parent: "-", level: 1, cityCount: 7 },
  { regionId: "AE-DU", name: "迪拜", parent: "阿联酋", level: 2, cityCount: 1 },
  { regionId: "AE-AZ", name: "阿布扎比", parent: "阿联酋", level: 2, cityCount: 1 },
  { regionId: "AE-SH", name: "沙迦", parent: "阿联酋", level: 2, cityCount: 1 },
  { regionId: "DU-MAR", name: "Dubai Marina", parent: "迪拜", level: 3, cityCount: 0 },
  { regionId: "DU-DEI", name: "Deira", parent: "迪拜", level: 3, cityCount: 0 },
  { regionId: "DU-DT", name: "Downtown Dubai", parent: "迪拜", level: 3, cityCount: 0 },
  { regionId: "DU-DXB", name: "DXB 机场", parent: "迪拜", level: 3, cityCount: 0 },
  { regionId: "AZ-YAS", name: "Yas Island", parent: "阿布扎比", level: 3, cityCount: 0 },
  { regionId: "AZ-COR", name: "Corniche", parent: "阿布扎比", level: 3, cityCount: 0 },
  { regionId: "SH-CIT", name: "Sharjah City", parent: "沙迦", level: 3, cityCount: 0 },
  { regionId: "AE-AJ", name: "阿治曼", parent: "阿联酋", level: 2, cityCount: 1 },
];
export const sysParams: SysParam[] = [
  { paramKey: "deposit.default", label: "默认押金", value: "50", groupName: "计费", updatedAt: iso(0) },
  { paramKey: "free.minutes", label: "免费时长(分钟)", value: "5", groupName: "计费", updatedAt: iso(86400_000) },
  { paramKey: "cap.daily", label: "每日封顶", value: "30", groupName: "计费", updatedAt: iso(2 * 86400_000) },
  { paramKey: "cap.total", label: "买断价", value: "60", groupName: "计费", updatedAt: iso(3 * 86400_000) },
  { paramKey: "currency", label: "结算币种", value: "AED", groupName: "全局", updatedAt: iso(4 * 86400_000) },
  { paramKey: "pay.provider", label: "支付通道", value: "nearpay", groupName: "支付", updatedAt: iso(5 * 86400_000) },
  { paramKey: "vat.rate", label: "增值税率", value: "0.05", groupName: "财务", updatedAt: iso(6 * 86400_000) },
  { paramKey: "sla.response", label: "默认响应时限(分钟)", value: "30", groupName: "工单", updatedAt: iso(7 * 86400_000) },
  { paramKey: "heartbeat.timeout", label: "心跳超时(秒)", value: "180", groupName: "设备", updatedAt: iso(8 * 86400_000) },
  { paramKey: "ota.strategy", label: "默认升级策略", value: "GRAY", groupName: "设备", updatedAt: iso(9 * 86400_000) },
  { paramKey: "lang.default", label: "默认语言", value: "ar", groupName: "全局", updatedAt: iso(10 * 86400_000) },
  { paramKey: "map.provider", label: "地图服务", value: "google", groupName: "全局", updatedAt: iso(11 * 86400_000) },
  { paramKey: "sms.provider", label: "短信通道", value: "unifonic", groupName: "通知", updatedAt: iso(12 * 86400_000) },
  { paramKey: "whatsapp.enabled", label: "WhatsApp通知", value: "true", groupName: "通知", updatedAt: iso(13 * 86400_000) },
  { paramKey: "credit.min", label: "最低信用分", value: "550", groupName: "风控", updatedAt: iso(14 * 86400_000) },
  { paramKey: "invite.reward", label: "邀请奖励(AED)", value: "5", groupName: "营销", updatedAt: iso(15 * 86400_000) },
];
export const openApiApps: OpenApiApp[] = Array.from({ length: 12 }, (_, i) => ({
  appNo: `APP${300 + i}`, name: p(["Careem 集成", "Noon 广告平台", "Emirates NBD 支付", "第三方BI", "场地方门户", "代理商开放平台"], i),
  appKey: `ak_${String(1000000000 + i * 7654321).slice(0, 10)}`, rateLimit: p([100, 300, 500, 1000], i),
  status: i % 5 === 0 ? "DISABLED" : "ACTIVE", createdAt: iso(i * 172800_000),
}));

// —— PDF 对照新增 mock（P2/P3）——
const DEP_STATUS: DepositRecord["status"][] = ["HELD", "RELEASED", "BOUGHT_OUT", "ARREARS"];
export const depositRecords: DepositRecord[] = Array.from({ length: 26 }, (_, i) => {
  const status = p(DEP_STATUS, i);
  return {
    depositNo: `DEP${900 + i}`, orderNo: `ORD${10500 + i}`, userNo: `U${1000 + (i % 18)}`,
    amount: p([49, 99, 99, 199], i), currency: "AED", status,
    arrearsAmount: status === "ARREARS" ? p([12, 25, 40, 8], i) : 0, createdAt: iso(i * 43200_000),
  };
});
export const marketCountries: MarketCountry[] = [
  { countryCode: "AE", name: "阿联酋", currency: "AED", timezone: "Asia/Dubai", compliance: "Neargo FZ-LLC", cityCount: 5, status: "LIVE" },
  { countryCode: "SA", name: "沙特", currency: "SAR", timezone: "Asia/Riyadh", compliance: "筹备中", cityCount: 2, status: "PILOT" },
  { countryCode: "QA", name: "卡塔尔", currency: "QAR", timezone: "Asia/Qatar", compliance: "规划", cityCount: 0, status: "PLANNED" },
  { countryCode: "KW", name: "科威特", currency: "KWD", timezone: "Asia/Kuwait", compliance: "规划", cityCount: 0, status: "PLANNED" },
  { countryCode: "EG", name: "埃及", currency: "EGP", timezone: "Africa/Cairo", compliance: "规划", cityCount: 0, status: "PLANNED" },
];
export const consumerSegments: ConsumerSegment[] = [
  { segmentNo: "SEG901", segment: "高频通勤用户", userCount: 3820, repeatRate: 0.62, avgOrderValue: 6.4, currency: "AED" },
  { segmentNo: "SEG902", segment: "机场/差旅人群", userCount: 2140, repeatRate: 0.31, avgOrderValue: 12.8, currency: "AED" },
  { segmentNo: "SEG903", segment: "商场休闲用户", userCount: 5670, repeatRate: 0.44, avgOrderValue: 5.1, currency: "AED" },
  { segmentNo: "SEG904", segment: "医院/景区场景", userCount: 1290, repeatRate: 0.27, avgOrderValue: 9.3, currency: "AED" },
  { segmentNo: "SEG905", segment: "新客(30日内)", userCount: 4410, repeatRate: 0.18, avgOrderValue: 4.7, currency: "AED" },
  { segmentNo: "SEG906", segment: "会员/次卡用户", userCount: 980, repeatRate: 0.71, avgOrderValue: 7.9, currency: "AED" },
];

// ============================================================================
// 分页 list 函数（签名统一：q?: PageQuery → PageResult<T>）
// ============================================================================
export const listPowerbanks = (q: PageQuery = {}) => paginate(powerbanks, q.page, q.size, (x) => kwHit(q.keyword, x.powerbankNo, x.cabinetNo));
export const listCabinetMonitor = (q: PageQuery = {}) => paginate(cabinetMonitors, q.page, q.size, (x) => kwHit(q.keyword, x.cabinetNo, x.locationName));
export const listCommandRecords = (q: PageQuery = {}) => paginate(commandRecords, q.page, q.size, (x) => kwHit(q.keyword, x.commandId, x.cabinetNo, x.operator));
export const listInventoryTransfers = (q: PageQuery = {}) => paginate(inventoryTransfers, q.page, q.size, (x) => kwHit(q.keyword, x.transferNo, x.fromLocation, x.toLocation));
export const listOtaRollouts = (q: PageQuery = {}) => paginate(otaRollouts, q.page, q.size, (x) => kwHit(q.keyword, x.rolloutNo, x.fwVersion, x.vendorCode));
export const listSlaRules = (q: PageQuery = {}) => paginate(slaRules, q.page, q.size, (x) => kwHit(q.keyword, x.slaNo, x.woType, x.escalateTo));
export const listInspectionPlans = (q: PageQuery = {}) => paginate(inspectionPlans, q.page, q.size, (x) => kwHit(q.keyword, x.planNo, x.route, x.assignee));
export const listLeads = (q: PageQuery = {}) => paginate(leads, q.page, q.size, (x) => kwHit(q.keyword, x.leadNo, x.venueName, x.owner));
export const listSiteAnalysis = (q: PageQuery = {}) => paginate(siteAnalyses, q.page, q.size, (x) => kwHit(q.keyword, x.siteNo, x.siteName));
export const listAgentAssignments = (q: PageQuery = {}) => paginate(agentAssignments, q.page, q.size, (x) => kwHit(q.keyword, x.agentNo, x.agentName, x.region));
export const listAgentPerformance = (q: PageQuery = {}) => paginate(agentPerformances, q.page, q.size, (x) => kwHit(q.keyword, x.agentNo, x.agentName));
export const listAgentAccounts = (q: PageQuery = {}) => paginate(agentAccounts, q.page, q.size, (x) => kwHit(q.keyword, x.accountNo, x.agentNo, x.agentName, x.loginPhone));
export const listOrderExceptions = (q: PageQuery = {}) => paginate(orderExceptions, q.page, q.size, (x) => kwHit(q.keyword, x.orderNo, x.cabinetNo, x.userNo));
export const listPricingDiffs = (q: PageQuery = {}) => paginate(pricingDiffs, q.page, q.size, (x) => kwHit(q.keyword, x.ruleNo, x.scene, x.locationName));
export const listPricingSchedules = (q: PageQuery = {}) => paginate(pricingSchedules, q.page, q.size, (x) => kwHit(q.keyword, x.ruleNo, x.name, x.period));
export const listShareRecords = (q: PageQuery = {}) => paginate(shareRecords, q.page, q.size, (x) => kwHit(q.keyword, x.recordNo, x.orderNo, x.payeeName));
export const listReconciles = (q: PageQuery = {}) => paginate(reconciles, q.page, q.size, (x) => kwHit(q.keyword, x.batchNo, x.period));
export const listInvoices = (q: PageQuery = {}) => paginate(invoices, q.page, q.size, (x) => kwHit(q.keyword, x.invoiceNo, x.payeeName, x.vatTrn));
export const listMembers = (q: PageQuery = {}) => paginate(members, q.page, q.size, (x) => kwHit(q.keyword, x.userNo, x.nickname));
export const listWallets = (q: PageQuery = {}) => paginate(wallets, q.page, q.size, (x) => kwHit(q.keyword, x.userNo, x.nickname));
export const listEmployees = (q: PageQuery = {}) => paginate(employees, q.page, q.size, (x) => kwHit(q.keyword, x.employeeNo, x.name, x.phone, x.email));
export const listCampaigns = (q: PageQuery = {}) => paginate(campaigns, q.page, q.size, (x) => kwHit(q.keyword, x.campaignNo, x.name, x.kind));
export const listPushMessages = (q: PageQuery = {}) => paginate(pushMessages, q.page, q.size, (x) => kwHit(q.keyword, x.pushNo, x.title, x.audience));
export const listReferrals = (q: PageQuery = {}) => paginate(referrals, q.page, q.size, (x) => kwHit(q.keyword, x.inviteNo, x.inviter, x.invitee));
export const listAdSlots = (q: PageQuery = {}) => paginate(adSlots, q.page, q.size, (x) => kwHit(q.keyword, x.slotNo, x.cabinetNo));
export const listAdCampaigns = (q: PageQuery = {}) => paginate(adCampaigns, q.page, q.size, (x) => kwHit(q.keyword, x.adNo, x.advertiser, x.creative));
export const listAdDeliveries = (q: PageQuery = {}) => paginate(adDeliveries, q.page, q.size, (x) => kwHit(q.keyword, x.deliveryNo, x.adNo, x.slotNo));
export const listDepartments = (q: PageQuery = {}) => paginate(departments, q.page, q.size, (x) => kwHit(q.keyword, x.deptNo, x.name, x.leader));
export const listStaffPerformance = (q: PageQuery = {}) => paginate(staffPerformances, q.page, q.size, (x) => kwHit(q.keyword, x.employeeNo, x.name, x.role));
export const listCsTickets = (q: PageQuery = {}) => paginate(csTickets, q.page, q.size, (x) => kwHit(q.keyword, x.ticketNo, x.userNo, x.cabinetNo, x.issue));
export const listCsSessions = (q: PageQuery = {}) => paginate(csSessions, q.page, q.size, (x) => kwHit(q.keyword, x.sessionNo, x.userNo, x.agentName));
export const listReportDevice = (q: PageQuery = {}) => paginate(reportDevices, q.page, q.size, (x) => kwHit(q.keyword, x.locationName));
export const listReportLocation = (q: PageQuery = {}) => paginate(reportLocations, q.page, q.size, (x) => kwHit(q.keyword, x.siteName));
export const listReportFinance = (q: PageQuery = {}) => paginate(reportFinances, q.page, q.size, (x) => kwHit(q.keyword, x.period));
export const listReportScreen = (q: PageQuery = {}) => paginate(reportScreens, q.page, q.size, (x) => kwHit(q.keyword, x.metric));
export const listReportCustom = (q: PageQuery = {}) => paginate(reportCustoms, q.page, q.size, (x) => kwHit(q.keyword, x.dim, x.metric));
export const listNotifyTemplates = (q: PageQuery = {}) => paginate(notifyTemplates, q.page, q.size, (x) => kwHit(q.keyword, x.templateNo, x.name));
export const listDictEntries = (q: PageQuery = {}) => paginate(dictEntries, q.page, q.size, (x) => kwHit(q.keyword, x.dictNo, x.group, x.code, x.label));
export const listRegions = (q: PageQuery = {}) => paginate(regions, q.page, q.size, (x) => kwHit(q.keyword, x.regionId, x.name, x.parent));
export const listSysParams = (q: PageQuery = {}) => paginate(sysParams, q.page, q.size, (x) => kwHit(q.keyword, x.paramKey, x.label, x.groupName));
export const listOpenApiApps = (q: PageQuery = {}) => paginate(openApiApps, q.page, q.size, (x) => kwHit(q.keyword, x.appNo, x.name, x.appKey));
export const listDepositRecords = (q: PageQuery = {}) => paginate(depositRecords, q.page, q.size, (x) => kwHit(q.keyword, x.depositNo, x.orderNo, x.userNo));
export const listMarketCountries = (q: PageQuery = {}) => paginate(marketCountries, q.page, q.size, (x) => kwHit(q.keyword, x.countryCode, x.name, x.currency));
export const listConsumerSegments = (q: PageQuery = {}) => paginate(consumerSegments, q.page, q.size, (x) => kwHit(q.keyword, x.segmentNo, x.segment));
export const listUserRisks = (q: PageQuery = {}) => paginate(userRisks, q.page, q.size, (x) => kwHit(q.keyword, x.riskNo, x.userNo, x.nickname, x.phone));
export const listUserBlacklist = (q: PageQuery = {}) => paginate(userBlacklist, q.page, q.size, (x) => kwHit(q.keyword, x.blacklistNo, x.userNo, x.nickname));
export const listAgentCommissions = (q: PageQuery = {}) => paginate(agentCommissions, q.page, q.size, (x) => kwHit(q.keyword, x.ruleNo, x.agentNo, x.agentName));
export const listVenueOnboardings = (q: PageQuery = {}) => paginate(venueOnboardings, q.page, q.size, (x) => kwHit(q.keyword, x.onboardingNo, x.venueName, x.contact));
export const listSiteLifecycles = (q: PageQuery = {}) => paginate(siteLifecycles, q.page, q.size, (x) => kwHit(q.keyword, x.siteNo, x.siteName, x.owner));
export const saveAgentCommission = (x: Partial<AgentCommission>) => upsert(agentCommissions, x, "ruleNo", () => nextNo("AC", agentCommissions));
export const saveVenueOnboarding = (x: Partial<VenueOnboarding>) => upsert(venueOnboardings, x, "onboardingNo", () => nextNo("OB", venueOnboardings));

// ============================================================================
// mock save 函数（新增/编辑：复用 upsert + nextNo，前缀与各域现有编号一致）
// ============================================================================
export const savePowerbank = (x: Partial<Powerbank>) => upsert(powerbanks, x, "powerbankNo", () => nextNo("PB", powerbanks));
export const saveInventoryTransfer = (x: Partial<InventoryTransfer>) => upsert(inventoryTransfers, x, "transferNo", () => nextNo("TR", inventoryTransfers));
export const saveOtaRollout = (x: Partial<OtaRollout>) => upsert(otaRollouts, x, "rolloutNo", () => nextNo("OTA", otaRollouts));
export const saveSlaRule = (x: Partial<SlaRule>) => upsert(slaRules, x, "slaNo", () => nextNo("SLA", slaRules));
export const saveInspectionPlan = (x: Partial<InspectionPlan>) => upsert(inspectionPlans, x, "planNo", () => nextNo("IP", inspectionPlans));
export const saveLead = (x: Partial<Lead>) => upsert(leads, x, "leadNo", () => nextNo("LD", leads));
export const saveVenue = (x: Partial<Venue>) => upsert(venues, x, "venueNo", () => nextNo("VEN", venues));
export const saveContract = (x: Partial<Contract>) => upsert(contracts, x, "contractNo", () => nextNo("CT", contracts));
export const saveAgentAccount = (x: Partial<AgentAccount>) => upsert(agentAccounts, x, "accountNo", () => nextNo("AA", agentAccounts));
export const savePricePlan = (x: Partial<PricePlan>) => upsert(pricePlans, x, "planNo", () => nextNo("PP", pricePlans));
export const savePricingDiff = (x: Partial<PricingDiff>) => upsert(pricingDiffs, x, "ruleNo", () => nextNo("PD", pricingDiffs));
export const savePricingSchedule = (x: Partial<PricingSchedule>) => upsert(pricingSchedules, x, "ruleNo", () => nextNo("PS", pricingSchedules));
export const saveShareRule = (x: Partial<ShareRule>) => upsert(shareRules, x, "ruleNo", () => nextNo("SR", shareRules));
export const saveInvoice = (x: Partial<Invoice>) => upsert(invoices, x, "invoiceNo", () => nextNo("INV", invoices));
export const saveMember = (x: Partial<Member>) => upsert(members, x, "userNo", () => nextNo("U", members));
export const saveWallet = (x: Partial<Wallet>) => upsert(wallets, x, "userNo", () => nextNo("U", wallets));
export const saveCampaign = (x: Partial<Campaign>) => upsert(campaigns, x, "campaignNo", () => nextNo("CMP", campaigns));
export const savePushMessage = (x: Partial<PushMessage>) => upsert(pushMessages, x, "pushNo", () => nextNo("PM", pushMessages));
export const saveAdSlot = (x: Partial<AdSlot>) => upsert(adSlots, x, "slotNo", () => nextNo("AS", adSlots));
export const saveAdCampaign = (x: Partial<AdCampaign>) => upsert(adCampaigns, x, "adNo", () => nextNo("AD", adCampaigns));
export const saveCsTicket = (x: Partial<CsTicket>) => upsert(csTickets, x, "ticketNo", () => nextNo("TK", csTickets));
export const saveDepartment = (x: Partial<Department>) => upsert(departments, x, "deptNo", () => nextNo("D", departments));
export const saveRoleRow = (x: Partial<RoleRow>) => upsert(roles, x, "roleNo", () => nextNo("R", roles));
export const saveNotifyTemplate = (x: Partial<NotifyTemplate>) => upsert(notifyTemplates, x, "templateNo", () => nextNo("NT", notifyTemplates));
export const saveDictEntry = (x: Partial<DictEntry>) => upsert(dictEntries, x, "dictNo", () => nextNo("DC", dictEntries));
export const saveRegion = (x: Partial<Region>) => upsert(regions, x, "regionId", () => nextNo("REG", regions));
export const saveSysParam = (x: Partial<SysParam>) => upsert(sysParams, x, "paramKey", () => nextNo("param.", sysParams));
export const saveOpenApiApp = (x: Partial<OpenApiApp>) => upsert(openApiApps, x, "appNo", () => nextNo("APP", openApiApps));
export const saveEmployee = (x: Partial<Employee>) => upsert(employees, x, "employeeNo", () => nextNo("E", employees, 100));
// 多国家市场：主键是 ISO alpha-2 国家码，由表单必填（不自动生成编号）
export const saveMarketCountry = (x: Partial<MarketCountry>) =>
  upsert(marketCountries, x, "countryCode", () => nextNo("XX", marketCountries, 0));

/** 提现审批（mock）：通过→PAYING，驳回→FAILED 并记原因；两者都落审批人/审批时间。 */
export function auditWithdrawal(withdrawNo: string, approve: boolean, rejectReason?: string, auditorName?: string): Withdrawal {
  const w = withdrawals.find((x) => x.withdrawNo === withdrawNo)!;
  w.auditorName = auditorName || "admin";
  w.auditedAt = new Date().toISOString();
  if (approve) {
    w.status = "PAYING";
    w.rejectReason = null;
  } else {
    w.status = "FAILED";
    w.rejectReason = rejectReason ?? "";
  }
  return w;
}

// ============================================================================
// 告警域（对标简电云 A1~A4）：代码字典 / 通知规则 / 告警记录 / 通知流水
// 关键：alarmCode（平台统一码）与 vendorErrorCode（厂商原始码）双列 —— 多厂商错误码归一化。
// ============================================================================
export const alarmCodes: AlarmCode[] = [
  { code: "OFFLINE", message: "柜机离线", level: "CRITICAL", suggestion: "检查网络与供电；10 分钟未恢复派现场工单", autoWorkOrder: true },
  { code: "SLOT_STUCK", message: "卡槽卡宝", level: "CRITICAL", suggestion: "远程弹仓一次；仍失败则锁槽并派维修", autoWorkOrder: true },
  { code: "LOCK_FAIL", message: "锁扣异常", level: "CRITICAL", suggestion: "锁槽止损，安排更换锁扣模块", autoWorkOrder: true },
  { code: "TEMP_HIGH", message: "机内温度过高", level: "CRITICAL", suggestion: "降功率并现场检查散热风道", autoWorkOrder: true },
  { code: "EJECT_TIMEOUT", message: "弹出超时", level: "WARN", suggestion: "复核指令回执；连续 3 次转维修工单", autoWorkOrder: true },
  { code: "BATTERY_LOW", message: "充电宝电量过低", level: "WARN", suggestion: "纳入下次补货路线，优先换宝", autoWorkOrder: false },
  { code: "SIGNAL_WEAK", message: "通信信号弱", level: "WARN", suggestion: "确认 4G 信号与天线位置，必要时挪机位", autoWorkOrder: false },
  { code: "HEARTBEAT_LOST", message: "心跳丢失", level: "WARN", suggestion: "观察 5 分钟；未恢复升级为 OFFLINE", autoWorkOrder: false },
  { code: "FW_UPGRADE_FAIL", message: "固件升级失败", level: "INFO", suggestion: "回滚上一版本，纳入下一批灰度", autoWorkOrder: false },
  { code: "SCREEN_FAULT", message: "广告屏异常", level: "INFO", suggestion: "不影响借还；并入巡检批量处理", autoWorkOrder: false },
];

// 厂商原始错误码风格各不相同：cd-tech=E2xx，sd-power=ERR-nn，chargenow=0x1Fxx
const vendorErr = (vendorCode: string, i: number) =>
  vendorCode === "cd-tech" ? `E${200 + (i % 40)}`
  : vendorCode === "sd-power" ? `ERR-${10 + (i % 30)}`
  : `0x1F${String(i % 100).padStart(2, "0")}`;

export const alarmRecords: AlarmRecord[] = Array.from({ length: 14 }, (_, i) => {
  const def = p(alarmCodes, i);
  const cab = p(cabinets, i * 3);
  const st = p(["OPEN", "OPEN", "ACKED", "CLOSED"] as const, i);
  return {
    alarmNo: `ALM${40000 + i}`, cabinetNo: cab.cabinetNo, siteName: cab.locationName ?? p(LOCS, i),
    vendorCode: cab.vendorCode, alarmCode: def.code, vendorErrorCode: vendorErr(cab.vendorCode, i),
    level: def.level, occurredAt: iso(i * 5400_000), status: st,
    workOrderNo: st === "OPEN" ? null : `WO${70000 + (i % 64)}`,
    remark: p(["心跳超时 10 分钟未恢复", "用户反馈取宝失败", "巡检现场发现", "厂商云回调上报", "监控脚本自动触发"], i),
  };
});

export const alarmNotices: AlarmNotice[] = Array.from({ length: 12 }, (_, i) => {
  const ch = p(["SMS", "EMAIL", "PUSH", "WEBHOOK"] as const, i);
  const failed = i % 5 === 4;
  return {
    noticeNo: `AN${50000 + i}`, alarmNo: p(alarmRecords, i).alarmNo, channel: ch,
    target: ch === "SMS" ? phone(i)
      : ch === "EMAIL" ? p(["ops@sharehub.ae", "ops-dubai@sharehub.ae", "support@sharehub.ae"], i)
      : ch === "PUSH" ? p(OPERATORS, i)
      : "https://hooks.sharehub.ae/alarm",
    sentAt: iso(i * 3600_000), status: failed ? "FAILED" : "SENT",
    failReason: failed ? p(["短信网关超时", "目标号码停机", "Webhook 返回 500"], i) : null,
  };
});

export const alarmRules: AlarmRule[] = Array.from({ length: 10 }, (_, i) => {
  const def = p(alarmCodes, i);
  const critical = def.level === "CRITICAL";
  return {
    ruleNo: `AR${600 + i}`, alarmCode: def.code,
    target: p(["运维值班组", "区域经理", "厂商对接人", "客服一线", "运营总监"], i),
    channel: p(["SMS", "PUSH", "EMAIL", "WEBHOOK"] as const, i),
    method: critical ? "INSTANT" : p(["INSTANT", "DIGEST"] as const, i),
    // 严重告警不设静默窗口（必须随时触达）；其余夜间静默，防轰炸
    quietStart: critical ? "" : "22:00", quietEnd: critical ? "" : "08:00",
    escalateMinutes: critical ? p([15, 30], i) : def.level === "WARN" ? 60 : 0,
    status: i % 7 === 0 ? "INACTIVE" : "ACTIVE",
  };
});

export const listAlarmRecords = (q: PageQuery & { level?: string; status?: string } = {}) =>
  paginate(alarmRecords, q.page, q.size, (x) =>
    kwHit(q.keyword, x.alarmNo, x.cabinetNo, x.siteName, x.alarmCode, x.vendorErrorCode, x.workOrderNo) &&
    (!q.level || x.level === q.level) && (!q.status || x.status === q.status));
export const listAlarmNotices = (q: PageQuery = {}) => paginate(alarmNotices, q.page, q.size, (x) => kwHit(q.keyword, x.noticeNo, x.alarmNo, x.target));
export const listAlarmCodes = (q: PageQuery = {}) => paginate(alarmCodes, q.page, q.size, (x) => kwHit(q.keyword, x.code, x.message, x.suggestion));
export const listAlarmRules = (q: PageQuery = {}) => paginate(alarmRules, q.page, q.size, (x) => kwHit(q.keyword, x.ruleNo, x.alarmCode, x.target));
export const saveAlarmCode = (x: Partial<AlarmCode>) => upsert(alarmCodes, x, "code", () => nextNo("ALARM_CODE_", alarmCodes, 1));
export const saveAlarmRule = (x: Partial<AlarmRule>) => upsert(alarmRules, x, "ruleNo", () => nextNo("AR", alarmRules, 600));

/** 告警转工单（mock）：生成关联工单号并置为已受理；已转过的沿用原工单号（幂等）。 */
export function raiseAlarmWorkOrder(alarmNo: string): AlarmRecord {
  const a = alarmRecords.find((x) => x.alarmNo === alarmNo)!;
  a.workOrderNo = a.workOrderNo ?? nextNo("WO", alarmRecords.filter((x) => x.workOrderNo), 70200);
  a.status = "ACKED";
  return a;
}

// ============================================================================
// 售后处置（对标简电云 B1/B2）：投诉订单 / 退款记录
// 关键：投诉可转工单（投诉-订单-工单串通）；退款走独立审批队列并带幂等键 + PSP 流水号。
// orderNo / userNo 一律取自上面的 orders mock，保证列表间可互相搜到同一单。
// ============================================================================
const COMPLAINT_TYPES: ComplaintIssueType[] = ["BILLING_DISPUTE", "NOT_EJECTED", "NOT_RETURNED", "DEVICE_FAULT", "OTHER"];
const COMPLAINT_DESC: Record<ComplaintIssueType, string> = {
  BILLING_DISPUTE: "只借了 20 分钟却按 2 小时计费，要求核对账单",
  NOT_EJECTED: "扫码后柜机没有弹出充电宝，但订单已生成并开始计费",
  NOT_RETURNED: "已经把充电宝插回柜机，App 仍显示租借中",
  DEVICE_FAULT: "借到的充电宝无法充电，接口松动",
  OTHER: "机器屏幕不亮，现场无人可协助",
};
const COMPLAINT_RESOLUTIONS: ComplaintResolution[] = ["REFUND", "COMPENSATE", "REJECT", "EXPLAINED"];

export const orderComplaints: OrderComplaint[] = Array.from({ length: 12 }, (_, i) => {
  const o = p(orders, i * 7);
  const type = p(COMPLAINT_TYPES, i);
  const st = p(["PENDING", "PENDING", "PROCESSING", "RESOLVED", "RESOLVED", "REJECTED"] as const, i);
  const done = st === "RESOLVED" || st === "REJECTED";
  return {
    complaintNo: `CPL${60000 + i}`, orderNo: o.orderNo, userNo: o.cUserNo,
    issueType: type, description: COMPLAINT_DESC[type],
    // mock 截图统一走占位图服务，真实实现替换为对象存储签名 URL
    screenshotUrl: i % 4 === 3 ? null : `https://placehold.co/720x1280?text=CPL${60000 + i}`,
    submittedAt: iso(i * 7200_000), status: st,
    handlerName: st === "PENDING" ? null : p(OPERATORS, i + 1),
    handledAt: done ? iso(i * 7200_000 - 1800_000) : null,
    resolution: done ? (st === "REJECTED" ? "REJECT" : p(COMPLAINT_RESOLUTIONS, i)) : null,
    resolutionNote: done ? p(["已按实际时长重算并退差额", "补发 10 AED 优惠券作为补偿", "核对后计费无误，已向用户解释", "已远程弹仓并确认归还成功"], i) : "",
    // 设备类投诉默认已转工单：现场问题必须落到运维手上
    workOrderNo: type === "DEVICE_FAULT" || type === "NOT_EJECTED" ? `WO${70000 + (i % 64)}` : null,
  };
});

const REFUND_REASONS = ["计费争议，按实际时长重算", "未弹出充电宝，全额退回", "重复扣款", "设备故障导致无法使用", "超时买断后找回设备"];

export const refundRecords: RefundRecord[] = Array.from({ length: 13 }, (_, i) => {
  const o = p(orders, i * 5 + 2);
  const st = p(["PENDING", "PENDING", "APPROVED", "EXECUTED", "EXECUTED", "REJECTED", "FAILED"] as const, i);
  const audited = st !== "PENDING";
  const executed = st === "EXECUTED" || st === "FAILED";
  return {
    refundNo: `RFD${80000 + i}`, orderNo: o.orderNo, userNo: o.cUserNo,
    amount: Number((o.feeAmount > 0 ? o.feeAmount : 12 + (i % 5) * 3).toFixed(2)), currency: "AED",
    reason: p(REFUND_REASONS, i), applicantName: p(OPERATORS, i), appliedAt: iso(i * 10800_000),
    status: st,
    auditorName: audited ? p(["Sara Ahmed", "Omar Khan", "admin"], i) : null,
    auditedAt: audited ? iso(i * 10800_000 - 3600_000) : null,
    rejectReason: st === "REJECTED" ? p(["订单计费无误，用户已确认", "超出退款申请时效", "同一订单已退款，重复提交"], i) : null,
    // 幂等键 = 订单号 + 申请序号：同一订单重复申请只会落到同一笔退款
    idempotencyKey: `RF-${o.orderNo}-${String(i % 3)}`,
    psgTxnNo: executed ? `PSP${202607000000 + i * 137}` : null,
  };
});

export const listOrderComplaints = (q: PageQuery & { status?: string } = {}) =>
  paginate(orderComplaints, q.page, q.size, (x) =>
    kwHit(q.keyword, x.complaintNo, x.orderNo, x.userNo, x.description, x.handlerName, x.workOrderNo) &&
    (!q.status || x.status === q.status));
export const listRefundRecords = (q: PageQuery & { status?: string } = {}) =>
  paginate(refundRecords, q.page, q.size, (x) =>
    kwHit(q.keyword, x.refundNo, x.orderNo, x.userNo, x.applicantName, x.auditorName, x.psgTxnNo, x.idempotencyKey) &&
    (!q.status || x.status === q.status));

export const saveOrderComplaint = (x: Partial<OrderComplaint>) =>
  upsert(orderComplaints, x, "complaintNo", () => nextNo("CPL", orderComplaints, 60000));

/** 处理投诉（mock）：写入处理结果/说明/处理人/处理时间；驳回落 REJECTED，其余落 RESOLVED。 */
export function handleOrderComplaint(complaintNo: string, resolution: ComplaintResolution, note: string): OrderComplaint {
  const c = orderComplaints.find((x) => x.complaintNo === complaintNo)!;
  c.resolution = resolution;
  c.resolutionNote = note;
  c.status = resolution === "REJECT" ? "REJECTED" : "RESOLVED";
  c.handlerName = "admin";
  c.handledAt = new Date().toISOString();
  return c;
}

/** 投诉转工单（mock）：生成关联工单号并置为处理中；已转过的沿用原工单号（幂等）。 */
export function raiseComplaintWorkOrder(complaintNo: string): OrderComplaint {
  const c = orderComplaints.find((x) => x.complaintNo === complaintNo)!;
  c.workOrderNo = c.workOrderNo ?? nextNo("WO", orderComplaints.filter((x) => x.workOrderNo), 70300);
  if (c.status === "PENDING") c.status = "PROCESSING";
  return c;
}

/** 退款申请（mock）：订单详情抽屉「申请退款」的落库入口，幂等键相同则复用既有申请。 */
export function applyRefund(orderNo: string, reason = "客服代客申请退款"): RefundRecord {
  const key = `RF-${orderNo}-manual`;
  const exist = refundRecords.find((x) => x.idempotencyKey === key);
  if (exist) return exist;
  const o = orders.find((x) => x.orderNo === orderNo);
  const created: RefundRecord = {
    refundNo: nextNo("RFD", refundRecords, 80000), orderNo,
    userNo: o?.cUserNo ?? "-", amount: o?.feeAmount ?? 0, currency: o?.currency ?? "AED",
    reason, applicantName: "admin", appliedAt: new Date().toISOString(), status: "PENDING",
    auditorName: null, auditedAt: null, rejectReason: null, idempotencyKey: key, psgTxnNo: null,
  };
  refundRecords.unshift(created);
  return created;
}

/** 退款审批（mock）：通过→APPROVED 并模拟 PSP 执行落 EXECUTED；驳回→REJECTED 并记原因。 */
export function auditRefund(refundNo: string, approve: boolean, rejectReason?: string): RefundRecord {
  const r = refundRecords.find((x) => x.refundNo === refundNo)!;
  r.auditorName = "admin";
  r.auditedAt = new Date().toISOString();
  if (approve) {
    r.status = "EXECUTED";
    r.rejectReason = null;
    r.psgTxnNo = r.psgTxnNo ?? `PSP${202607000000 + refundRecords.length * 137}`;
  } else {
    r.status = "REJECTED";
    r.rejectReason = rejectReason ?? "";
  }
  return r;
}

// ============================================================================
// 公告管理（营销域 · P1，对标简电云 E1）
// 三语（zh/en/ar）+ 生效期 + 置顶 —— 竞品公告只有单语，我们要覆盖 MENA 多语市场。
// ============================================================================
export const notices: Notice[] = [
  {
    noticeNo: "NTC900", title: "斋月期间机柜服务时间调整",
    titleEn: "Ramadan service hours update", titleAr: "تحديث ساعات الخدمة خلال رمضان",
    content: "斋月期间，Dubai Mall、Mall of Emirates 等商场点位服务至次日 02:00，归还不受影响。",
    contentEn: "During Ramadan, stations in Dubai Mall and Mall of Emirates stay open until 02:00. Returns are unaffected.",
    contentAr: "خلال رمضان، تعمل المحطات في دبي مول ومول الإمارات حتى الساعة 02:00. الإرجاع غير متأثر.",
    type: "SYSTEM", pinned: true, startAt: iso(3 * 86400_000), endAt: iso(-27 * 86400_000),
    status: "PUBLISHED", publishedBy: "运营中心", createdAt: iso(4 * 86400_000),
  },
  {
    noticeNo: "NTC901", title: "新用户首借 30 分钟免费",
    titleEn: "First rental free for 30 minutes", titleAr: "أول استئجار مجاني لمدة 30 دقيقة",
    content: "新用户首次借出充电宝，前 30 分钟免费，自动抵扣无需领券。",
    contentEn: "New users get the first 30 minutes free on their first power bank rental. No coupon needed.",
    contentAr: "يحصل المستخدمون الجدد على أول 30 دقيقة مجانًا عند أول استئجار لشاحن متنقل، دون الحاجة إلى قسيمة.",
    type: "PROMO", pinned: true, startAt: iso(10 * 86400_000), endAt: iso(-20 * 86400_000),
    status: "PUBLISHED", publishedBy: "增长组", createdAt: iso(11 * 86400_000),
  },
  {
    noticeNo: "NTC902", title: "DXB T3 航站楼点位夜间维护",
    titleEn: "Overnight maintenance at DXB Terminal 3", titleAr: "صيانة ليلية في مطار دبي المبنى 3",
    content: "本周四 01:00-04:00 对 DXB T3 全部机柜进行固件升级，期间暂停借出，已借订单正常计费与归还。",
    contentEn: "All cabinets at DXB T3 will receive a firmware upgrade on Thursday 01:00-04:00. Rentals pause; ongoing orders bill and return as usual.",
    contentAr: "سيتم تحديث البرامج الثابتة لجميع الخزائن في المبنى 3 بمطار دبي يوم الخميس من 01:00 إلى 04:00. يتوقف الاستئجار مؤقتًا.",
    type: "MAINTENANCE", pinned: false, startAt: iso(1 * 86400_000), endAt: iso(-2 * 86400_000),
    status: "PUBLISHED", publishedBy: "运维值班组", createdAt: iso(2 * 86400_000),
  },
  {
    noticeNo: "NTC903", title: "押金规则更新：信用免押上线",
    titleEn: "Deposit update: credit-based deposit waiver", titleAr: "تحديث التأمين: الإعفاء بناءً على التقييم الائتماني",
    content: "信用分达标用户借出充电宝免收 AED 50 押金，逾期未还仍按原规则计费买断。",
    contentEn: "Users above the credit threshold rent without the AED 50 deposit. Overdue buy-out rules remain unchanged.",
    contentAr: "يمكن للمستخدمين ذوي التقييم الائتماني المرتفع الاستئجار دون تأمين 50 درهمًا. تبقى قواعد الشراء عند التأخير كما هي.",
    type: "SYSTEM", pinned: false, startAt: iso(20 * 86400_000), endAt: iso(-40 * 86400_000),
    status: "PUBLISHED", publishedBy: "产品组", createdAt: iso(21 * 86400_000),
  },
  {
    noticeNo: "NTC904", title: "Marina Walk 新增 12 个机柜点位",
    titleEn: "12 new stations live at Marina Walk", titleAr: "تشغيل 12 محطة جديدة في مارينا ووك",
    content: "Marina Walk 沿线新增 12 个机柜，扫码即可借还，缓解晚间排队。",
    contentEn: "12 new cabinets are live along Marina Walk. Scan to rent or return and skip the evening queue.",
    contentAr: "تم تشغيل 12 خزانة جديدة على امتداد مارينا ووك. امسح الرمز للاستئجار أو الإرجاع.",
    type: "PROMO", pinned: false, startAt: iso(6 * 86400_000), endAt: iso(-24 * 86400_000),
    status: "PUBLISHED", publishedBy: "拓展组", createdAt: iso(7 * 86400_000),
  },
  {
    noticeNo: "NTC905", title: "支付通道切换公告",
    titleEn: "Payment channel migration notice", titleAr: "إشعار بتغيير قناة الدفع",
    content: "自本月起结算通道切换至 NEARPAY，账单主体显示为 ShareHub FZ-LLC，退款周期缩短至 3 个工作日。",
    contentEn: "Settlement moves to NEARPAY this month. Statements show ShareHub FZ-LLC and refunds now take 3 business days.",
    contentAr: "تنتقل التسوية إلى NEARPAY هذا الشهر. تظهر الفواتير باسم ShareHub FZ-LLC وتستغرق المبالغ المستردة 3 أيام عمل.",
    type: "SYSTEM", pinned: false, startAt: iso(15 * 86400_000), endAt: iso(-15 * 86400_000),
    status: "PUBLISHED", publishedBy: "财务中心", createdAt: iso(16 * 86400_000),
  },
  {
    noticeNo: "NTC906", title: "Yas Mall 点位临时停用（商场装修）",
    titleEn: "Yas Mall stations temporarily offline", titleAr: "إيقاف مؤقت لمحطات ياس مول",
    content: "因商场装修，Yas Mall B1 层 4 台机柜临时停用，请前往 L1 层机柜归还。",
    contentEn: "Due to mall renovation, 4 cabinets on Yas Mall B1 are offline. Please return at the L1 cabinets.",
    contentAr: "بسبب أعمال التجديد، تم إيقاف 4 خزائن في الطابق B1 بياس مول. يرجى الإرجاع في خزائن الطابق L1.",
    type: "MAINTENANCE", pinned: false, startAt: iso(-1 * 86400_000), endAt: iso(-30 * 86400_000),
    status: "DRAFT", publishedBy: "区域经理", createdAt: iso(1 * 86400_000),
  },
  {
    noticeNo: "NTC907", title: "国庆双周充电福利（已结束）",
    titleEn: "UAE National Day charging offer (ended)", titleAr: "عرض اليوم الوطني للإمارات (منتهي)",
    content: "国庆期间每日首单封顶 AED 3，活动已于上月结束，感谢参与。",
    contentEn: "During National Day the first daily rental was capped at AED 3. The campaign ended last month.",
    contentAr: "خلال اليوم الوطني، كان الحد الأقصى لأول استئجار يوميًا 3 دراهم. انتهى العرض الشهر الماضي.",
    type: "PROMO", pinned: false, startAt: iso(60 * 86400_000), endAt: iso(45 * 86400_000),
    status: "OFFLINE", publishedBy: "增长组", createdAt: iso(62 * 86400_000),
  },
  {
    noticeNo: "NTC908", title: "客服热线与 WhatsApp 支持时间",
    titleEn: "Support hotline and WhatsApp hours", titleAr: "أوقات الدعم عبر الهاتف وواتساب",
    content: "客服热线 09:00-23:00（GST），WhatsApp 全天留言，超时未归还请先在 App 内提交申诉。",
    contentEn: "Hotline 09:00-23:00 GST; WhatsApp accepts messages 24/7. For overdue returns, file a claim in the app first.",
    contentAr: "الخط الساخن من 09:00 إلى 23:00 بتوقيت الخليج، وواتساب متاح على مدار الساعة. للإرجاع المتأخر، قدّم شكوى عبر التطبيق.",
    type: "SYSTEM", pinned: false, startAt: iso(30 * 86400_000), endAt: iso(-60 * 86400_000),
    status: "PUBLISHED", publishedBy: "客服中心", createdAt: iso(31 * 86400_000),
  },
];

export const listNotices = (q: PageQuery = {}) =>
  paginate(notices, q.page, q.size, (x) => kwHit(q.keyword, x.noticeNo, x.title, x.titleEn, x.titleAr, x.publishedBy));
export const saveNotice = (x: Partial<Notice>) => upsert(notices, x, "noticeNo", () => nextNo("NTC", notices));

// ============================================================================
// 支付渠道（系统域 · P1，对标简电云 E8）
// 一页承载渠道列表 + 配置抽屉：NEARPAY 为当前主通道，其余为未来可插拔占位。
// 注意：mock 不写任何真实密钥，一律占位掩码。
// ============================================================================
export const paymentChannels: PaymentChannel[] = [
  {
    channelCode: "NEARPAY", channelName: "NearPay（聚合收单）", mode: "DELEGATED", status: "ENABLED",
    countries: "AE", currencies: "AED", capabilities: "支付,退款,预授权,分账",
    apiBase: "https://api.nearpay.example", merchantId: "MID-AE-100286",
    apiKeyMasked: "sk_test_****", updatedAt: iso(2 * 86400_000),
  },
  {
    channelCode: "STRIPE", channelName: "Stripe", mode: "DIRECT", status: "DISABLED",
    countries: "AE,SA", currencies: "AED,SAR,USD", capabilities: "支付,退款,预授权",
    apiBase: "https://api.stripe.com", merchantId: "acct_****",
    apiKeyMasked: "sk_test_****", updatedAt: iso(20 * 86400_000),
  },
  {
    channelCode: "PAYPAL", channelName: "PayPal", mode: "DIRECT", status: "DISABLED",
    countries: "AE,EG", currencies: "USD,EUR", capabilities: "支付,退款",
    apiBase: "https://api-m.paypal.com", merchantId: "PP-****",
    apiKeyMasked: "sk_test_****", updatedAt: iso(30 * 86400_000),
  },
  {
    channelCode: "TAP", channelName: "Tap Payments（海湾本地卡）", mode: "DIRECT", status: "DISABLED",
    countries: "AE,SA,KW,BH", currencies: "AED,SAR,KWD,BHD", capabilities: "支付,退款,预授权",
    apiBase: "https://api.tap.company", merchantId: "MID-GCC-****",
    apiKeyMasked: "sk_test_****", updatedAt: iso(35 * 86400_000),
  },
  {
    channelCode: "CHECKOUT", channelName: "Checkout.com", mode: "DIRECT", status: "DISABLED",
    countries: "AE,SA,QA", currencies: "AED,SAR,QAR", capabilities: "支付,退款,预授权,分账",
    apiBase: "https://api.checkout.com", merchantId: "MID-CKO-****",
    apiKeyMasked: "sk_test_****", updatedAt: iso(45 * 86400_000),
  },
  {
    channelCode: "HYPERPAY", channelName: "HyperPay（沙特本地）", mode: "DELEGATED", status: "DISABLED",
    countries: "SA,JO,EG", currencies: "SAR,JOD,EGP", capabilities: "支付,退款",
    apiBase: "https://eu-prod.oppwa.com", merchantId: "MID-SA-****",
    apiKeyMasked: "sk_test_****", updatedAt: iso(50 * 86400_000),
  },
];

export const listPaymentChannels = (q: PageQuery = {}) =>
  paginate(paymentChannels, q.page, q.size, (x) => kwHit(q.keyword, x.channelCode, x.channelName, x.countries, x.currencies));
export const savePaymentChannel = (x: Partial<PaymentChannel>) =>
  upsert(paymentChannels, x, "channelCode", () => nextNo("CH", paymentChannels));

// ============================================================================
// 财务域 · 批次 B5：分润统计（§5）/ 充值订单（§6）
// 追加区块（含独立 import，避免与其他批次抢改顶部 import 块）。
// ============================================================================
import type { ShareSummary, RechargeOrder } from "../types";

// —— §5 分润统计 ——
// 分成方一律引用现有 mock 实体：场地方取 venues（VEN3xx），代理商取 agents（AG00x），
// 保证与「分润规则 / 分润明细 / 结算单」跨页口径自洽（规格 §17.1-8）。
const SUMMARY_PERIODS = ["2026-07", "2026-06", "2026-05"];
const SUMMARY_PAYEES: { dimension: ShareSummary["dimension"]; payeeNo: string; payeeName: string }[] = [
  ...venues.map((v) => ({ dimension: "VENUE" as const, payeeNo: v.venueNo, payeeName: v.name })),
  ...agents.slice(0, 6).map((a) => ({ dimension: "AGENT" as const, payeeNo: a.agentNo, payeeName: a.name })),
];
export const shareSummaries: ShareSummary[] = SUMMARY_PERIODS.flatMap((period, pi) =>
  SUMMARY_PAYEES.map((payee, i) => {
    const orderCount = 320 + ((i * 137 + pi * 71) % 880);
    // 客单价 3.2~4.0 AED（与计费模板 PP001 的 30 分钟 3 AED 量级一致）
    const gmv = Number((orderCount * (3.2 + ((i * 3 + pi) % 9) / 10)).toFixed(2));
    // 分成比例沿用各自域的口径：场地方 15~25%，代理商 30~40%
    const rate = payee.dimension === "VENUE" ? [0.15, 0.2, 0.25][i % 3] : [0.3, 0.35, 0.4][i % 3];
    const shareAmount = Number((gmv * rate).toFixed(2));
    // 越早的周期结算越彻底：当月部分结算、上月大部分结清、上上月全清（settled ≤ share 恒成立）
    const settledRatio = pi === 0 ? [0, 0.4, 0.65][i % 3] : pi === 1 ? [0.8, 1, 0.9][i % 3] : 1;
    const settledAmount = Number((shareAmount * settledRatio).toFixed(2));
    return {
      ...payee, period, orderCount, gmv, shareAmount, settledAmount,
      pendingAmount: Number((shareAmount - settledAmount).toFixed(2)),
      currency: "AED",
    };
  }),
);

export type ShareSummaryQuery = PageQuery & {
  dimension?: string; // VENUE / AGENT（维度切换器）
  period?: string;
  sortKey?: string; // 目前仅 shareAmount / pendingAmount / gmv / orderCount
  sortDir?: string; // asc / desc
};
export const listShareSummaries = (q: ShareSummaryQuery = {}) => {
  const rows = shareSummaries.filter((x) =>
    kwHit(q.keyword, x.payeeNo, x.payeeName) &&
    (!q.dimension || x.dimension === q.dimension) &&
    (!q.period || x.period === q.period));
  if (q.sortKey) {
    const dir = q.sortDir === "desc" ? -1 : 1;
    const key = q.sortKey as keyof ShareSummary;
    rows.sort((a, b) => (Number(a[key]) - Number(b[key])) * dir);
  }
  return paginate(rows, q.page, q.size);
};

// —— §6 充值订单 ——
// 用户引用 cUsers（U30xx）；channelCode 取自 paymentChannels 的真实渠道码（NEARPAY 为当前主通道）。
const RECHARGE_PACKAGES = [
  { packageNo: "RP001", pay: 20, gift: 0 },
  { packageNo: "RP002", pay: 50, gift: 5 },
  { packageNo: "RP003", pay: 100, gift: 15 },
  { packageNo: "RP004", pay: 200, gift: 40 },
  { packageNo: null, pay: 35, gift: 0 }, // 自定义金额：无套餐、无赠送
];
const RECHARGE_CHANNELS = ["NEARPAY", "NEARPAY", "NEARPAY", "STRIPE", "TAP", "NEARPAY", "CHECKOUT"];
export const rechargeOrders: RechargeOrder[] = Array.from({ length: 36 }, (_, i) => {
  const user = cUsers[i % cUsers.length];
  const pkg = p(RECHARGE_PACKAGES, i);
  const status = p(["PAID", "PAID", "PAID", "PENDING", "PAID", "FAILED", "PAID", "REFUNDED"] as const, i);
  const settled = status === "PAID" || status === "REFUNDED";
  return {
    rechargeNo: `RCG${70000 + i}`, userNo: user.cUserNo, nickname: user.nickname,
    packageNo: pkg.packageNo, payAmount: pkg.pay, giftAmount: pkg.gift,
    creditAmount: pkg.pay + pkg.gift, currency: "AED",
    channelCode: p(RECHARGE_CHANNELS, i), status,
    createdAt: iso(i * 21600_000),
    paidAt: settled ? iso(i * 21600_000 - 90_000) : null,
    psgTxnNo: settled ? `PSG${20260700 + i}` : null,
  };
});

export type RechargeQuery = PageQuery & { status?: string; from?: string; to?: string };
export const listRechargeOrders = (q: RechargeQuery = {}) =>
  paginate(rechargeOrders, q.page, q.size, (x) =>
    kwHit(q.keyword, x.rechargeNo, x.userNo, x.nickname, x.psgTxnNo, x.channelCode) &&
    (!q.status || x.status === q.status) &&
    // 日期范围按下单时间（PENDING/FAILED 没有 paidAt，用 paidAt 会把它们全筛掉）
    (!q.from || x.createdAt.slice(0, 10) >= q.from) &&
    (!q.to || x.createdAt.slice(0, 10) <= q.to));

// ============================================================================
// 批次 B4/B5 · mock 数据 + list/save（规格 §1 §2 §3 §4 §7 §8）
// 编号沿用现有口径：机柜 CAB1000+、订单 ORD5000xx、用户 U30xx、站点取自 sites；币种 AED。
// ============================================================================

// —— §1 设备日志：双流合一（COMMAND 下发 / REPORT 上报），按时间倒序 ——
const CMD_EVENTS = ["EJECT", "LOCK", "REBOOT", "FW_UPGRADE", "LOCATE"] as const;
const RPT_EVENTS = ["HEARTBEAT", "SLOT_STATE", "RETURN_DETECT", "BATTERY_LOW", "FAULT"] as const;

const cmdPayload = (ev: string, cab: string, i: number) => {
  const slot = (i % 8) + 1;
  switch (ev) {
    case "EJECT": return JSON.stringify({ cmd: "eject", cabinetNo: cab, slot, orderNo: `ORD${500000 + (i % 120)}`, ttlSec: 30 });
    case "LOCK": return JSON.stringify({ cmd: "lock", cabinetNo: cab, slot, reason: "SLOT_FAULT" });
    case "REBOOT": return JSON.stringify({ cmd: "reboot", cabinetNo: cab, delaySec: 5, operator: "admin" });
    case "FW_UPGRADE": return JSON.stringify({ cmd: "fw_upgrade", cabinetNo: cab, fromVersion: "1.3.1", toVersion: "1.4.0", pkgSize: 1843200 });
    default: return JSON.stringify({ cmd: "locate", cabinetNo: cab, buzzerSec: 3 });
  }
};
const rptPayload = (ev: string, cab: string, i: number) => {
  const slot = (i % 8) + 1;
  switch (ev) {
    case "HEARTBEAT": return JSON.stringify({ evt: "heartbeat", cabinetNo: cab, signal: 62 + (i % 30), temp: 31 + (i % 9), fwVersion: "1.4.0", availableCount: i % 9 });
    case "SLOT_STATE": return JSON.stringify({ evt: "slot_state", cabinetNo: cab, slot, powerbankNo: `PB${1000 + i}`, battery: 40 + (i % 55), lock: "LOCKED" });
    case "RETURN_DETECT": return JSON.stringify({ evt: "return_detect", cabinetNo: cab, slot, powerbankNo: `PB${1000 + i}`, orderNo: `ORD${500000 + (i % 120)}`, battery: 12 + (i % 40) });
    case "BATTERY_LOW": return JSON.stringify({ evt: "battery_low", cabinetNo: cab, slot, powerbankNo: `PB${1000 + i}`, battery: 5 + (i % 8), threshold: 15 });
    default: return JSON.stringify({ evt: "fault", cabinetNo: cab, slot, code: "SLOT_STUCK", detail: "powerbank not ejected after 3 retries" });
  }
};

export const deviceLogs: DeviceLog[] = Array.from({ length: 42 }, (_, i) => {
  const isCmd = i % 2 === 0; // 下发/上报交替，构成可读的因果时间轴
  const cab = cabNo(i);
  const ev = isCmd ? p(CMD_EVENTS as unknown as string[], i >> 1) : p(RPT_EVENTS as unknown as string[], i >> 1);
  const bad = i % 11 === 3 ? "TIMEOUT" : i % 17 === 5 ? "FAILED" : "OK";
  return {
    logNo: `LOG${80000 + i}`,
    cabinetNo: cab,
    stream: isCmd ? "COMMAND" : "REPORT",
    direction: isCmd ? "DOWN" : "UP",
    eventType: ev,
    payload: isCmd ? cmdPayload(ev, cab, i) : rptPayload(ev, cab, i),
    vendorCode: p(VENDORS, i),
    occurredAt: iso(i * 900_000), // 递增偏移 = 时间倒序
    result: bad as DeviceLog["result"],
  };
});

/** 设备日志查询：关键词(日志号/机柜/事件) + stream 双流筛选 + 日期范围(YYYY-MM-DD)。 */
export const listDeviceLogs = (q: PageQuery & { stream?: string; from?: string; to?: string } = {}) =>
  paginate(deviceLogs, q.page, q.size, (x) => {
    if (!kwHit(q.keyword, x.logNo, x.cabinetNo, x.eventType, x.vendorCode)) return false;
    if (q.stream && x.stream !== q.stream) return false;
    const day = x.occurredAt.slice(0, 10);
    if (q.from && day < q.from) return false;
    if (q.to && day > q.to) return false;
    return true;
  });

// —— §2 设备编码：按批次 + 供应商归集，跟踪绑定进度 ——
export const deviceCodeBatches: DeviceCodeBatch[] = [
  { batchNo: "BC900", vendorCode: "cd-tech", codeType: "SN", rangeStart: "SN090000", rangeEnd: "SN090999", total: 1000, bound: 1000, producedAt: iso(210 * 86400_000), status: "BOUND" },
  { batchNo: "BC901", vendorCode: "cd-tech", codeType: "QR", rangeStart: "QRAE010001", rangeEnd: "QRAE011000", total: 1000, bound: 742, producedAt: iso(150 * 86400_000), status: "PARTIAL" },
  { batchNo: "BC902", vendorCode: "sd-power", codeType: "SN", rangeStart: "SN091000", rangeEnd: "SN091499", total: 500, bound: 500, producedAt: iso(120 * 86400_000), status: "BOUND" },
  { batchNo: "BC903", vendorCode: "sd-power", codeType: "QR", rangeStart: "QRAE020001", rangeEnd: "QRAE020600", total: 600, bound: 128, producedAt: iso(75 * 86400_000), status: "PARTIAL" },
  { batchNo: "BC904", vendorCode: "chargenow", codeType: "SN", rangeStart: "SN092000", rangeEnd: "SN092799", total: 800, bound: 0, producedAt: iso(40 * 86400_000), status: "PENDING" },
  { batchNo: "BC905", vendorCode: "chargenow", codeType: "QR", rangeStart: "QRSA030001", rangeEnd: "QRSA030400", total: 400, bound: 0, producedAt: iso(28 * 86400_000), status: "PENDING" },
  { batchNo: "BC906", vendorCode: "cd-tech", codeType: "QR", rangeStart: "QRAE019001", rangeEnd: "QRAE019200", total: 200, bound: 0, producedAt: iso(96 * 86400_000), status: "VOID" },
  { batchNo: "BC907", vendorCode: "sd-power", codeType: "SN", rangeStart: "SN093000", rangeEnd: "SN093299", total: 300, bound: 61, producedAt: iso(14 * 86400_000), status: "PARTIAL" },
];
export const listDeviceCodeBatches = (q: PageQuery = {}) =>
  paginate(deviceCodeBatches, q.page, q.size, (x) => kwHit(q.keyword, x.batchNo, x.vendorCode, x.rangeStart, x.rangeEnd));
export const saveDeviceCodeBatch = (x: Partial<DeviceCodeBatch>) =>
  upsert(deviceCodeBatches, x, "batchNo", () => nextNo("BC", deviceCodeBatches));

// —— §3 预约订单：预约取宝 / 预约还位 ——
const RES_STATUS: Reservation["status"][] = ["PENDING", "PENDING", "FULFILLED", "EXPIRED", "CANCELLED", "FULFILLED"];
export const reservations: Reservation[] = Array.from({ length: 22 }, (_, i) => {
  const st = p(RES_STATUS, i);
  const site = p(sites, i);
  const type: Reservation["type"] = i % 3 === 0 ? "RETURN" : "BORROW";
  // 待履约的预约窗口必须挂在**真实当前时间**上（而非 mock 固定时间轴），
  // 否则「即将超时」永远算不出来——该高亮判定的是「距 reservedTo 还剩多久」。
  const now = Date.now();
  const pendingFrom = new Date(now - 10 * 60_000).toISOString();
  const pendingTo = new Date(now + (i < 2 ? 12 : 45 + i * 20) * 60_000).toISOString();
  const fromOffset = (i * 6 + 3) * 3600_000;
  if (st === "PENDING") {
    return {
      reservationNo: `RSV${600 + i}`,
      userNo: `U${3000 + (i % 40)}`,
      type,
      siteNo: site.siteNo,
      siteName: site.name,
      cabinetNo: i % 4 === 0 ? null : cabNo(i),
      reservedFrom: pendingFrom,
      reservedTo: pendingTo,
      holdFee: 0,
      currency: "AED",
      status: st,
      orderNo: null,
    };
  }
  return {
    reservationNo: `RSV${600 + i}`,
    userNo: `U${3000 + (i % 40)}`,
    type,
    siteNo: site.siteNo,
    siteName: site.name,
    cabinetNo: i % 4 === 0 ? null : cabNo(i), // 空 = 站点级预约（不指定机柜）
    reservedFrom: iso(fromOffset),
    reservedTo: iso(fromOffset - 30 * 60_000),
    holdFee: st === "EXPIRED" ? 2 + (i % 3) : 0,
    currency: "AED",
    status: st,
    orderNo: st === "FULFILLED" ? `ORD${500000 + (i % 120)}` : null,
  };
});
export const listReservations = (q: PageQuery & { status?: string; type?: string } = {}) =>
  paginate(reservations, q.page, q.size, (x) => {
    if (!kwHit(q.keyword, x.reservationNo, x.userNo, x.siteName, x.cabinetNo, x.orderNo)) return false;
    if (q.status && x.status !== q.status) return false;
    if (q.type && x.type !== q.type) return false;
    return true;
  });
/** 取消预约：仅 PENDING 可取消（非 PENDING 直接原样返回，由前端按钮先行拦截）。 */
export const cancelReservation = (no: string): Reservation => {
  const i = reservations.findIndex((r) => r.reservationNo === no);
  if (i < 0) throw new Error(`预约不存在：${no}`);
  if (reservations[i].status !== "PENDING") throw new Error("仅待履约（PENDING）的预约可取消");
  reservations[i] = { ...reservations[i], status: "CANCELLED", holdFee: 0 };
  return reservations[i];
};

// —— §4 免费订单：来源即白名单用途，页头做成本管控统计 ——
const REASONS: WhitelistReason[] = ["INTERNAL_TEST", "VIP", "BD_DEMO", "MERCHANT_SELF"];
export const freeOrders: FreeOrder[] = Array.from({ length: 26 }, (_, i) => {
  const dur = 25 + ((i * 37) % 260);
  const start = i * 20 * 3600_000;
  return {
    orderNo: `ORD${500200 + i}`,
    userNo: `U${3000 + (i % 40)}`,
    nickname: p(NICKS, i),
    whitelistReason: p(REASONS, i),
    waivedAmount: Math.min(30, Math.ceil(dur / 30) * 3),
    currency: "AED",
    siteName: p(LOCS, i),
    cabinetNo: cabNo(i),
    startedAt: iso(start),
    endedAt: iso(start - dur * 60_000),
    duration: dur,
  };
});
export const listFreeOrders = (q: PageQuery & { reason?: string } = {}) =>
  paginate(freeOrders, q.page, q.size, (x) => {
    if (!kwHit(q.keyword, x.orderNo, x.userNo, x.nickname, x.siteName, x.cabinetNo)) return false;
    if (q.reason && x.whitelistReason !== q.reason) return false;
    return true;
  });
/** 页头统计：本月免费单数（按 mock「当前」月份口径）+ 累计减免金额。 */
export const getFreeOrderStats = (): FreeOrderStats => {
  const month = iso(0).slice(0, 7);
  return {
    monthCount: freeOrders.filter((o) => o.startedAt.slice(0, 7) === month).length,
    waivedTotal: freeOrders.reduce((s, o) => s + o.waivedAmount, 0),
    currency: "AED",
  };
};

// —— §7 免费用户白名单：用途强制枚举，额度可限次/限额/不限 ——
export const freeWhitelist: FreeUserWhitelist[] = Array.from({ length: 14 }, (_, i) => {
  const quotaType = p(["TIMES", "AMOUNT", "UNLIMITED"] as const, i);
  const status: FreeUserWhitelist["status"] = i % 7 === 3 ? "EXPIRED" : i % 11 === 6 ? "REVOKED" : "ACTIVE";
  const quotaValue = quotaType === "UNLIMITED" ? 0 : quotaType === "TIMES" ? 10 + (i % 4) * 10 : 100 + (i % 5) * 50;
  return {
    userNo: `U${3000 + i}`,
    nickname: p(NICKS, i),
    phone: phone(i),
    reason: p(REASONS, i),
    quotaType,
    quotaValue,
    usedValue: quotaType === "UNLIMITED" ? 0 : Math.round(quotaValue * ((i % 5) / 5)),
    validFrom: iso((30 + i) * 86400_000).slice(0, 10),
    validTo: iso((status === "EXPIRED" ? 3 : -(60 + i * 5)) * 86400_000).slice(0, 10),
    grantedBy: p(OPERATORS, i),
    status,
  };
});
export const listFreeWhitelist = (q: PageQuery & { status?: string; reason?: string } = {}) =>
  paginate(freeWhitelist, q.page, q.size, (x) => {
    if (!kwHit(q.keyword, x.userNo, x.nickname, x.phone, x.grantedBy)) return false;
    if (q.status && x.status !== q.status) return false;
    if (q.reason && x.reason !== q.reason) return false;
    return true;
  });
export const saveFreeWhitelist = (x: Partial<FreeUserWhitelist>) =>
  upsert(freeWhitelist, x, "userNo", () => nextNo("U", freeWhitelist, 3900));
/** 撤销白名单：置 REVOKED（软撤销，保留审计痕迹，对齐决策 §八-4）。 */
export const revokeFreeWhitelist = (userNo: string): FreeUserWhitelist => {
  const i = freeWhitelist.findIndex((w) => w.userNo === userNo);
  if (i < 0) throw new Error(`白名单不存在：${userNo}`);
  freeWhitelist[i] = { ...freeWhitelist[i], status: "REVOKED" };
  return freeWhitelist[i];
};

// —— §8 充值套餐：比竞品多「赠额有效期」与「适用市场」 ——
export const rechargePackages: RechargePackage[] = [
  { packageNo: "RP900", name: "体验包", payAmount: 20, giftAmount: 0, currency: "AED", markets: "AE", validDays: 90, sortNo: 1, status: "ENABLED" },
  { packageNo: "RP901", name: "常用包", payAmount: 50, giftAmount: 5, currency: "AED", markets: "AE,SA", validDays: 180, sortNo: 2, status: "ENABLED" },
  { packageNo: "RP902", name: "超值包", payAmount: 100, giftAmount: 15, currency: "AED", markets: "AE,SA,KW", validDays: 365, sortNo: 3, status: "ENABLED" },
  { packageNo: "RP903", name: "家庭包", payAmount: 200, giftAmount: 40, currency: "AED", markets: "AE", validDays: 365, sortNo: 4, status: "ENABLED" },
  { packageNo: "RP904", name: "斋月特惠包", payAmount: 80, giftAmount: 20, currency: "AED", markets: "AE,SA,QA", validDays: 60, sortNo: 5, status: "DISABLED" },
  { packageNo: "RP905", name: "商户自用包", payAmount: 500, giftAmount: 60, currency: "AED", markets: "AE", validDays: 365, sortNo: 6, status: "DISABLED" },
];
export const listRechargePackages = (q: PageQuery & { status?: string } = {}) =>
  paginate(rechargePackages, q.page, q.size, (x) => {
    if (!kwHit(q.keyword, x.packageNo, x.name, x.markets)) return false;
    if (q.status && x.status !== q.status) return false;
    return true;
  });
export const saveRechargePackage = (x: Partial<RechargePackage>) =>
  upsert(rechargePackages, x, "packageNo", () => nextNo("RP", rechargePackages));

// ============================================================================
// 系统设置 · 批次 B2/B3/B5 待建 8 项 mock（规格 §9~§16）
// 口径：MENA 市场（AE/SA/EG…）、币种 AED、引用现有编号（CAB1000+ / ORD5000xx / U30xx / NT1xx）。
// 禁止写入任何真实密钥/真实联系方式：目标一律脱敏，税号/账号用占位。
// ============================================================================

/** 今日基准（mock 时间轴的"现在"），发送记录页头统计据此判定"今日"。 */
const TODAY = iso(0).slice(0, 10);

/** 联系方式脱敏：手机保留前 6 后 2，邮箱保留首字母与域名。前端永不承载完整联系方式。 */
export function maskTarget(v: string): string {
  if (v.includes("@")) {
    const [name, domain] = v.split("@");
    return `${name.slice(0, 1)}***@${domain}`;
  }
  if (v.length <= 8) return `${v.slice(0, 3)}****`;
  return `${v.slice(0, 6)}****${v.slice(-2)}`;
}

// —— §9 发送记录 ——
const NOTIFY_SCENES = ["OTP 验证码", "借出成功", "归还成功", "扣费通知", "逾期提醒", "工单派单", "提现结果", "告警通知"];
const NOTIFY_FAILS = ["运营商拒收（号码停机）", "邮箱硬退信（地址不存在）", "设备 token 已失效", "触达拉黑名单命中", "上游限流，稍后重试"];
const NOTIFY_TARGETS = [
  "+9715012345678", "+9715098765432", "+966501234567", "+9715055512345",
  "fatima.a@example.ae", "omar.k@example.sa", "layla.h@example.ae",
  "dGtuX2FwbnNfODkwMTIz", "+201001234567", "+9715077788899",
];
export const notifyLogs: NotifyLog[] = Array.from({ length: 42 }, (_, i) => {
  const channel = p(["SMS", "EMAIL", "PUSH", "WHATSAPP"] as const, i);
  // Push 近乎免费，短信/WhatsApp 单价高——成本差异是本页存在的理由。
  const cost = channel === "PUSH" ? 0 : channel === "EMAIL" ? 0.01 : channel === "WHATSAPP" ? 0.11 : 0.09;
  const failed = i % 11 === 0;
  return {
    logNo: `NL${7000 + i}`,
    channel,
    templateNo: `NT${100 + (i % 14)}`,
    target: maskTarget(p(NOTIFY_TARGETS, i)),
    scene: p(NOTIFY_SCENES, i),
    // 前 16 条落在"今日"，其余往前铺 1~6 天，让页头统计有真实分母。
    sentAt: i < 16 ? iso(i * 1800_000) : iso((i - 15) * 86400_000 / 4 + 43200_000),
    status: failed ? "FAILED" : "SENT",
    failReason: failed ? p(NOTIFY_FAILS, i) : null,
    cost,
    currency: "AED",
  } as NotifyLog;
});

export const listNotifyLogs = (q: PageQuery & { channel?: string; status?: string; sort?: string; dir?: string } = {}) => {
  const rows = notifyLogs.filter((x) =>
    (!q.channel || x.channel === q.channel) &&
    (!q.status || x.status === q.status) &&
    kwHit(q.keyword, x.logNo, x.templateNo, x.target, x.scene, x.failReason));
  if (q.sort) {
    const dir = q.dir === "asc" ? 1 : -1;
    rows.sort((a, b) => (q.sort === "cost" ? (a.cost - b.cost) : a.sentAt.localeCompare(b.sentAt)) * dir);
  }
  return paginate(rows, q.page, q.size);
};
/** 页头统计：今日发送量 / 失败率 / 今日成本（失败率按今日口径，避免历史稀释当日异常）。 */
export function getNotifyLogStats(): NotifyLogStats {
  const today = notifyLogs.filter((x) => x.sentAt.slice(0, 10) === TODAY);
  const failed = today.filter((x) => x.status === "FAILED").length;
  return {
    sentToday: today.length,
    failedToday: failed,
    failRate: today.length ? failed / today.length : 0,
    costToday: Math.round(today.reduce((s, x) => s + x.cost, 0) * 100) / 100,
    currency: "AED",
  };
}

// —— §10 触达拉黑 ——
export const notifyBlacklist: NotifyBlacklist[] = [
  { blockNo: "BL901", target: maskTarget("+9715012345678"), channel: "SMS", reason: "USER_OPT_OUT", blockedAt: iso(3 * 86400_000), blockedBy: "系统（用户回复 STOP）", expireAt: null },
  { blockNo: "BL902", target: maskTarget("omar.k@example.sa"), channel: "EMAIL", reason: "HARD_BOUNCE", blockedAt: iso(6 * 86400_000), blockedBy: "系统（SES 硬退信）", expireAt: null },
  { blockNo: "BL903", target: maskTarget("+966501234567"), channel: "ALL", reason: "ABUSE", blockedAt: iso(9 * 86400_000), blockedBy: "风控值班组", expireAt: iso(-21 * 86400_000) },
  { blockNo: "BL904", target: maskTarget("dGtuX2FwbnNfODkwMTIz"), channel: "PUSH", reason: "MANUAL", blockedAt: iso(12 * 86400_000), blockedBy: "客服中心", expireAt: iso(-3 * 86400_000) },
  { blockNo: "BL905", target: maskTarget("+9715055512345"), channel: "SMS", reason: "ABUSE", blockedAt: iso(20 * 86400_000), blockedBy: "风控值班组", expireAt: iso(5 * 86400_000) },
  { blockNo: "BL906", target: maskTarget("layla.h@example.ae"), channel: "EMAIL", reason: "USER_OPT_OUT", blockedAt: iso(26 * 86400_000), blockedBy: "系统（退订链接）", expireAt: null },
  { blockNo: "BL907", target: maskTarget("+201001234567"), channel: "WHATSAPP", reason: "HARD_BOUNCE", blockedAt: iso(31 * 86400_000), blockedBy: "系统（WhatsApp 未注册）", expireAt: null },
  { blockNo: "BL908", target: maskTarget("+9715077788899"), channel: "ALL", reason: "MANUAL", blockedAt: iso(40 * 86400_000), blockedBy: "运营中心", expireAt: iso(-60 * 86400_000) },
];
export const listNotifyBlacklist = (q: PageQuery & { channel?: string; reason?: string } = {}) =>
  paginate(notifyBlacklist, q.page, q.size, (x) =>
    (!q.channel || x.channel === q.channel) &&
    (!q.reason || x.reason === q.reason) &&
    kwHit(q.keyword, x.blockNo, x.target, x.blockedBy));
export const saveNotifyBlacklist = (x: Partial<NotifyBlacklist>) =>
  upsert(notifyBlacklist, x, "blockNo", () => nextNo("BL", notifyBlacklist));
/** 解除拉黑：软删除——把到期时间置为当下，保留拉黑历史供审计（决策 §八-4）。 */
export function releaseNotifyBlacklist(blockNo: string): NotifyBlacklist {
  const i = notifyBlacklist.findIndex((x) => x.blockNo === blockNo);
  if (i < 0) throw new Error("拉黑记录不存在");
  notifyBlacklist[i] = { ...notifyBlacklist[i], expireAt: iso(0) };
  return notifyBlacklist[i];
}

// —— §11 业务规则 ——
// ⚠️ 数值为 mock 占位，非业务口径；提现手续费率/封顶将来是提现审核页的唯一来源（规格 §17.1-3）。
export const bizRules: BizRules = {
  withdraw: { minAmount: 100, feeRate: 0.006, feeCap: 25, settleDays: 7, dailyLimit: 20000, needApproval: true },
  reservation: { maxDurationMin: 30, advanceHours: 24, holdFeePerMin: 0.2, maxConcurrent: 1 },
  billing: { freeMinutes: 5, billUnitMinutes: 30, dailyCap: 20, buyoutPrice: 99, overdueHours: 72 },
  currency: "AED",
  updatedAt: iso(4 * 86400_000),
};
export const getBizRules = (): BizRules => bizRules;
/** 分区保存：只覆盖传入的分区，未传分区保持不变（三张 Card 各自保存）。 */
export function saveBizRules(x: Partial<BizRules>): BizRules {
  if (x.withdraw) bizRules.withdraw = { ...bizRules.withdraw, ...x.withdraw };
  if (x.reservation) bizRules.reservation = { ...bizRules.reservation, ...x.reservation };
  if (x.billing) bizRules.billing = { ...bizRules.billing, ...x.billing };
  bizRules.updatedAt = iso(0);
  return bizRules;
}

// —— §12 登录设置 ——
// `*` 是默认档（无专属配置的国家走它），列表置顶。
export const loginSettings: LoginSetting[] = [
  { country: "*", countryName: "默认（未单独配置的国家）", otpEnabled: true, passwordEnabled: false, appleEnabled: true, googleEnabled: true, otpExpireSec: 300, otpDailyLimit: 10, forceRealName: false },
  { country: "AE", countryName: "阿联酋", otpEnabled: true, passwordEnabled: false, appleEnabled: true, googleEnabled: true, otpExpireSec: 300, otpDailyLimit: 12, forceRealName: false },
  { country: "SA", countryName: "沙特", otpEnabled: true, passwordEnabled: true, appleEnabled: true, googleEnabled: false, otpExpireSec: 180, otpDailyLimit: 8, forceRealName: true },
  { country: "QA", countryName: "卡塔尔", otpEnabled: true, passwordEnabled: false, appleEnabled: true, googleEnabled: true, otpExpireSec: 300, otpDailyLimit: 10, forceRealName: false },
  { country: "KW", countryName: "科威特", otpEnabled: true, passwordEnabled: false, appleEnabled: false, googleEnabled: true, otpExpireSec: 300, otpDailyLimit: 10, forceRealName: false },
  { country: "EG", countryName: "埃及", otpEnabled: true, passwordEnabled: true, appleEnabled: false, googleEnabled: true, otpExpireSec: 600, otpDailyLimit: 6, forceRealName: false },
];
/** `*` 默认档恒置顶，其余按国家码排序——一眼看清"默认是什么、谁被单独放开"。 */
export const listLoginSettings = (q: PageQuery = {}) => {
  const rows = loginSettings
    .filter((x) => kwHit(q.keyword, x.country, x.countryName))
    .sort((a, b) => (a.country === "*" ? -1 : b.country === "*" ? 1 : a.country.localeCompare(b.country)));
  return paginate(rows, q.page, q.size);
};
export const saveLoginSetting = (x: Partial<LoginSetting>) =>
  upsert(loginSettings, x, "country", () => nextNo("XX", loginSettings, 0));

// —— §13 应用版本 ——
export const appVersions: AppVersion[] = [
  {
    versionId: "IOS-1.4.2", versionNo: "1.4.2", platform: "IOS", buildNo: 1420,
    releaseNote: "支持信用免押借出；修复 Dubai Mall 部分机柜扫码超时。",
    releaseNoteEn: "Credit-based deposit waiver; fixed scan timeout at some Dubai Mall cabinets.",
    releaseNoteAr: "الإعفاء من التأمين بناءً على التقييم الائتماني؛ إصلاح انتهاء مهلة المسح في بعض خزائن دبي مول.",
    forceUpdate: false, minSupported: "1.2.0", rolloutPercent: 100,
    downloadUrl: "https://apps.apple.com/app/id0000000000", status: "RELEASED", releasedAt: iso(6 * 86400_000),
  },
  {
    versionId: "IOS-1.5.0", versionNo: "1.5.0", platform: "IOS", buildNo: 1500,
    releaseNote: "新增预约取宝；阿语界面 RTL 全量适配。",
    releaseNoteEn: "Reserve-a-powerbank; full RTL polish for Arabic.",
    releaseNoteAr: "حجز بطارية مسبقًا؛ تحسين كامل لواجهة اللغة العربية من اليمين إلى اليسار.",
    forceUpdate: false, minSupported: "1.3.0", rolloutPercent: 20,
    downloadUrl: "https://apps.apple.com/app/id0000000000", status: "RELEASED", releasedAt: iso(1 * 86400_000),
  },
  {
    versionId: "ANDROID-1.4.2", versionNo: "1.4.2", platform: "ANDROID", buildNo: 1421,
    releaseNote: "支持信用免押借出；优化弱网下的归还确认。",
    releaseNoteEn: "Credit-based deposit waiver; better return confirmation on weak networks.",
    releaseNoteAr: "الإعفاء من التأمين؛ تحسين تأكيد الإرجاع عند ضعف الشبكة.",
    forceUpdate: false, minSupported: "1.2.0", rolloutPercent: 100,
    downloadUrl: "https://play.google.com/store/apps/details?id=example.sharehub", status: "RELEASED", releasedAt: iso(6 * 86400_000),
  },
  {
    versionId: "ANDROID-1.4.3", versionNo: "1.4.3", platform: "ANDROID", buildNo: 1430,
    releaseNote: "强制更新：修复支付回调丢单导致的重复扣费。",
    releaseNoteEn: "Mandatory update: fixes duplicate charges caused by lost payment callbacks.",
    releaseNoteAr: "تحديث إلزامي: إصلاح الخصم المزدوج الناتج عن فقدان استدعاء الدفع.",
    forceUpdate: true, minSupported: "1.4.3", rolloutPercent: 100,
    downloadUrl: "https://play.google.com/store/apps/details?id=example.sharehub", status: "RELEASED", releasedAt: iso(2 * 86400_000),
  },
  {
    versionId: "ANDROID-1.4.1", versionNo: "1.4.1", platform: "ANDROID", buildNo: 1410,
    releaseNote: "灰度中发现归还偶发失败，已回滚。",
    releaseNoteEn: "Rolled back: intermittent return failures found during rollout.",
    releaseNoteAr: "تم التراجع: أعطال متقطعة في الإرجاع أثناء الطرح التدريجي.",
    forceUpdate: false, minSupported: "1.2.0", rolloutPercent: 0,
    downloadUrl: "https://play.google.com/store/apps/details?id=example.sharehub", status: "ROLLBACK", releasedAt: iso(14 * 86400_000),
  },
  {
    versionId: "H5-2.1.0", versionNo: "2.1.0", platform: "H5", buildNo: 2100,
    releaseNote: "小程序/H5 免安装借还；接入 NEARPAY 快捷支付。",
    releaseNoteEn: "Install-free rental on H5; NEARPAY express checkout.",
    releaseNoteAr: "الاستئجار دون تثبيت عبر H5؛ الدفع السريع عبر NEARPAY.",
    forceUpdate: false, minSupported: "2.0.0", rolloutPercent: 100,
    downloadUrl: "https://h5.example.ae", status: "RELEASED", releasedAt: iso(9 * 86400_000),
  },
  {
    versionId: "H5-2.2.0", versionNo: "2.2.0", platform: "H5", buildNo: 2200,
    releaseNote: "草稿：站点地图与附近可借数量。",
    releaseNoteEn: "Draft: station map with live availability.",
    releaseNoteAr: "مسودة: خريطة المحطات مع توفر البطاريات مباشرة.",
    forceUpdate: false, minSupported: "2.0.0", rolloutPercent: 0,
    downloadUrl: "https://h5.example.ae", status: "DRAFT", releasedAt: null,
  },
];
/** 按平台分组显示：先平台（IOS→ANDROID→H5），组内按 buildNo 倒序（新版在上）。 */
const PLATFORM_ORDER: AppVersion["platform"][] = ["IOS", "ANDROID", "H5"];
export const listAppVersions = (q: PageQuery & { platform?: string } = {}) => {
  const rows = appVersions
    .filter((x) => (!q.platform || x.platform === q.platform) && kwHit(q.keyword, x.versionNo, x.platform, x.releaseNote, x.releaseNoteEn))
    .sort((a, b) =>
      PLATFORM_ORDER.indexOf(a.platform) - PLATFORM_ORDER.indexOf(b.platform) || b.buildNo - a.buildNo);
  return paginate(rows, q.page, q.size);
};
export const saveAppVersion = (x: Partial<AppVersion>) => {
  // versionId 由 平台-版本号 派生：同一版本号在不同平台是两条记录。
  const withId = x.versionId ? x : { ...x, versionId: `${x.platform ?? "IOS"}-${x.versionNo ?? "0.0.0"}` };
  return upsert(appVersions, withId, "versionId", () => `${x.platform ?? "IOS"}-${x.versionNo ?? "0.0.0"}`);
};
/** 回滚：置 ROLLBACK 且灰度归零（立即停止下发），保留记录不物理删。 */
export function rollbackAppVersion(versionId: string): AppVersion {
  const i = appVersions.findIndex((x) => x.versionId === versionId);
  if (i < 0) throw new Error("版本不存在");
  appVersions[i] = { ...appVersions[i], status: "ROLLBACK", rolloutPercent: 0 };
  return appVersions[i];
}

// —— §14 银行管理 ——
// IBAN 长度是各国固定值（AE 23 / SA 24 / EG 29 …），提现收款账户按此校验。
export const banks: BankEntry[] = [
  { bankCode: "ENBD", bankName: "阿联酋国民银行", bankNameEn: "Emirates NBD", country: "AE", currency: "AED", swiftPrefix: "EBILAEAD", ibanLength: 23, status: "ENABLED" },
  { bankCode: "FAB", bankName: "阿布扎比第一银行", bankNameEn: "First Abu Dhabi Bank", country: "AE", currency: "AED", swiftPrefix: "NBADAEAA", ibanLength: 23, status: "ENABLED" },
  { bankCode: "ADCB", bankName: "阿布扎比商业银行", bankNameEn: "Abu Dhabi Commercial Bank", country: "AE", currency: "AED", swiftPrefix: "ADCBAEAA", ibanLength: 23, status: "ENABLED" },
  { bankCode: "MASHREQ", bankName: "马士礼格银行", bankNameEn: "Mashreq Bank", country: "AE", currency: "AED", swiftPrefix: "BOMLAEAD", ibanLength: 23, status: "ENABLED" },
  { bankCode: "DIB", bankName: "迪拜伊斯兰银行", bankNameEn: "Dubai Islamic Bank", country: "AE", currency: "AED", swiftPrefix: "DUIBAEAD", ibanLength: 23, status: "ENABLED" },
  { bankCode: "RAJHI", bankName: "拉吉希银行", bankNameEn: "Al Rajhi Bank", country: "SA", currency: "SAR", swiftPrefix: "RJHISARI", ibanLength: 24, status: "ENABLED" },
  { bankCode: "SNB", bankName: "沙特国民银行", bankNameEn: "Saudi National Bank", country: "SA", currency: "SAR", swiftPrefix: "NCBKSAJE", ibanLength: 24, status: "ENABLED" },
  { bankCode: "RIYAD", bankName: "利雅得银行", bankNameEn: "Riyad Bank", country: "SA", currency: "SAR", swiftPrefix: "RIBLSARI", ibanLength: 24, status: "DISABLED" },
  { bankCode: "QNB", bankName: "卡塔尔国民银行", bankNameEn: "Qatar National Bank", country: "QA", currency: "QAR", swiftPrefix: "QNBAQAQA", ibanLength: 29, status: "DISABLED" },
  { bankCode: "NBK", bankName: "科威特国民银行", bankNameEn: "National Bank of Kuwait", country: "KW", currency: "KWD", swiftPrefix: "NBOKKWKW", ibanLength: 30, status: "DISABLED" },
  { bankCode: "CIB", bankName: "埃及商业国际银行", bankNameEn: "Commercial International Bank", country: "EG", currency: "EGP", swiftPrefix: "CIBEEGCX", ibanLength: 29, status: "DISABLED" },
];
export const listBanks = (q: PageQuery & { country?: string; currency?: string } = {}) =>
  paginate(banks, q.page, q.size, (x) =>
    (!q.country || x.country === q.country) &&
    (!q.currency || x.currency === q.currency) &&
    kwHit(q.keyword, x.bankCode, x.bankName, x.bankNameEn, x.swiftPrefix));
export const saveBank = (x: Partial<BankEntry>) => upsert(banks, x, "bankCode", () => nextNo("BK", banks));

// —— §15 问题管理 ——
export const problems: ProblemEntry[] = [
  {
    problemNo: "PB901", category: "RENT",
    title: "扫码后充电宝没弹出", titleEn: "Nothing ejected after scanning", titleAr: "لم تخرج البطارية بعد مسح الرمز",
    answer: "请在 App 内点「重试弹出」；仍无反应说明卡槽卡宝，我们会自动开工单并在 30 分钟内到场，本单不计费。",
    answerEn: "Tap “Retry eject” in the app. If it still fails the slot is stuck — a work order is raised automatically, an engineer arrives within 30 minutes, and this rental is not charged.",
    answerAr: "اضغط «إعادة الإخراج» في التطبيق. إذا استمرت المشكلة فالفتحة عالقة — سيتم إنشاء طلب صيانة تلقائيًا والوصول خلال 30 دقيقة، ولن يتم احتساب رسوم.",
    suggestedAction: "TO_WORKORDER", sortNo: 1, status: "ENABLED",
  },
  {
    problemNo: "PB902", category: "RETURN",
    title: "机柜满仓，还不进去", titleEn: "Cabinet is full, cannot return", titleAr: "الخزانة ممتلئة ولا يمكن الإرجاع",
    answer: "请在 App 地图上选择附近可还机柜（显示空仓数）；因满仓产生的超时时长会在申诉后免除。",
    answerEn: "Pick a nearby cabinet with free slots on the app map. Overdue time caused by a full cabinet is waived after you file a claim.",
    answerAr: "اختر خزانة قريبة بها فتحات فارغة من خريطة التطبيق. سيتم إعفاء وقت التأخير الناتج عن امتلاء الخزانة بعد تقديم الشكوى.",
    suggestedAction: "SELF_SERVICE", sortNo: 2, status: "ENABLED",
  },
  {
    problemNo: "PB903", category: "BILLING",
    title: "已归还但仍在计费", titleEn: "Still being charged after returning", titleAr: "استمرار احتساب الرسوم بعد الإرجاع",
    answer: "归还回执以机柜上报为准，偶发延迟在 10 分钟内自动结算；超过 10 分钟请提交订单号，客服核对后按实际归还时间重算并退差额。",
    answerEn: "Return is confirmed by the cabinet report; occasional delays settle automatically within 10 minutes. Beyond that, submit the order number — we recalculate by the actual return time and refund the difference.",
    answerAr: "يتم تأكيد الإرجاع من تقرير الخزانة، وتتم التسوية تلقائيًا خلال 10 دقائق. بعد ذلك، أرسل رقم الطلب وسنعيد الحساب حسب وقت الإرجاع الفعلي ونرد الفرق.",
    suggestedAction: "TO_REFUND", sortNo: 3, status: "ENABLED",
  },
  {
    problemNo: "PB904", category: "BILLING",
    title: "押金什么时候退", titleEn: "When is my deposit refunded", titleAr: "متى يتم رد مبلغ التأمين",
    answer: "归还后押金即时解冻，银行入账通常 1-3 个工作日（部分发卡行最长 7 天）。信用免押用户无押金冻结。",
    answerEn: "The deposit is released immediately after return; banks post it in 1-3 business days (up to 7 with some issuers). Credit-waiver users have no deposit hold.",
    answerAr: "يتم تحرير التأمين فور الإرجاع، ويستغرق ظهوره في البنك من 1 إلى 3 أيام عمل (حتى 7 أيام لدى بعض البنوك). لا يوجد تأمين لمستخدمي الإعفاء الائتماني.",
    suggestedAction: "SELF_SERVICE", sortNo: 4, status: "ENABLED",
  },
  {
    problemNo: "PB905", category: "DEVICE",
    title: "充电宝充不进电 / 线坏了", titleEn: "Powerbank not charging or cable broken", titleAr: "البطارية لا تشحن أو الكابل تالف",
    answer: "请就近归还并在 App 内报障，本单免费；我们会锁定该充电宝编号并派维修回收。",
    answerEn: "Return it at the nearest cabinet and report the fault in the app — this rental is free. We lock that powerbank and dispatch a technician to collect it.",
    answerAr: "أعِد البطارية في أقرب خزانة وأبلغ عن العطل في التطبيق — هذا الاستئجار مجاني. سنقوم بحظر البطارية وإرسال فني لاستلامها.",
    suggestedAction: "TO_WORKORDER", sortNo: 5, status: "ENABLED",
  },
  {
    problemNo: "PB906", category: "ACCOUNT",
    title: "收不到验证码", titleEn: "Not receiving the OTP", titleAr: "لا أستلم رمز التحقق",
    answer: "请确认号码所在国家已开放注册，并检查是否曾回复 STOP 退订（会进入触达拉黑）。可改用 Apple / Google 登录。",
    answerEn: "Check that your country is open for sign-up and whether you previously replied STOP (which adds you to the send-blocklist). You can also sign in with Apple or Google.",
    answerAr: "تأكد من أن بلدك متاح للتسجيل، وتحقق مما إذا كنت قد رددت بكلمة STOP سابقًا (تؤدي إلى الحظر). يمكنك أيضًا تسجيل الدخول عبر Apple أو Google.",
    suggestedAction: "TO_CS", sortNo: 6, status: "ENABLED",
  },
  {
    problemNo: "PB907", category: "RENT",
    title: "同时借多个充电宝", titleEn: "Renting more than one powerbank", titleAr: "استئجار أكثر من بطارية",
    answer: "单账号默认同时可借 1 个；实名用户可申请提升至 2 个，超出请使用同行人账号。",
    answerEn: "One active rental per account by default; verified users can request a limit of two. Beyond that, please use a companion’s account.",
    answerAr: "استئجار واحد نشط لكل حساب افتراضيًا؛ يمكن للمستخدمين الموثقين طلب رفعه إلى اثنين. لما زاد عن ذلك، استخدم حساب مرافق.",
    suggestedAction: "SELF_SERVICE", sortNo: 7, status: "ENABLED",
  },
  {
    problemNo: "PB908", category: "OTHER",
    title: "发票 / 报销凭证", titleEn: "Invoice for expense claims", titleAr: "الفاتورة الضريبية للمصروفات",
    answer: "在「我的-订单」选择订单申请电子发票，含 TRN 税号，通常 10 分钟内发送到邮箱。",
    answerEn: "Request an e-invoice from My Orders; it includes the TRN and usually arrives by email within 10 minutes.",
    answerAr: "اطلب الفاتورة الإلكترونية من «طلباتي»؛ تتضمن الرقم الضريبي وتصل عبر البريد خلال 10 دقائق عادةً.",
    suggestedAction: "SELF_SERVICE", sortNo: 8, status: "ENABLED",
  },
  {
    problemNo: "PB909", category: "OTHER",
    title: "斋月营业时间（已停用）", titleEn: "Ramadan opening hours (retired)", titleAr: "ساعات العمل في رمضان (موقوف)",
    answer: "旧版斋月说明，已由公告替代，保留仅供历史工单参考。",
    answerEn: "Legacy Ramadan notice, superseded by announcements; kept for historical tickets only.",
    answerAr: "إشعار رمضان القديم، تم استبداله بالإعلانات؛ محفوظ للرجوع فقط.",
    suggestedAction: "TO_CS", sortNo: 9, status: "DISABLED",
  },
];
export const listProblems = (q: PageQuery & { category?: string; status?: string } = {}) => {
  const rows = problems
    .filter((x) =>
      (!q.category || x.category === q.category) &&
      (!q.status || x.status === q.status) &&
      kwHit(q.keyword, x.problemNo, x.title, x.titleEn, x.titleAr, x.answer))
    .sort((a, b) => a.sortNo - b.sortNo);
  return paginate(rows, q.page, q.size);
};
export const saveProblem = (x: Partial<ProblemEntry>) => upsert(problems, x, "problemNo", () => nextNo("PB", problems));

// —— §16 税率与发票（阶段 3）——
// 税号一律占位（`****`）：合规资料不落前端 mock。
export const taxSettings: TaxSetting[] = [
  { country: "AE", countryName: "阿联酋", taxName: "VAT", ratePercent: 5, trn: "1000****00003", invoiceTitle: "ShareHub FZ-LLC", includedInPrice: true, effectiveFrom: "2026-01-01" },
  { country: "SA", countryName: "沙特", taxName: "ZATCA VAT", ratePercent: 15, trn: "3000****00003", invoiceTitle: "ShareHub Arabia LLC", includedInPrice: true, effectiveFrom: "2026-04-01" },
  { country: "QA", countryName: "卡塔尔", taxName: "VAT（待立法）", ratePercent: 0, trn: "-", invoiceTitle: "ShareHub Qatar", includedInPrice: false, effectiveFrom: "2027-01-01" },
  { country: "KW", countryName: "科威特", taxName: "VAT（待立法）", ratePercent: 0, trn: "-", invoiceTitle: "ShareHub Kuwait", includedInPrice: false, effectiveFrom: "2027-01-01" },
  { country: "EG", countryName: "埃及", taxName: "VAT", ratePercent: 14, trn: "200-***-456", invoiceTitle: "ShareHub Egypt LLC", includedInPrice: false, effectiveFrom: "2027-01-01" },
];
export const listTaxSettings = (q: PageQuery = {}) =>
  paginate(taxSettings, q.page, q.size, (x) => kwHit(q.keyword, x.country, x.countryName, x.taxName, x.invoiceTitle));
export const saveTaxSetting = (x: Partial<TaxSetting>) =>
  upsert(taxSettings, x, "country", () => nextNo("XX", taxSettings, 0));
