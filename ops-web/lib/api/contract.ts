// 单一 API 契约。mock 与真实后端各实现一份（lib/api/mock.ts / http.ts），
// 页面只依赖此接口，切换靠 lib/api/index.ts 一处开关（消除散落的 if(USE_MOCK)）。
import type {
  PageResult, Cabinet, Slot, RentOrder, WorkOrder, Employee, DashboardStats,
  Site, Location, Venue, Contract, ShareRule, Settlement, Withdrawal, Vendor,
  CUser, Coupon, RoleRow, AuditEntry, Agent, PricePlan, LedgerEntry,
  Powerbank, CabinetMonitor, CommandRecord, InventoryTransfer, OtaRollout,
  SlaRule, InspectionPlan, Lead, SiteAnalysis, AgentAssignment, AgentPerformance,
  AgentAccount, OrderException, PricingDiff, PricingSchedule, ShareRecord,
  Reconcile, Invoice, Member, Wallet, Campaign, PushMessage, Referral, AdSlot,
  AdCampaign, AdDelivery, Department, StaffPerformance, CsTicket, CsSession,
  ReportDevice, ReportLocation, ReportFinance, ReportScreen, ReportCustom,
  NotifyTemplate, DictEntry, Region, SysParam, OpenApiApp,
  DepositRecord, MarketCountry, ConsumerSegment,
  UserRisk, UserBlacklist, AgentCommission, VenueOnboarding, SiteLifecycle,
  AlarmRecord, AlarmNotice, AlarmCode, AlarmRule,
  OrderComplaint, RefundRecord, ComplaintResolution,
  Notice, PaymentChannel,
} from "../types";

export interface PageQ { page?: number; size?: number; keyword?: string; [k: string]: unknown; }
export type CabinetQ = PageQ & { onlineStatus?: string; status?: string };
export type OrderQ = PageQ & { status?: string };
export type WoQ = PageQ & { status?: string; type?: string };
export type AlarmQ = PageQ & { level?: string; status?: string };
export type StatusQ = PageQ & { status?: string };

export interface LoginResp { token: string; username: string; role: string; agentNo: string; }

export interface Api {
  // 认证：登录换后端 token（后端据 token 角色鉴权，不认客户端 X-Roles）
  login(username: string, role: string, agentNo?: string): Promise<LoginResp>;
  // 工作台
  getDashboard(): Promise<DashboardStats>;
  // 设备
  listCabinets(q?: CabinetQ): Promise<PageResult<Cabinet>>;
  getCabinet(cabinetNo: string): Promise<{ cabinet: Cabinet; slots: Slot[] }>;
  sendCommand(cabinetNo: string, type: string, params?: Record<string, unknown>): Promise<{ commandId: string }>;
  // 订单
  listOrders(q?: OrderQ): Promise<PageResult<RentOrder>>;
  getOrder(orderNo: string): Promise<RentOrder>;
  interveneOrder(orderNo: string, action: string): Promise<{ ok: true }>;
  // 工单
  listWorkOrders(q?: WoQ): Promise<PageResult<WorkOrder>>;
  dispatchWorkOrder(woNo: string, assignee: string): Promise<{ ok: true }>;
  // 场所：站点 / 点位 / 场地方 / 合同（ADR-013）
  listSites(q?: PageQ): Promise<PageResult<Site>>;
  saveSite(s: Partial<Site> & { siteNo?: string }): Promise<Site>;
  listLocations(q?: PageQ): Promise<PageResult<Location>>;
  savePoint(l: Partial<Location> & { locationNo?: string }): Promise<Location>;
  listVenues(q?: PageQ): Promise<PageResult<Venue>>;
  listContracts(q?: PageQ): Promise<PageResult<Contract>>;
  // 财务
  listShareRules(q?: PageQ): Promise<PageResult<ShareRule>>;
  listLedger(q?: PageQ): Promise<PageResult<LedgerEntry>>;
  listSettlements(q?: PageQ): Promise<PageResult<Settlement>>;
  listWithdrawals(q?: PageQ): Promise<PageResult<Withdrawal>>;
  /** 提现审批：驳回必须带原因；auditorName 取当前登录用户（后端以会话为准，前端透传便于 mock）。 */
  auditWithdrawal(withdrawNo: string, approve: boolean, rejectReason?: string, auditorName?: string): Promise<Withdrawal>;
  // 供应商接入
  listVendors(): Promise<Vendor[]>;
  saveVendor(v: Partial<Vendor> & { vendorCode: string }): Promise<Vendor>;
  // C 端用户 / 营销
  listUsers(q?: PageQ): Promise<PageResult<CUser>>;
  setBlacklist(cUserNo: string, blacklisted: boolean): Promise<{ ok: true }>;
  listCoupons(q?: PageQ): Promise<PageResult<Coupon>>;
  saveCoupon(c: Partial<Coupon> & { couponNo?: string }): Promise<Coupon>;
  // 计费定价
  listPricePlans(q?: PageQ): Promise<PageResult<PricePlan>>;
  // 代理商
  listAgents(q?: PageQ): Promise<PageResult<Agent>>;
  saveAgent(a: Partial<Agent> & { agentNo?: string }): Promise<Agent>;
  // 员工 / 角色 / 审计（无租户管理，租户仅后端兼容层）
  listEmployees(q?: PageQ): Promise<PageResult<Employee>>;
  listRoles(): Promise<RoleRow[]>;
  listAudits(q?: PageQ): Promise<PageResult<AuditEntry>>;

