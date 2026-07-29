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
  email: string; // 登录/通知邮箱
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
  // —— 资金审批合规四件套（对标补齐）——
  fee: number; // 提现手续费（与 amount 同币种，实际到账 = amount - fee）
  auditorName: string | null; // 审批人（未审批为 null）
  auditedAt: string | null; // 审批时间
  rejectReason: string | null; // 驳回原因（驳回必填）
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
  // —— 用户价值画像（对标补齐：钱包页直接看消费/充值贡献）——
  orderCount: number; // 累计订单数
  orderAmount: number; // 累计订单金额
  rechargeCount: number; // 累计充值次数
  rechargeAmount: number; // 累计充值金额
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

// —— 售后处置（订单域 · P1，对标简电云 B1/B2）——
// 投诉问题类型：计费争议 / 未弹出 / 未归还 / 设备故障 / 其他
export type ComplaintIssueType = "BILLING_DISPUTE" | "NOT_EJECTED" | "NOT_RETURNED" | "DEVICE_FAULT" | "OTHER";
// 处理结果：退款 / 补偿 / 驳回 / 已解释
export type ComplaintResolution = "REFUND" | "COMPENSATE" | "REJECT" | "EXPLAINED";

// 投诉订单：比竞品多 workOrderNo —— 投诉可直接转工单，投诉/订单/工单三者串通（对方投诉与工单不通）。
export interface OrderComplaint {
  complaintNo: string;
  orderNo: string; // 关联租借订单（引用 orders mock 真实单号）
  userNo: string;
  issueType: ComplaintIssueType;
  description: string; // 用户描述
  screenshotUrl: string | null; // 投诉截图（列表渲染为「查看」链接）
  submittedAt: string;
  status: "PENDING" | "PROCESSING" | "RESOLVED" | "REJECTED";
  handlerName: string | null; // 处理人
  handledAt: string | null;
  resolution: ComplaintResolution | null; // 处理结果
  resolutionNote: string; // 处理说明
  workOrderNo: string | null; // 转工单后回填
}

// 退款记录：独立审批队列（申请→审批→执行），比竞品多一条审批链。
// idempotencyKey / psgTxnNo 是资金操作可追溯的底线：前者防重复退款，后者对得上 PSP 流水。
export interface RefundRecord {
  refundNo: string;
  orderNo: string;
  userNo: string;
  amount: number;
  currency: string;
  reason: string;
  applicantName: string; // 申请人（客服/用户/系统）
  appliedAt: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "EXECUTED" | "FAILED";
  auditorName: string | null; // 审批人
  auditedAt: string | null;
  rejectReason: string | null; // 驳回原因（驳回必填）
  idempotencyKey: string; // 幂等键（同一键只退一次）
  psgTxnNo: string | null; // PSP 支付流水号（未执行时为空）
}

// —— 告警治理（设备运营域 · P1，对标简电云 A1~A4）——
// 告警等级：提示 / 警告 / 严重
export type AlarmLevel = "INFO" | "WARN" | "CRITICAL";

// 告警记录：多厂商错误码归一化 —— alarmCode 是平台统一码，vendorErrorCode 是厂商原始码。
export interface AlarmRecord {
  alarmNo: string;
  cabinetNo: string;
  siteName: string;
  vendorCode: string; // 设备厂商（cd-tech / sd-power / chargenow）
  alarmCode: string; // 平台统一告警码，如 SLOT_STUCK
  vendorErrorCode: string; // 厂商原始错误码，各家风格不同（E203 / ERR-17 / 0x1F04）
  level: AlarmLevel;
  occurredAt: string;
  status: "OPEN" | "ACKED" | "CLOSED"; // 待处理 / 已受理 / 已关闭
  workOrderNo: string | null; // 关联工单号（转工单后回填）
  remark: string;
}

// 告警通知：触达流水（谁/何时/何渠道/成功失败）
export interface AlarmNotice {
  noticeNo: string;
  alarmNo: string;
  channel: "SMS" | "EMAIL" | "PUSH" | "WEBHOOK";
  target: string; // 接收人（手机号/邮箱/工号/回调地址）
  sentAt: string;
  status: "SENT" | "FAILED";
  failReason: string | null;
}

