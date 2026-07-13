// powerbank 运营端领域类型（镜像 docs/api + powerbank-common-api 契约）。
// 后端就绪后由 openapi 生成替换，避免漂移（tech-stack-frontend §六）。

// 契约对齐 neargo-common-core（TDD-接入层分端与common复用 envelope 方案①）。
export interface Result<T> {
  code: number;
  message: string;
  data: T;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  page?: number;
  size?: number;
}

export interface PageQuery {
  page?: number;
  size?: number;
  keyword?: string;
  [k: string]: unknown;
}

// —— 设备（ops 域）——
export type OnlineStatus = "ONLINE" | "OFFLINE";
export type CabinetStatus = "DEPLOYED" | "FAULT" | "RETIRED";
export interface Cabinet {
  cabinetNo: string;
  sn: string;
  vendorCode: string;
  model: string;
  locationNo: string | null;
  locationName?: string | null;
  slotTotal: number;
  availableCount: number; // 可借（在仓充电宝数）
  onlineStatus: OnlineStatus;
  status: CabinetStatus;
  fwVersion: string;
  lastHeartbeatAt: string | null;
}

export type SlotLock = "LOCKED" | "UNLOCKED";
export interface Slot {
  slotIndex: number;
  powerbankNo: string | null;
  battery: number | null;
  lockStatus: SlotLock;
  health: "OK" | "FAULT";
}

export type PowerbankStatus =
  | "IN_STOCK" | "DEPLOYED" | "IN_USE" | "RETURNED" | "SCRAP" | "LOST";

// —— 订单（trade 域）——
export type OrderStatus =
  | "CREATED" | "DISPENSING" | "IN_USE" | "RETURNED" | "SETTLED" | "CLOSED" | "EXCEPTION";
export interface RentOrder {
  orderNo: string;
  cUserNo: string;
  cabinetNo: string;
  returnCabinetNo: string | null;
  powerbankNo: string | null;
  locationName?: string | null;
  status: OrderStatus;
  rentStartAt: string | null;
  rentEndAt: string | null;
  durationMin: number | null;
  feeAmount: number;
  depositAmount: number;
  currency: string;
}

// —— 工单（ops 域）——
export type WorkOrderType =
  | "FAULT" | "REFILL" | "INSPECT" | "INSTALL" | "REMOVE" | "COMPLAINT" | "CLEAN";
export type WorkOrderStatus =
  | "CREATED" | "DISPATCHED" | "ACCEPTED" | "PROCESSING" | "DONE" | "AUDITED" | "CLOSED";
export interface WorkOrder {
  woNo: string;
  type: WorkOrderType;
  source: "ALERT" | "USER" | "VENUE" | "MANUAL";
  priority: "LOW" | "MEDIUM" | "HIGH";
  cabinetNo: string | null;
  locationName?: string | null;
  status: WorkOrderStatus;
  assigneeName: string | null;
  slaDueAt: string | null;
  description: string;
  createdAt: string;
}

// —— 租户（platform 域）——
export interface Tenant {
  tenantNo: string;
  name: string;
  brandName: string;
  status: "ENABLED" | "SUSPENDED";
  plan: string;
  cabinetCount: number;
  expireAt: string | null;
}

// —— 员工（platform 域）——
export interface Employee {
  employeeNo: string;
  name: string;
  phone: string;
  deptName: string | null;
  roleName: string;
  status: "ACTIVE" | "LEFT";
}

// —— 工作台 ——
export interface DashboardAlert {
  id: string;
  type: "OFFLINE" | "EXCEPTION" | "TIMEOUT";
  cabinetNo: string;
  message: string;
  href: string;
}
export interface DashboardRankItem {
  rank: number;
  siteName: string;
  gmv: number;
  orderCount: number;
  currency: string;
}
export interface DashboardStats {
  gmvToday: number;
  ordersToday: number;
  activeCabinets: number;
  onlineRate: number; // 0..1
  openWorkOrders: number;
  currency: string;
  trend: { day: string; gmv: number; orders: number }[];
  todos: { pendingWorkOrders: number; pendingRefunds: number; pendingWithdrawals: number };
  alerts: DashboardAlert[];
  rankings: DashboardRankItem[];
}

// —— 场所：场地方 → 站点 → 点位（ADR-013 两层）——
export interface Site {
  siteNo: string;
  name: string;
  venueName: string;
  agentNo: string | null; // 归属代理，空=平台直营
  regionId: string;
  address: string;
  sceneType: string;
  pointCount: number;
  cabinetCount: number;
  status: "ACTIVE" | "PAUSED";
}
export interface Location {
  locationNo: string; // 点位
  name: string;
  siteNo: string;
  siteName: string;
  spotDesc: string;
  cabinetCount: number;
  status: "ACTIVE" | "PAUSED";
}
export interface Venue {
  venueNo: string;
  name: string;
  contact: string;
  industry: string;
  locationCount: number;
}
export interface Contract {
  contractNo: string;
  venueName: string;
  siteName: string; // 合同绑定 场地方 × 站点（ADR-013）
  shareRate: number; // 0..1
  entryFee: number;
  startAt: string;
  endAt: string;
  status: "ACTIVE" | "EXPIRED";
}