  // === 设备扩展 tab ===
  listPowerbanks(q?: PageQ): Promise<PageResult<Powerbank>>;
  listCabinetMonitor(q?: PageQ): Promise<PageResult<CabinetMonitor>>;
  listCommandRecords(q?: PageQ): Promise<PageResult<CommandRecord>>;
  listInventoryTransfers(q?: PageQ): Promise<PageResult<InventoryTransfer>>;
  listOtaRollouts(q?: PageQ): Promise<PageResult<OtaRollout>>;
  // === 工单扩展 tab ===
  listSlaRules(q?: PageQ): Promise<PageResult<SlaRule>>;
  listInspectionPlans(q?: PageQ): Promise<PageResult<InspectionPlan>>;
  // === 场所扩展 tab ===
  listLeads(q?: PageQ): Promise<PageResult<Lead>>;
  listSiteAnalysis(q?: PageQ): Promise<PageResult<SiteAnalysis>>;
  // === 代理商扩展 tab ===
  listAgentAssignments(q?: PageQ): Promise<PageResult<AgentAssignment>>;
  listAgentPerformance(q?: PageQ): Promise<PageResult<AgentPerformance>>;
  listAgentAccounts(q?: PageQ): Promise<PageResult<AgentAccount>>;
  // === 订单扩展 tab ===
  listOrderExceptions(q?: PageQ): Promise<PageResult<OrderException>>;
  // === 定价扩展 tab ===
  listPricingDiffs(q?: PageQ): Promise<PageResult<PricingDiff>>;
  listPricingSchedules(q?: PageQ): Promise<PageResult<PricingSchedule>>;
  // === 财务扩展 tab ===
  listShareRecords(q?: PageQ): Promise<PageResult<ShareRecord>>;
  listReconciles(q?: PageQ): Promise<PageResult<Reconcile>>;
  listInvoices(q?: PageQ): Promise<PageResult<Invoice>>;
  // === 用户扩展 tab ===
  listMembers(q?: PageQ): Promise<PageResult<Member>>;
  listWallets(q?: PageQ): Promise<PageResult<Wallet>>;
  // === 营销扩展 tab ===
  listCampaigns(q?: PageQ): Promise<PageResult<Campaign>>;
  listPushMessages(q?: PageQ): Promise<PageResult<PushMessage>>;
  listReferrals(q?: PageQ): Promise<PageResult<Referral>>;
  listAdSlots(q?: PageQ): Promise<PageResult<AdSlot>>;
  listAdCampaigns(q?: PageQ): Promise<PageResult<AdCampaign>>;
  listAdDeliveries(q?: PageQ): Promise<PageResult<AdDelivery>>;
  // === 员工扩展 tab ===
  listDepartments(q?: PageQ): Promise<PageResult<Department>>;
  listStaffPerformance(q?: PageQ): Promise<PageResult<StaffPerformance>>;
  // === 客服 tab ===
  listCsTickets(q?: PageQ): Promise<PageResult<CsTicket>>;
  listCsSessions(q?: PageQ): Promise<PageResult<CsSession>>;
  // === 报表 tab ===
  listReportDevice(q?: PageQ): Promise<PageResult<ReportDevice>>;
  listReportLocation(q?: PageQ): Promise<PageResult<ReportLocation>>;
  listReportFinance(q?: PageQ): Promise<PageResult<ReportFinance>>;
  listReportScreen(q?: PageQ): Promise<PageResult<ReportScreen>>;
  listReportCustom(q?: PageQ): Promise<PageResult<ReportCustom>>;
  // === 系统扩展 tab ===
  listNotifyTemplates(q?: PageQ): Promise<PageResult<NotifyTemplate>>;
  listDictEntries(q?: PageQ): Promise<PageResult<DictEntry>>;
  listRegions(q?: PageQ): Promise<PageResult<Region>>;
  listSysParams(q?: PageQ): Promise<PageResult<SysParam>>;
  listOpenApiApps(q?: PageQ): Promise<PageResult<OpenApiApp>>;
  listDepositRecords(q?: PageQ): Promise<PageResult<DepositRecord>>;
  listMarketCountries(q?: PageQ): Promise<PageResult<MarketCountry>>;
  listConsumerSegments(q?: PageQ): Promise<PageResult<ConsumerSegment>>;
  // === 用户风控 ===
  listUserRisks(q?: PageQ): Promise<PageResult<UserRisk>>;
  listUserBlacklist(q?: PageQ): Promise<PageResult<UserBlacklist>>;
  // === 代理分润 ===
  listAgentCommissions(q?: PageQ): Promise<PageResult<AgentCommission>>;
  saveAgentCommission(x: Partial<AgentCommission> & { ruleNo?: string }): Promise<AgentCommission>;
  // === 门店 Onboarding / 生命周期 ===
  listVenueOnboardings(q?: PageQ): Promise<PageResult<VenueOnboarding>>;
  saveVenueOnboarding(x: Partial<VenueOnboarding> & { onboardingNo?: string }): Promise<VenueOnboarding>;
  listSiteLifecycles(q?: PageQ): Promise<PageResult<SiteLifecycle>>;
  // === 告警治理（记录 / 通知流水 / 代码字典 / 通知规则）===
  listAlarmRecords(q?: AlarmQ): Promise<PageResult<AlarmRecord>>;
  listAlarmNotices(q?: PageQ): Promise<PageResult<AlarmNotice>>;
  listAlarmCodes(q?: PageQ): Promise<PageResult<AlarmCode>>;
  listAlarmRules(q?: PageQ): Promise<PageResult<AlarmRule>>;
  saveAlarmCode(x: Partial<AlarmCode> & { code?: string }): Promise<AlarmCode>;
  saveAlarmRule(x: Partial<AlarmRule> & { ruleNo?: string }): Promise<AlarmRule>;
  /** 告警转工单：生成关联工单号并置为已受理，返回更新后的告警记录。 */
  raiseAlarmWorkOrder(alarmNo: string): Promise<AlarmRecord>;