// 告警代码字典：比竞品多「建议处置」「是否自动开工单」——字典即处置预案
export interface AlarmCode {
  code: string;
  message: string;
  level: AlarmLevel;
  suggestion: string; // 建议处置
  autoWorkOrder: boolean; // 命中后是否自动开工单
}

// 通知规则：比竞品多「静默窗口」「升级策略」——防夜间轰炸与告警风暴
export interface AlarmRule {
  ruleNo: string;
  alarmCode: string;
  target: string; // 通知目标（角色/人/群）
  channel: AlarmNotice["channel"];
  method: "INSTANT" | "DIGEST"; // 即时 / 汇总
  quietStart: string; // 静默窗口起（HH:mm）
  quietEnd: string; // 静默窗口止（HH:mm）
  escalateMinutes: number; // N 分钟未处理则升级（0=不升级）
  status: "ACTIVE" | "INACTIVE";
}

// —— 公告管理（营销域 · P1，对标简电云 E1）——
// c-app 首页 Hub 的「公告条」需要运营端发布口（原功能清单遗漏）。
// 三语（zh/en/ar）+ 生效期 + 置顶：竞品公告只有单语，我们要覆盖 MENA 多语市场。
export interface Notice {
  noticeNo: string;
  title: string; // 中文标题
  titleEn: string;
  titleAr: string;
  content: string; // 中文正文
  contentEn: string;
  contentAr: string;
  type: "SYSTEM" | "PROMO" | "MAINTENANCE"; // 系统公告 / 活动公告 / 维护公告
  pinned: boolean; // 置顶（C 端公告条优先展示）
  startAt: string; // 生效期起
  endAt: string; // 生效期止
  status: "DRAFT" | "PUBLISHED" | "OFFLINE"; // 草稿 / 已发布 / 已下线
  publishedBy: string;
  createdAt: string;
}

// —— 支付渠道（系统域 · P1，对标简电云 E8）——
// 竞品把 Stripe/Paypal/Braintree/Yedpay/ABA/Selcom 七个渠道各占一个菜单；
// 我们合并为一页：渠道列表 + 各自配置抽屉 —— 少菜单噪音即「信息更清晰」。
export interface PaymentChannel {
  channelCode: string; // NEARPAY / STRIPE / PAYPAL ...
  channelName: string;
  mode: "DELEGATED" | "DIRECT"; // 委托（聚合/代收）/ 直连（自有商户号）
  status: "ENABLED" | "DISABLED";
  countries: string; // 适用国家，ISO alpha-2 逗号分隔，如 "AE,SA"
  currencies: string; // 币种，逗号分隔，如 "AED,SAR"
  capabilities: string; // 能力：支付/退款/预授权/分账，逗号分隔
  apiBase: string;
  merchantId: string;
  apiKeyMasked: string; // 密钥仅掩码展示（真实密钥永不落前端，占位 sk_test_****）
  updatedAt: string;
}

// ============================================================================
// 系统设置 · 批次 B2/B3/B5 待建 8 项（TDD-运营端对标补齐-待建功能规格 §9~§16）
// 追加式：本区块只增不改，勿与上方内容混排。
// ============================================================================

// —— §9 发送记录（系统域 · 阶段 2，对标简电云「短信记录」）——
// 我们更清晰：全渠道（短信/邮件/Push/WhatsApp）而非仅短信，且每条带计费——OTP 是真金白银。
export type NotifyLogChannel = "SMS" | "EMAIL" | "PUSH" | "WHATSAPP";
export interface NotifyLog {
  logNo: string;
  channel: NotifyLogChannel;
  templateNo: string; // 关联通知模板（NT1xx）
  target: string; // 目标（手机/邮箱/push token），**已脱敏中间位**，前端不承载完整联系方式
  scene: string; // 场景：OTP / 订单完成 / 告警 …
  sentAt: string;
  status: "SENT" | "FAILED";
  failReason: string | null;
  cost: number; // 单条计费
  currency: string; // 计费币种（AED）
}
/** 发送记录页头统计（今日发送量 / 失败率 / 今日成本）。 */
export interface NotifyLogStats {
  sentToday: number;
  failedToday: number;
  failRate: number; // 0~1
  costToday: number;
  currency: string;
}

