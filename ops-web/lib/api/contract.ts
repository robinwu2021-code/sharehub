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
} from "../types";

export interface PageQ { page?: number; size?: number; keyword?: string; [k: string]: unknown; }
export type CabinetQ = PageQ & { onlineStatus?: string; status?: string };
export type OrderQ = PageQ & { status?: string };
export type WoQ = PageQ & { status?: string; type?: string };

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
  auditWithdrawal(withdrawNo: string, approve: boolean): Promise<{ ok: true }>;
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
}