  // === 售后处置（投诉订单 / 退款审批队列）===
  listOrderComplaints(q?: StatusQ): Promise<PageResult<OrderComplaint>>;
  /** 处理投诉：写入处理结果 + 说明，落 RESOLVED/REJECTED。 */
  handleOrderComplaint(complaintNo: string, resolution: ComplaintResolution, note: string): Promise<OrderComplaint>;
  /** 投诉转工单：投诉-订单-工单闭环（竞品此处断链）。 */
  raiseComplaintWorkOrder(complaintNo: string): Promise<OrderComplaint>;
  listRefundRecords(q?: StatusQ): Promise<PageResult<RefundRecord>>;
  /** 退款审批：驳回必须带原因。幂等键由申请侧生成，审批不重发。 */
  auditRefund(refundNo: string, approve: boolean, rejectReason?: string): Promise<RefundRecord>;

  // === 扩展实体 save（照 saveCoupon 写法）===
  savePowerbank(x: Partial<Powerbank> & { powerbankNo?: string }): Promise<Powerbank>;
  saveInventoryTransfer(x: Partial<InventoryTransfer> & { transferNo?: string }): Promise<InventoryTransfer>;
  saveOtaRollout(x: Partial<OtaRollout> & { rolloutNo?: string }): Promise<OtaRollout>;
  saveSlaRule(x: Partial<SlaRule> & { slaNo?: string }): Promise<SlaRule>;
  saveInspectionPlan(x: Partial<InspectionPlan> & { planNo?: string }): Promise<InspectionPlan>;
  saveLead(x: Partial<Lead> & { leadNo?: string }): Promise<Lead>;
  saveVenue(x: Partial<Venue> & { venueNo?: string }): Promise<Venue>;
  saveContract(x: Partial<Contract> & { contractNo?: string }): Promise<Contract>;
  saveAgentAccount(x: Partial<AgentAccount> & { accountNo?: string }): Promise<AgentAccount>;
  savePricePlan(x: Partial<PricePlan> & { planNo?: string }): Promise<PricePlan>;
  savePricingDiff(x: Partial<PricingDiff> & { ruleNo?: string }): Promise<PricingDiff>;
  savePricingSchedule(x: Partial<PricingSchedule> & { ruleNo?: string }): Promise<PricingSchedule>;
  saveShareRule(x: Partial<ShareRule> & { ruleNo?: string }): Promise<ShareRule>;
  saveInvoice(x: Partial<Invoice> & { invoiceNo?: string }): Promise<Invoice>;
  saveMember(x: Partial<Member> & { userNo?: string }): Promise<Member>;
  saveWallet(x: Partial<Wallet> & { userNo?: string }): Promise<Wallet>;
  saveCampaign(x: Partial<Campaign> & { campaignNo?: string }): Promise<Campaign>;
  savePushMessage(x: Partial<PushMessage> & { pushNo?: string }): Promise<PushMessage>;
  saveAdSlot(x: Partial<AdSlot> & { slotNo?: string }): Promise<AdSlot>;
  saveAdCampaign(x: Partial<AdCampaign> & { adNo?: string }): Promise<AdCampaign>;
  saveCsTicket(x: Partial<CsTicket> & { ticketNo?: string }): Promise<CsTicket>;
  saveDepartment(x: Partial<Department> & { deptNo?: string }): Promise<Department>;
  saveRoleRow(x: Partial<RoleRow> & { roleNo?: string }): Promise<RoleRow>;
  saveNotifyTemplate(x: Partial<NotifyTemplate> & { templateNo?: string }): Promise<NotifyTemplate>;
  saveDictEntry(x: Partial<DictEntry> & { dictNo?: string }): Promise<DictEntry>;
  saveRegion(x: Partial<Region> & { regionId?: string }): Promise<Region>;
  saveSysParam(x: Partial<SysParam> & { paramKey?: string }): Promise<SysParam>;
  saveOpenApiApp(x: Partial<OpenApiApp> & { appNo?: string }): Promise<OpenApiApp>;
  saveEmployee(x: Partial<Employee> & { employeeNo?: string }): Promise<Employee>;
  saveMarketCountry(x: Partial<MarketCountry> & { countryCode?: string }): Promise<MarketCountry>;