// —— §10 触达拉黑（系统域 · 阶段 2，对标简电云「短信拉黑」）——
// 我们更清晰：全渠道拉黑（含 ALL），且原因是枚举而非自由文本，便于统计退订来源。
export type NotifyBlockReason = "USER_OPT_OUT" | "HARD_BOUNCE" | "ABUSE" | "MANUAL";
export interface NotifyBlacklist {
  blockNo: string; // 业务键（target+channel 是自然键，但复合键不便做行键/编辑，故另立单号）
  target: string; // 号码 / 邮箱
  // 规格 §10 写的是 SMS/EMAIL/PUSH/ALL；这里补上 WHATSAPP —— §9 发送记录已是全渠道，
  // 拉黑若少一个渠道就等于该渠道不可拉黑，与"全渠道拉黑"的对标结论矛盾。
  channel: NotifyLogChannel | "ALL";
  reason: NotifyBlockReason;
  blockedAt: string;
  blockedBy: string;
  expireAt: string | null; // 空 = 永久；「解除」即把到期时间置为当下（软删除，保留审计痕迹）
}

// —— §11 业务规则（系统域 · 阶段 2）——
// 竞品拆「提现设置 / 预约设置 / 充电设置」三个菜单；我们合并为一页三分区，各自保存。
export interface WithdrawRule {
  minAmount: number; // 最低提现额
  feeRate: number; // 手续费率 0~1
  feeCap: number; // 手续费封顶
  settleDays: number; // 结算周期 T+N
  dailyLimit: number; // 单日限额
  needApproval: boolean; // 是否需人工审批
}
export interface ReservationRule {
  maxDurationMin: number; // 预约时长上限（分）
  advanceHours: number; // 提前预约上限（小时）
  holdFeePerMin: number; // 超时未取占位费（元/分）
  maxConcurrent: number; // 单用户同时预约上限
}
export interface BillingDefaultRule {
  freeMinutes: number; // 默认免费时长（分）
  billUnitMinutes: number; // 默认计费单位（分）
  dailyCap: number; // 默认日封顶
  buyoutPrice: number; // 默认买断价
  overdueHours: number; // 超时判定阈值（小时）
}
export interface BizRules {
  withdraw: WithdrawRule;
  reservation: ReservationRule;
  billing: BillingDefaultRule;
  currency: string;
  updatedAt: string;
}

// —— §12 登录设置（系统域 · 阶段 2）——
// 竞品「登录设置 / 第三方登录」两个菜单；我们合并且按国家可配（MENA 多国监管差异大）。
export interface LoginSetting {
  country: string; // ISO alpha-2；`*` = 默认档（置顶）
  countryName: string;
  otpEnabled: boolean;
  passwordEnabled: boolean;
  appleEnabled: boolean;
  googleEnabled: boolean;
  otpExpireSec: number;
  otpDailyLimit: number;
  forceRealName: boolean; // 强制实名（沙特等地合规要求）
}

// —— §13 应用版本（系统域 · 阶段 2）——
// 竞品只有单一版本；我们按平台分 + 灰度比例 + 三语更新说明。
export interface AppVersion {
  versionId: string; // `IOS-1.4.2`：versionNo 在不同平台会重复，故以 平台-版本 为业务键
  versionNo: string;
  platform: "IOS" | "ANDROID" | "H5";
  buildNo: number;
  releaseNote: string;
  releaseNoteEn: string;
  releaseNoteAr: string;
  forceUpdate: boolean;
  minSupported: string; // 最低支持版本（强更时必填）
  rolloutPercent: number; // 灰度比例 0~100
  downloadUrl: string;
  status: "DRAFT" | "RELEASED" | "ROLLBACK";
  releasedAt: string | null;
}

// —— §14 银行管理（系统域 · 阶段 2）——
// 竞品只有行名；我们带国家/币种/IBAN 长度——提现收款账户校验直接读这里。
export interface BankEntry {
  bankCode: string;
  bankName: string;
  bankNameEn: string;
  country: string;
  currency: string;
  swiftPrefix: string;
  ibanLength: number; // IBAN 位数（含国家码），用于提现账户格式校验
  status: "ENABLED" | "DISABLED";
}

