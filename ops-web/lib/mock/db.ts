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
export const withdrawals: Withdrawal[] = Array.from({ length: 20 }, (_, i) => ({
  withdrawNo: `WD${3000 + i}`, payeeName: p([...VENUE_NAMES, "Agent-North"], i), amount: 500 + (i * 211) % 3000,
  currency: "AED", status: p(["APPLY", "AUDIT", "PAYING", "PAID", "FAILED"] as const, i), appliedAt: iso(i * 43200_000),
}));

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
export const wallets: Wallet[] = Array.from({ length: 24 }, (_, i) => ({
  userNo: `U${3000 + i}`, nickname: p(NICKS, i), balance: Number(((i * 7) % 200 + (i % 10) / 10).toFixed(2)),
  bonus: Number(((i * 3) % 50).toFixed(2)), currency: "AED", updatedAt: iso(i * 43200_000),
}));

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