  // === 公告管理（营销域 · P1，补齐清单 E1）===
  listNotices(q?: PageQ): Promise<PageResult<Notice>>;
  saveNotice(x: Partial<Notice> & { noticeNo?: string }): Promise<Notice>;
  // === 支付渠道（系统域 · P1，补齐清单 E8）===
  listPaymentChannels(q?: PageQ): Promise<PageResult<PaymentChannel>>;
  savePaymentChannel(x: Partial<PaymentChannel> & { channelCode?: string }): Promise<PaymentChannel>;

  // === 财务域 B5：分润统计 / 充值订单（规格 §5 §6，均为只读）===
  /** 分润统计：dimension 是维度切换器的参数——一张表两种主体，不是两个接口。 */
  listShareSummaries(q?: ShareSummaryQ): Promise<PageResult<ShareSummary>>;
  listRechargeOrders(q?: RechargeQ): Promise<PageResult<RechargeOrder>>;

  // === 批次 B4/B5：设备日志 / 设备编码 / 预约订单 / 免费订单 / 白名单 / 充值套餐 ===
  // 规格 §1 §2 §3 §4 §7 §8
  listDeviceLogs(q?: DeviceLogQ): Promise<PageResult<DeviceLog>>;
  listDeviceCodeBatches(q?: PageQ): Promise<PageResult<DeviceCodeBatch>>;
  saveDeviceCodeBatch(x: Partial<DeviceCodeBatch> & { batchNo?: string }): Promise<DeviceCodeBatch>;
  listReservations(q?: ReservationQ): Promise<PageResult<Reservation>>;
  /** 取消预约：仅 PENDING 可取消（后端同样校验，前端按钮先行拦截）。 */
  cancelReservation(reservationNo: string): Promise<Reservation>;
  listFreeOrders(q?: FreeOrderQ): Promise<PageResult<FreeOrder>>;
  /** 免费订单页头统计：本月单数 / 累计减免（成本管控，须为全量口径而非当页）。 */
  getFreeOrderStats(): Promise<FreeOrderStats>;
  listFreeWhitelist(q?: WhitelistQ): Promise<PageResult<FreeUserWhitelist>>;
  saveFreeWhitelist(x: Partial<FreeUserWhitelist> & { userNo?: string }): Promise<FreeUserWhitelist>;
  /** 撤销白名单：软撤销置 REVOKED（决策 §八-4，不物理删）。 */
  revokeFreeWhitelist(userNo: string): Promise<FreeUserWhitelist>;
  listRechargePackages(q?: StatusQ): Promise<PageResult<RechargePackage>>;
  saveRechargePackage(x: Partial<RechargePackage> & { packageNo?: string }): Promise<RechargePackage>;