// —— §15 问题管理（系统域 · 阶段 2，对标简电云 FAQ）——
// 我们更清晰：三语 + 关联建议处置，直接喂 C 端报障下拉与客服快捷答复。
export type ProblemCategory = "RENT" | "RETURN" | "BILLING" | "DEVICE" | "ACCOUNT" | "OTHER";
export type ProblemAction = "SELF_SERVICE" | "TO_WORKORDER" | "TO_REFUND" | "TO_CS";
export interface ProblemEntry {
  problemNo: string;
  category: ProblemCategory;
  title: string;
  titleEn: string;
  titleAr: string;
  answer: string;
  answerEn: string;
  answerAr: string;
  suggestedAction: ProblemAction;
  sortNo: number;
  status: "ENABLED" | "DISABLED";
}

// —— §16 税率与发票（系统域 · 阶段 3）——
// 竞品「发票设置」只有税率；我们做国家维度配置，与现有「发票」列表互补。
export interface TaxSetting {
  country: string; // ISO alpha-2（业务键）
  countryName: string;
  taxName: string; // VAT / ZATCA VAT …
  ratePercent: number;
  trn: string; // 税号（UAE TRN 15 位）
  invoiceTitle: string; // 默认开票抬头
  includedInPrice: boolean; // 价内税 / 价外税（影响 C 端计费展示）
  effectiveFrom: string;
}

// ============================================================================
// 财务域 · 批次 B5 待建 2 项（TDD-运营端对标补齐-待建功能规格 §5 §6）
// 追加式：本区块只增不改。
// ============================================================================

// —— §5 分润统计（财务域 · 阶段 2，对标简电云「佣金统计」）——
// 竞品把佣金统计按「运营商 / 商户」切成两套菜单两张表；我们做**一张表 + 顶部维度切换器**，
// 同一套列、同一次查询，少一次跳转——这是「信息更清晰」的正例。
export interface ShareSummary {
  /** 主体维度：与 ShareRule / ShareRecord 的 dimension 同枚举，切换器切的就是它 */
  dimension: "VENUE" | "AGENT";
  payeeNo: string; // 场地方 VEN3xx / 代理商 AG00x
  payeeName: string;
  period: string; // 统计周期 `2026-07`
  orderCount: number;
  gmv: number; // 交易额
  shareAmount: number; // 分润额
  settledAmount: number; // 已结算（≤ 分润额）
  pendingAmount: number; // 待结算 = 分润额 − 已结算（列表高亮列：财务最关心的数）
  currency: string;
}

// —— §6 充值订单（财务域 · 阶段 3）——
// 归「用户账」分组，与钱包同主体。channelCode 取值必须与 系统设置·支付渠道 的 channelCode 对得上。
export interface RechargeOrder {
  rechargeNo: string;
  userNo: string; // U30xx
  nickname: string; // 冗余展示用：列表不必再跳用户页确认是谁
  packageNo: string | null; // 充值套餐；null = 自定义金额
  payAmount: number; // 实付
  giftAmount: number; // 赠送
  creditAmount: number; // 到账 = 实付 + 赠送
  currency: string;
  channelCode: string; // NEARPAY / STRIPE / TAP …（关联 PaymentChannel.channelCode）
  status: "PENDING" | "PAID" | "FAILED" | "REFUNDED";
  createdAt: string; // 下单时间：PENDING/FAILED 无 paidAt，日期范围筛选一律以本字段为准
  paidAt: string | null;
  psgTxnNo: string | null; // 支付网关流水号；未支付为空
}

// ============================================================================
// 批次 B4/B5 · 设备日志 / 设备编码 / 预约订单 / 免费订单 / 免费用户白名单 / 充值套餐
// 规格：docs/technical/TDD-运营端对标补齐-待建功能规格.md §1 §2 §3 §4 §7 §8
// ============================================================================