// —— 财务 · 分润 · 结算（trade 域）——
export interface ShareRule {
  ruleNo: string;
  dimension: "VENUE" | "AGENT";
  payeeName: string;
  mode: "CHANNEL_SPLIT" | "LEDGER";
  rate: number; // 0..1
  priority: number;
}
export interface Settlement {
  settleNo: string;
  payeeType: "VENUE" | "AGENT";
  payeeName: string;
  period: string;
  totalAmount: number;
  currency: string;
  status: "GEN" | "CONFIRMED" | "PAID";
}
export interface Withdrawal {
  withdrawNo: string;
  payeeName: string;
  amount: number;
  currency: string;
  status: "APPLY" | "AUDIT" | "PAYING" | "PAID" | "FAILED";
  appliedAt: string;
}

// —— 账务分录（复式记账，trade 域）——
export interface LedgerEntry {
  entryNo: string;
  voucherNo: string;
  orderNo: string | null;
  account: string; // 账户：现金/应付场地方/应付代理/平台收入…
  direction: "DEBIT" | "CREDIT"; // 借/贷
  amount: number;
  currency: string;
  summary: string;
  createdAt: string;
}

// —— 供应商接入（access-gateway / gw 域）——
export type AccessMode = "TCP" | "MQTT" | "HTTP_API";
export interface Vendor {
  vendorCode: string;
  name: string;
  accessMode: AccessMode;
  status: "ENABLED" | "DISABLED";
  apiBase: string | null;
  deviceCount: number;
}

// —— C 端用户（user 域）——
export interface CUser {
  cUserNo: string;
  nickname: string;
  phone: string;
  creditScore: number;
  blacklisted: boolean;
  orders: number;
  registeredAt: string;
}