  // === 批次 B2/B3/B5：系统设置 8 项（规格 §9~§16）===
  listNotifyLogs(q?: NotifyLogQ): Promise<PageResult<NotifyLog>>;
  /** 发送记录页头统计：今日发送量 / 失败率 / 今日成本（全量口径，非当页）。 */
  getNotifyLogStats(): Promise<NotifyLogStats>;
  listNotifyBlacklist(q?: NotifyBlacklistQ): Promise<PageResult<NotifyBlacklist>>;
  saveNotifyBlacklist(x: Partial<NotifyBlacklist> & { blockNo?: string }): Promise<NotifyBlacklist>;
  /** 解除拉黑：软删除，把 expireAt 置为当下并保留记录（决策 §八-4）。 */
  releaseNotifyBlacklist(blockNo: string): Promise<NotifyBlacklist>;
  getBizRules(): Promise<BizRules>;
  /** 业务规则分区保存：只传要改的分区（提现 / 预约 / 计费默认值各自一个保存按钮）。 */
  saveBizRules(x: Partial<BizRules>): Promise<BizRules>;
  listLoginSettings(q?: PageQ): Promise<PageResult<LoginSetting>>;
  saveLoginSetting(x: Partial<LoginSetting> & { country?: string }): Promise<LoginSetting>;
  listAppVersions(q?: AppVersionQ): Promise<PageResult<AppVersion>>;
  saveAppVersion(x: Partial<AppVersion> & { versionId?: string }): Promise<AppVersion>;
  /** 版本回滚：置 ROLLBACK 且灰度归零，记录保留。 */
  rollbackAppVersion(versionId: string): Promise<AppVersion>;
  listBanks(q?: BankQ): Promise<PageResult<BankEntry>>;
  saveBank(x: Partial<BankEntry> & { bankCode?: string }): Promise<BankEntry>;
  listProblems(q?: ProblemQ): Promise<PageResult<ProblemEntry>>;
  saveProblem(x: Partial<ProblemEntry> & { problemNo?: string }): Promise<ProblemEntry>;
  listTaxSettings(q?: PageQ): Promise<PageResult<TaxSetting>>;
  saveTaxSetting(x: Partial<TaxSetting> & { country?: string }): Promise<TaxSetting>;
}

// —— 财务域 B5 追加（独立 import，避免与其他批次抢改顶部 import 块）——
import type { ShareSummary, RechargeOrder } from "../types";
export type ShareSummaryQ = PageQ & {
  dimension?: string; // VENUE / AGENT
  period?: string; // 2026-07
  sortKey?: string; // shareAmount / pendingAmount / gmv / orderCount
  sortDir?: string; // asc / desc
};
export type RechargeQ = PageQ & { status?: string; from?: string; to?: string };

// —— 批次 B4/B5 追加（独立 import，避免与其他批次抢改顶部 import 块）——
import type {
  DeviceLog, DeviceCodeBatch, Reservation, FreeOrder, FreeOrderStats,
  FreeUserWhitelist, RechargePackage,
} from "../types";
/** 设备日志：stream 双流筛选 + 日期范围（YYYY-MM-DD，含端点）。 */
export type DeviceLogQ = PageQ & { stream?: string; from?: string; to?: string };
export type ReservationQ = PageQ & { status?: string; type?: string };
export type FreeOrderQ = PageQ & { reason?: string };
export type WhitelistQ = PageQ & { status?: string; reason?: string };

// —— 批次 B2/B3/B5 系统设置追加（独立 import，避免与其他批次抢改顶部 import 块）——
import type {
  NotifyLog, NotifyLogStats, NotifyBlacklist, BizRules, LoginSetting,
  AppVersion, BankEntry, ProblemEntry, TaxSetting,
} from "../types";
/** 发送记录：渠道/状态筛选 + 受控排序（sort=sentAt|cost）。 */
export type NotifyLogQ = PageQ & { channel?: string; status?: string; sort?: string; dir?: string };
export type NotifyBlacklistQ = PageQ & { channel?: string; reason?: string };
export type AppVersionQ = PageQ & { platform?: string };
export type BankQ = PageQ & { country?: string; currency?: string };
export type ProblemQ = PageQ & { category?: string; status?: string };