// —— 设备日志（阶段 2）——
// 对标竞品「充电桩日志」（只有设备上报）。我们做**双流合一**：指令下发(COMMAND/DOWN)
// 与设备上报(REPORT/UP) 在同一时间轴，排障时因果可见。
export interface DeviceLog {
  logNo: string;
  cabinetNo: string;
  stream: "COMMAND" | "REPORT"; // 双流标识：下发 / 上报
  direction: "DOWN" | "UP"; // 方向，与 stream 对应
  eventType: string; // EJECT / HEARTBEAT / SLOT_STATE / FW_UPGRADE ...
  payload: string; // 报文（JSON 字符串；列表行内截断，展开看全文）
  vendorCode: string;
  occurredAt: string;
  result: "OK" | "TIMEOUT" | "FAILED";
}

// —— 设备编码（阶段 2）——
// 对标竞品「充电桩编码」（平铺列表）。我们按**批次 + 供应商**归集，并跟踪绑定进度。
export interface DeviceCodeBatch {
  batchNo: string;
  vendorCode: string;
  codeType: "QR" | "SN"; // 二维码 / 出厂序列号
  rangeStart: string;
  rangeEnd: string;
  total: number;
  bound: number; // 已绑定数（列表显示 已绑定/总数 + 进度条）
  producedAt: string;
  status: "PENDING" | "PARTIAL" | "BOUND" | "VOID";
}

// —— 预约订单（阶段 2）——
// 竞品是电车预约充电桩；充电宝映射为「预约取宝 / 预约还位」（热门点位高峰占位）。
export interface Reservation {
  reservationNo: string;
  userNo: string;
  type: "BORROW" | "RETURN"; // 预约取宝 / 预约还位
  siteNo: string;
  siteName: string;
  cabinetNo: string | null; // 指定机柜（空 = 站点级预约）
  reservedFrom: string;
  reservedTo: string;
  holdFee: number; // 占位费（超时未取产生；规则在业务规则页配置）
  currency: string; // 规格未列，金额展示统一需要币种（AED）
  status: "PENDING" | "FULFILLED" | "EXPIRED" | "CANCELLED";
  orderNo: string | null; // 履约后关联的租借订单
}

// 免费来源：白名单用途枚举（免费订单与白名单共用，保证两页口径一致）
export type WhitelistReason = "INTERNAL_TEST" | "VIP" | "BD_DEMO" | "MERCHANT_SELF";

// —— 免费订单（阶段 2）——：与免费用户白名单联动，页头做成本管控统计
export interface FreeOrder {
  orderNo: string; // 复用租借订单号
  userNo: string;
  nickname: string;
  whitelistReason: WhitelistReason; // 免费来源
  waivedAmount: number; // 减免金额
  currency: string; // 规格未列，money() 需要（AED）
  siteName: string;
  cabinetNo: string;
  startedAt: string;
  endedAt: string;
  duration: number; // 时长（分）
}

/** 免费订单页头统计（本月免费单数 / 累计减免金额），成本管控口径。 */
export interface FreeOrderStats {
  monthCount: number;
  waivedTotal: number;
  currency: string;
}

// —— 免费用户白名单（阶段 2）——
// 竞品「免费用户」放订单域；我们归**用户域**（它本质是用户属性），并强制标注用途。
export interface FreeUserWhitelist {
  userNo: string;
  nickname: string;
  phone: string;
  reason: WhitelistReason; // 必填用途（枚举，非自由文本）
  quotaType: "UNLIMITED" | "TIMES" | "AMOUNT";
  quotaValue: number; // 额度（次数 / 金额）
  usedValue: number; // 已用
  validFrom: string;
  validTo: string;
  grantedBy: string; // 授予人（审计用）
  status: "ACTIVE" | "EXPIRED" | "REVOKED";
}

// —— 充值套餐（阶段 3）——
// 竞品只有「充值 + 赠送」；我们加**有效期**与**适用市场**（MENA 多国家）。
export interface RechargePackage {
  packageNo: string;
  name: string;
  payAmount: number; // 充值金额
  giftAmount: number; // 赠送金额
  currency: string;
  markets: string; // 适用市场，ISO alpha-2 逗号分隔，如 "AE,SA"
  validDays: number; // 赠送金额有效期（天）
  sortNo: number;
  status: "ENABLED" | "DISABLED";
}