// —— 营销：优惠券（user 域）——
export interface Coupon {
  couponNo: string;
  name: string;
  type: "CUT" | "DISCOUNT";
  value: number;
  threshold: number;
  stock: number;
  issued: number;
  status: "ACTIVE" | "PAUSED";
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

// —— 租户配置（platform 域，后端兼容层，产品不体现）——
export interface TenantConfig {
  tenantNo: string;
  brandName: string;
  paymentProvider: string; // nearpay（委托，ADR-005）
  currency: string;
  freeMinutes: number;
  capTotal: number;
  enabledVendors: string[];
}

// —— 代理商（agt 域，ADR-012）——
export interface Agent {
  agentNo: string;
  name: string;
  contact: string;
  regionScope: string;
  shareRate: number; // 默认分润比例 0..1
  cabinetCount: number;
  status: "ENABLED" | "SUSPENDED";
}

// —— 计费模板（trade 域）——
export interface PricePlan {
  planNo: string;
  name: string;
  freeMinutes: number;
  unitMinutes: number;
  unitPrice: number;
  capDaily: number;
  capTotal: number; // 买断价
  currency: string;
  scope: string; // 默认/点位/场景
  status: "ACTIVE" | "DISABLED";
}

// —— 设备 · 待建功能补全（ops/gw 域）——
export interface Powerbank {
  powerbankNo: string;
  cabinetNo: string;
  battery: number; // 0..100
  status: "IN_CABINET" | "RENTED" | "FAULT" | "RETIRED";
  health: "OK" | "FAULT";
  cycles: number;
}
export interface CabinetMonitor {
  cabinetNo: string;
  locationName: string;
  online: boolean;
  heartbeatAt: string;
  signal: number; // 0..100
  temp: number;
  faultCount: number;
}
export interface CommandRecord {
  commandId: string;
  cabinetNo: string;
  type: "EJECT" | "LOCK" | "REBOOT" | "LOCATE";
  slotIndex: number | null;
  status: "SENT" | "ACKED" | "TIMEOUT" | "FAILED";
  operator: string;
  createdAt: string;
}
export interface InventoryTransfer {
  transferNo: string;
  fromLocation: string;
  toLocation: string;
  powerbankCount: number;
  status: "DRAFT" | "IN_TRANSIT" | "DONE";
  operator: string;
  createdAt: string;
}
export interface OtaRollout {
  rolloutNo: string;
  fwVersion: string;
  vendorCode: string;
  strategy: "GRAY" | "FULL";
  progress: number; // 0..100
  status: "PENDING" | "RUNNING" | "DONE" | "ROLLBACK";
  createdAt: string;
}

// —— 工单 · 待建功能补全（ops 域）——
export interface SlaRule {
  slaNo: string;
  woType: string;
  responseMins: number;
  resolveMins: number;
  escalateTo: string;
  active: boolean;
}
export interface InspectionPlan {
  planNo: string;
  route: string;
  frequency: string;
  nextAt: string;
  assignee: string;
  active: boolean;
}

// —— 场所 · 待建功能补全（ops 域）——
export interface Lead {
  leadNo: string;
  venueName: string;
  contact: string;
  stage: "NEW" | "CONTACTED" | "NEGOTIATING" | "SIGNED" | "LOST";
  owner: string;
  expectSites: number;
  updatedAt: string;
}
export interface SiteAnalysis {
  siteNo: string;
  siteName: string;
  revenue: number;
  orders: number;
  turnover: number; // 次/日
  paybackDays: number;
  cabinetCount: number;
  currency: string;
}

// —— 代理商 · 待建功能补全（agt 域）——
export interface AgentAssignment {
  agentNo: string;
  agentName: string;
  region: string;
  cabinetCount: number;
  siteCount: number;
}
export interface AgentPerformance {
  agentNo: string;
  agentName: string;
  gmv: number;
  cabinetCount: number;
  onlineRate: number; // 0..1
  rank: number;
  currency: string;
}
export interface AgentAccount {
  accountNo: string;
  agentNo: string;
  agentName: string;
  loginPhone: string;
  status: "ACTIVE" | "DISABLED";
  dataScope: string;
  createdAt: string;
}

// —— 订单 · 待建功能补全（trade 域）——
export interface OrderException {
  orderNo: string;
  type: "NOT_EJECTED" | "NOT_RETURNED" | "OVERTIME_BUYOUT" | "DOUBLE_CHARGE";
  cabinetNo: string;
  userNo: string;
  amount: number;
  currency: string;
  status: "OPEN" | "HANDLED";
  createdAt: string;
}

// —— 计费 · 待建功能补全（trade 域）——
export interface PricingDiff {
  ruleNo: string;
  scene: string;
  locationName: string;
  freeMins: number;
  unitPrice: number;
  dayCap: number;
  priority: number;
  currency: string;
}
export interface PricingSchedule {
  ruleNo: string;
  name: string;
  period: string;
  multiplier: number;
  active: boolean;
}

// —— 财务 · 待建功能补全（trade 域）——
export interface ShareRecord {
  recordNo: string;
  orderNo: string;
  dimension: "VENUE" | "AGENT";
  payeeName: string;
  amount: number;
  rate: number; // 0..1
  currency: string;
  createdAt: string;
}
export interface Reconcile {
  batchNo: string;
  period: string;
  nearpayTotal: number;
  ledgerTotal: number;
  diff: number;
  currency: string;
  status: "MATCHED" | "DIFF";
  createdAt: string;
}
export interface Invoice {
  invoiceNo: string;
  payeeName: string;
  amount: number;
  vatTrn: string;
  currency: string;
  status: "DRAFT" | "ISSUED" | "VOID";
  issuedAt: string;
}

// —— 用户 · 待建功能补全（user 域）——
export interface Member {
  userNo: string;
  nickname: string;
  level: "SILVER" | "GOLD" | "PLATINUM";
  points: number;
  cardType: string;
  expireAt: string;
}
export interface Wallet {
  userNo: string;
  nickname: string;
  balance: number;
  bonus: number;
  currency: string;
  updatedAt: string;
}

// —— 营销 · 待建功能补全（user/ad 域）——
export interface Campaign {
  campaignNo: string;
  name: string;
  kind: string;
  rule: string;
  status: "DRAFT" | "RUNNING" | "ENDED";
  startAt: string;
  endAt: string;
}
export interface PushMessage {
  pushNo: string;
  title: string;
  channel: "APP_PUSH" | "SUBSCRIBE";
  audience: string;
  sentCount: number;
  status: "DRAFT" | "SENT";
  sentAt: string;
}
export interface Referral {
  inviteNo: string;
  inviter: string;
  invitee: string;
  reward: number;
  status: "PENDING" | "REWARDED";
  createdAt: string;
  currency: string;
}
export interface AdSlot {
  slotNo: string;
  cabinetNo: string;
  position: "SCREEN" | "BODY";
  size: string;
  status: "IDLE" | "OCCUPIED";
  createdAt: string;
}
export interface AdCampaign {
  adNo: string;
  advertiser: string;
  creative: string;
  targeting: string;
  status: "DRAFT" | "RUNNING" | "ENDED";
  startAt: string;
  endAt: string;
}
export interface AdDelivery {
  deliveryNo: string;
  adNo: string;
  slotNo: string;
  impressions: number;
  plays: number;
  date: string;
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

// —— 客服 · 待建功能补全（cs 域）——
export interface CsTicket {
  ticketNo: string;
  userNo: string;
  cabinetNo: string;
  issue: string;
  channel: string;
  status: "OPEN" | "PROCESSING" | "CLOSED";
  createdAt: string;
}
export interface CsSession {
  sessionNo: string;
  userNo: string;
  agentName: string;
  lastMessage: string;
  status: "ACTIVE" | "CLOSED";
  updatedAt: string;
}

// —— 报表 · 待建功能补全（bi 域）——
export interface ReportDevice {
  locationName: string;
  onlineRate: number; // 0..1
  turnover: number;
  faultRate: number; // 0..1
  cabinetCount: number;
}
export interface ReportLocation {
  siteName: string;
  revenue: number;
  cost: number;
  payback: number;
  roi: number;
  currency: string;
}
export interface ReportFinance {
  period: string;
  gmv: number;
  share: number;
  settle: number;
  net: number;
  currency: string;
}
export interface ReportScreen {
  metric: string;
  value: number;
  unit: string;
  trend: number;
}
export interface ReportCustom {
  dim: string;
  metric: string;
  value: number;
}

// —— 系统 · 待建功能补全（platform 域）——
export interface NotifyTemplate {
  templateNo: string;
  name: string;
  channel: "SMS" | "EMAIL" | "PUSH" | "WHATSAPP";
  lang: "ar" | "en";
  status: "ENABLED" | "DISABLED";
}
export interface DictEntry {
  dictNo: string;
  group: string;
  code: string;
  label: string;
  sort: number;
  enabled: boolean;
}
export interface Region {
  regionId: string;
  name: string;
  parent: string;
  level: number;
  cityCount: number;
}
export interface SysParam {
  paramKey: string;
  label: string;
  value: string;
  groupName: string;
  updatedAt: string;
}
export interface OpenApiApp {
  appNo: string;
  name: string;
  appKey: string;
  rateLimit: number;
  status: "ACTIVE" | "DISABLED";
  createdAt: string;
}

// —— 用户风控与黑名单（用户域 · P2）——
export interface UserRisk {
  riskNo: string;
  userNo: string;
  nickname: string;
  phone: string;
  creditScore: number;
  riskLevel: "HIGH" | "MEDIUM" | "LOW";
  reason: string;
  flaggedAt: string;
}
export interface UserBlacklist {
  blacklistNo: string;
  userNo: string;
  nickname: string;
  phone: string;
  reason: string;
  blacklistedAt: string;
  releasedAt: string | null;
  status: "ACTIVE" | "RELEASED";
}

// —— 代理商分润配置（代理域 · P1）——
export interface AgentCommission {
  ruleNo: string;
  agentNo: string;
  agentName: string;
  dimension: "GMV" | "ORDER_COUNT";
  rate: number; // 0~1
  mode: "CHANNEL_SPLIT" | "LEDGER";
  effectiveAt: string;
  status: "ACTIVE" | "INACTIVE";
}

// —— 门店自助 Onboarding（场地域 · P2）——
export interface VenueOnboarding {
  onboardingNo: string;
  venueName: string;
  contact: string;
  industry: string;
  requestedAt: string;
  status: "PENDING" | "APPROVED" | "REJECTED";
  reviewAt: string | null;
  reviewNote: string | null;
}

// —— 站点生命周期管理（场地域 · P3）——
export interface SiteLifecycle {
  siteNo: string;
  siteName: string;
  stage: "PROSPECTING" | "SIGNED" | "LIVE" | "ACTIVE" | "CHURNED" | "CLOSED";
  stageAt: string;
  owner: string;
  currency: string;
  gmvLtm: number; // 近 12 月 GMV
}

// —— PDF 对照新增（分期屏蔽 P2/P3）——
// 押金与欠费管理（订单域 · P2）
export interface DepositRecord {
  depositNo: string;
  orderNo: string;
  userNo: string;
  amount: number;
  currency: string;
  status: "HELD" | "RELEASED" | "BOUGHT_OUT" | "ARREARS"; // 冻结/已解冻/买断/欠费
  arrearsAmount: number; // 欠费金额（0 表示无欠费）
  createdAt: string;
}
// 多国家市场管理架构（系统域 · P3）
export interface MarketCountry {
  countryCode: string; // ISO alpha-2，如 AE
  name: string;
  currency: string;
  timezone: string;
  compliance: string; // 合规主体/牌照
  cityCount: number;
  status: "LIVE" | "PILOT" | "PLANNED"; // 已开城/试点/规划
}
// 消费者数据分析（报表域 · P3）
export interface ConsumerSegment {
  segmentNo: string;
  segment: string; // 人群/画像名
  userCount: number;
  repeatRate: number; // 复借率 0~1
  avgOrderValue: number;
  currency: string;
}
