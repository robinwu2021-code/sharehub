// 真实后端实现（Api 契约）。端点对齐 docs/api/README.md。
import { client } from "./http-client";
import type { Api, CabinetQ, OrderQ, WoQ, PageQ, AlarmQ, StatusQ } from "./contract";

export const httpApi: Api = {
  login: (username, role, agentNo) => client.post("/api/auth/login", { username, role, agentNo }),
  getDashboard: () => client.get("/api/ops/dashboard"),

  listCabinets: (q?: CabinetQ) => client.get("/api/ops/cabinets", q),
  getCabinet: (no) => client.get(`/api/ops/cabinets/${no}`),
  sendCommand: (no, type, params) => client.post(`/api/ops/cabinets/${no}/commands`, { type, params }),

  listOrders: (q?: OrderQ) => client.get("/api/trade/orders", q),
  getOrder: (no) => client.get(`/api/trade/orders/${no}`),
  interveneOrder: (no, action) => client.post(`/api/trade/orders/${no}/intervene`, { action }),

  listWorkOrders: (q?: WoQ) => client.get("/api/ops/work-orders", q),
  dispatchWorkOrder: (no, assignee) => client.post(`/api/ops/work-orders/${no}/dispatch`, { assignee }),

  listSites: (q?: PageQ) => client.get("/api/ops/sites", q),
  saveSite: (s) => client.post(s.siteNo ? `/api/ops/sites/${s.siteNo}` : "/api/ops/sites", s),
  listLocations: (q?: PageQ) => client.get("/api/ops/locations", q),
  savePoint: (l) => client.post(l.locationNo ? `/api/ops/locations/${l.locationNo}` : "/api/ops/locations", l),
  listVenues: (q?: PageQ) => client.get("/api/ops/venues", q),
  listContracts: (q?: PageQ) => client.get("/api/ops/contracts", q),

  listShareRules: (q?: PageQ) => client.get("/api/trade/share-rules", q),
  listLedger: (q?: PageQ) => client.get("/api/trade/ledger", q),
  listSettlements: (q?: PageQ) => client.get("/api/trade/settlements", q),
  listWithdrawals: (q?: PageQ) => client.get("/api/trade/withdrawals", q),
  auditWithdrawal: (no, approve, rejectReason, auditorName) => client.post(`/api/trade/withdrawals/${no}/audit`, { approve, rejectReason, auditorName }),

  listVendors: () => client.get("/internal/gw/vendors"),
  saveVendor: (v) => client.post(`/internal/gw/vendors/${v.vendorCode}/config`, v),

  listUsers: (q?: PageQ) => client.get("/api/user/users", q),
  setBlacklist: (no, blacklisted) => client.post("/internal/user/credit/blacklist", { cUserNo: no, blacklisted }),
  listCoupons: (q?: PageQ) => client.get("/api/user/coupons", q),
  saveCoupon: (c) => client.post(c.couponNo ? `/api/user/coupons/${c.couponNo}` : "/api/user/coupons", c),

  listPricePlans: (q?: PageQ) => client.get("/api/trade/price-plans", q),

  listAgents: (q?: PageQ) => client.get("/api/agent/agents", q),
  saveAgent: (a) => client.post(a.agentNo ? `/api/agent/agents/${a.agentNo}` : "/api/agent/agents", a),

  listEmployees: (q?: PageQ) => client.get("/api/platform/employees", q),
  listRoles: () => client.get("/api/platform/roles"),
  listAudits: (q?: PageQ) => client.get("/api/platform/audit-logs", q),

  // 设备扩展
  listPowerbanks: (q?: PageQ) => client.get("/api/ops/powerbanks", q),
  listCabinetMonitor: (q?: PageQ) => client.get("/api/ops/cabinet-monitor", q),
  listCommandRecords: (q?: PageQ) => client.get("/api/ops/command-records", q),
  listInventoryTransfers: (q?: PageQ) => client.get("/api/ops/inventory-transfers", q),
  listOtaRollouts: (q?: PageQ) => client.get("/api/ops/ota-rollouts", q),
  // 工单扩展
  listSlaRules: (q?: PageQ) => client.get("/api/ops/sla-rules", q),
  listInspectionPlans: (q?: PageQ) => client.get("/api/ops/inspection-plans", q),
  // 场所扩展
  listLeads: (q?: PageQ) => client.get("/api/ops/leads", q),
  listSiteAnalysis: (q?: PageQ) => client.get("/api/ops/site-analysis", q),
  // 代理商扩展
  listAgentAssignments: (q?: PageQ) => client.get("/api/agent/assignments", q),
  listAgentPerformance: (q?: PageQ) => client.get("/api/agent/performance", q),
  listAgentAccounts: (q?: PageQ) => client.get("/api/agent/accounts", q),
  // 订单扩展
  listOrderExceptions: (q?: PageQ) => client.get("/api/trade/order-exceptions", q),
  // 定价扩展
  listPricingDiffs: (q?: PageQ) => client.get("/api/trade/pricing-diffs", q),
  listPricingSchedules: (q?: PageQ) => client.get("/api/trade/pricing-schedules", q),
  // 财务扩展
  listShareRecords: (q?: PageQ) => client.get("/api/trade/share-records", q),
  listReconciles: (q?: PageQ) => client.get("/api/trade/reconciles", q),
  listInvoices: (q?: PageQ) => client.get("/api/trade/invoices", q),
  // 用户扩展
  listMembers: (q?: PageQ) => client.get("/api/user/members", q),
  listWallets: (q?: PageQ) => client.get("/api/user/wallets", q),
  // 营销扩展
  listCampaigns: (q?: PageQ) => client.get("/api/user/campaigns", q),
  listPushMessages: (q?: PageQ) => client.get("/api/user/push-messages", q),
  listReferrals: (q?: PageQ) => client.get("/api/user/referrals", q),
  listAdSlots: (q?: PageQ) => client.get("/api/ops/ad-slots", q),
  listAdCampaigns: (q?: PageQ) => client.get("/api/user/ad-campaigns", q),
  listAdDeliveries: (q?: PageQ) => client.get("/api/user/ad-deliveries", q),
  // 员工扩展
  listDepartments: (q?: PageQ) => client.get("/api/platform/departments", q),
  listStaffPerformance: (q?: PageQ) => client.get("/api/platform/staff-performance", q),
  // 客服
  listCsTickets: (q?: PageQ) => client.get("/api/cs/tickets", q),
  listCsSessions: (q?: PageQ) => client.get("/api/cs/sessions", q),
  // 报表
  listReportDevice: (q?: PageQ) => client.get("/api/report/device", q),
  listReportLocation: (q?: PageQ) => client.get("/api/report/location", q),
  listReportFinance: (q?: PageQ) => client.get("/api/report/finance", q),
  listReportScreen: (q?: PageQ) => client.get("/api/report/screen", q),
  listReportCustom: (q?: PageQ) => client.get("/api/report/custom", q),
  // 系统扩展
  listNotifyTemplates: (q?: PageQ) => client.get("/api/platform/notify-templates", q),
  listDictEntries: (q?: PageQ) => client.get("/api/platform/dict-entries", q),
  listRegions: (q?: PageQ) => client.get("/api/platform/regions", q),
  listSysParams: (q?: PageQ) => client.get("/api/platform/sys-params", q),
  listOpenApiApps: (q?: PageQ) => client.get("/api/platform/openapi-apps", q),
  listDepositRecords: (q?: PageQ) => client.get("/api/order/deposits", q),
  listMarketCountries: (q?: PageQ) => client.get("/api/platform/markets", q),
  listConsumerSegments: (q?: PageQ) => client.get("/api/report/consumer-segments", q),
  // 用户风控
  listUserRisks: (q?: PageQ) => client.get("/api/user/risk-users", q),
  listUserBlacklist: (q?: PageQ) => client.get("/api/user/blacklist", q),
  // 代理分润
  listAgentCommissions: (q?: PageQ) => client.get("/api/agent/commissions", q),
  saveAgentCommission: (x) => client.post(x.ruleNo ? `/api/agent/commissions/${x.ruleNo}` : "/api/agent/commissions", x),
  // 门店 Onboarding / 生命周期
  listVenueOnboardings: (q?: PageQ) => client.get("/api/ops/venue-onboardings", q),
  saveVenueOnboarding: (x) => client.post(x.onboardingNo ? `/api/ops/venue-onboardings/${x.onboardingNo}` : "/api/ops/venue-onboardings", x),
  listSiteLifecycles: (q?: PageQ) => client.get("/api/ops/site-lifecycles", q),
  // 告警治理
  listAlarmRecords: (q?: AlarmQ) => client.get("/api/alarm/records", q),
  listAlarmNotices: (q?: PageQ) => client.get("/api/alarm/notices", q),
  listAlarmCodes: (q?: PageQ) => client.get("/api/alarm/codes", q),
  listAlarmRules: (q?: PageQ) => client.get("/api/alarm/rules", q),
  saveAlarmCode: (x) => client.post(x.code ? `/api/alarm/codes/${x.code}` : "/api/alarm/codes", x),
  saveAlarmRule: (x) => client.post(x.ruleNo ? `/api/alarm/rules/${x.ruleNo}` : "/api/alarm/rules", x),
  raiseAlarmWorkOrder: (no) => client.post(`/api/alarm/records/${no}/work-order`, {}),
  // 售后处置
  listOrderComplaints: (q?: StatusQ) => client.get("/api/order/complaints", q),
  handleOrderComplaint: (no, resolution, note) => client.post(`/api/order/complaints/${no}/handle`, { resolution, note }),
  raiseComplaintWorkOrder: (no) => client.post(`/api/order/complaints/${no}/work-order`, {}),
  listRefundRecords: (q?: StatusQ) => client.get("/api/order/refunds", q),
  auditRefund: (no, approve, rejectReason) => client.post(`/api/order/refunds/${no}/audit`, { approve, rejectReason }),

  // 扩展实体 save（URL 对齐各自 list 端点）
  savePowerbank: (x) => client.post(x.powerbankNo ? `/api/ops/powerbanks/${x.powerbankNo}` : "/api/ops/powerbanks", x),
  saveInventoryTransfer: (x) => client.post(x.transferNo ? `/api/ops/inventory-transfers/${x.transferNo}` : "/api/ops/inventory-transfers", x),
  saveOtaRollout: (x) => client.post(x.rolloutNo ? `/api/ops/ota-rollouts/${x.rolloutNo}` : "/api/ops/ota-rollouts", x),
  saveSlaRule: (x) => client.post(x.slaNo ? `/api/ops/sla-rules/${x.slaNo}` : "/api/ops/sla-rules", x),
  saveInspectionPlan: (x) => client.post(x.planNo ? `/api/ops/inspection-plans/${x.planNo}` : "/api/ops/inspection-plans", x),
  saveLead: (x) => client.post(x.leadNo ? `/api/ops/leads/${x.leadNo}` : "/api/ops/leads", x),
  saveVenue: (x) => client.post(x.venueNo ? `/api/ops/venues/${x.venueNo}` : "/api/ops/venues", x),
  saveContract: (x) => client.post(x.contractNo ? `/api/ops/contracts/${x.contractNo}` : "/api/ops/contracts", x),
  saveAgentAccount: (x) => client.post(x.accountNo ? `/api/agent/accounts/${x.accountNo}` : "/api/agent/accounts", x),
  savePricePlan: (x) => client.post(x.planNo ? `/api/trade/price-plans/${x.planNo}` : "/api/trade/price-plans", x),
  savePricingDiff: (x) => client.post(x.ruleNo ? `/api/trade/pricing-diffs/${x.ruleNo}` : "/api/trade/pricing-diffs", x),
  savePricingSchedule: (x) => client.post(x.ruleNo ? `/api/trade/pricing-schedules/${x.ruleNo}` : "/api/trade/pricing-schedules", x),
  saveShareRule: (x) => client.post(x.ruleNo ? `/api/trade/share-rules/${x.ruleNo}` : "/api/trade/share-rules", x),
  saveInvoice: (x) => client.post(x.invoiceNo ? `/api/trade/invoices/${x.invoiceNo}` : "/api/trade/invoices", x),
  saveMember: (x) => client.post(x.userNo ? `/api/user/members/${x.userNo}` : "/api/user/members", x),
  saveWallet: (x) => client.post(x.userNo ? `/api/user/wallets/${x.userNo}` : "/api/user/wallets", x),
  saveCampaign: (x) => client.post(x.campaignNo ? `/api/user/campaigns/${x.campaignNo}` : "/api/user/campaigns", x),
  savePushMessage: (x) => client.post(x.pushNo ? `/api/user/push-messages/${x.pushNo}` : "/api/user/push-messages", x),
  saveAdSlot: (x) => client.post(x.slotNo ? `/api/ops/ad-slots/${x.slotNo}` : "/api/ops/ad-slots", x),
  saveAdCampaign: (x) => client.post(x.adNo ? `/api/user/ad-campaigns/${x.adNo}` : "/api/user/ad-campaigns", x),
  saveCsTicket: (x) => client.post(x.ticketNo ? `/api/cs/tickets/${x.ticketNo}` : "/api/cs/tickets", x),
  saveDepartment: (x) => client.post(x.deptNo ? `/api/platform/departments/${x.deptNo}` : "/api/platform/departments", x),
  saveRoleRow: (x) => client.post(x.roleNo ? `/api/platform/roles/${x.roleNo}` : "/api/platform/roles", x),
  saveNotifyTemplate: (x) => client.post(x.templateNo ? `/api/platform/notify-templates/${x.templateNo}` : "/api/platform/notify-templates", x),
  saveDictEntry: (x) => client.post(x.dictNo ? `/api/platform/dict-entries/${x.dictNo}` : "/api/platform/dict-entries", x),
  saveRegion: (x) => client.post(x.regionId ? `/api/platform/regions/${x.regionId}` : "/api/platform/regions", x),
  saveSysParam: (x) => client.post(x.paramKey ? `/api/platform/sys-params/${x.paramKey}` : "/api/platform/sys-params", x),
  saveOpenApiApp: (x) => client.post(x.appNo ? `/api/platform/openapi-apps/${x.appNo}` : "/api/platform/openapi-apps", x),
  saveEmployee: (x) => client.post(x.employeeNo ? `/api/platform/employees/${x.employeeNo}` : "/api/platform/employees", x),
  saveMarketCountry: (x) => client.post(x.countryCode ? `/api/platform/markets/${x.countryCode}` : "/api/platform/markets", x),

  // 公告管理 / 支付渠道
  listNotices: (q?: PageQ) => client.get("/api/marketing/notices", q),
  saveNotice: (x) => client.post(x.noticeNo ? `/api/marketing/notices/${x.noticeNo}` : "/api/marketing/notices", x),
  listPaymentChannels: (q?: PageQ) => client.get("/api/system/payment-channels", q),
  savePaymentChannel: (x) => client.post(x.channelCode ? `/api/system/payment-channels/${x.channelCode}` : "/api/system/payment-channels", x),

  // 财务 B5：分润统计（trade 域聚合）/ 充值订单（钱包同主体，归 user 域）
  listShareSummaries: (q?: ShareSummaryQ) => client.get("/api/trade/share-summaries", q),
  listRechargeOrders: (q?: RechargeQ) => client.get("/api/user/recharge-orders", q),

  // 批次 B4/B5：设备日志/编码归 ops 域，预约与免费订单归 trade 域，白名单与充值套餐归 user 域
  listDeviceLogs: (q?: DeviceLogQ) => client.get("/api/ops/device-logs", q),
  listDeviceCodeBatches: (q?: PageQ) => client.get("/api/ops/device-code-batches", q),
  saveDeviceCodeBatch: (x) => client.post(x.batchNo ? `/api/ops/device-code-batches/${x.batchNo}` : "/api/ops/device-code-batches", x),
  listReservations: (q?: ReservationQ) => client.get("/api/trade/reservations", q),
  cancelReservation: (no) => client.post(`/api/trade/reservations/${no}/cancel`, {}),
  listFreeOrders: (q?: FreeOrderQ) => client.get("/api/trade/free-orders", q),
  getFreeOrderStats: () => client.get("/api/trade/free-orders/stats"),
  listFreeWhitelist: (q?: WhitelistQ) => client.get("/api/user/free-whitelist", q),
  saveFreeWhitelist: (x) => client.post(x.userNo ? `/api/user/free-whitelist/${x.userNo}` : "/api/user/free-whitelist", x),
  revokeFreeWhitelist: (no) => client.post(`/api/user/free-whitelist/${no}/revoke`, {}),
  listRechargePackages: (q?: StatusQ) => client.get("/api/user/recharge-packages", q),
  saveRechargePackage: (x) => client.post(x.packageNo ? `/api/user/recharge-packages/${x.packageNo}` : "/api/user/recharge-packages", x),

  // 系统设置 B2/B3/B5（规格 §9~§16）
  listNotifyLogs: (q?: NotifyLogQ) => client.get("/api/system/notify-logs", q),
  getNotifyLogStats: () => client.get("/api/system/notify-logs/stats"),
  listNotifyBlacklist: (q?: NotifyBlacklistQ) => client.get("/api/system/notify-blacklist", q),
  saveNotifyBlacklist: (x) => client.post(x.blockNo ? `/api/system/notify-blacklist/${x.blockNo}` : "/api/system/notify-blacklist", x),
  releaseNotifyBlacklist: (no) => client.post(`/api/system/notify-blacklist/${no}/release`, {}),
  getBizRules: () => client.get("/api/system/biz-rules"),
  saveBizRules: (x) => client.post("/api/system/biz-rules", x),
  listLoginSettings: (q?: PageQ) => client.get("/api/system/login-settings", q),
  saveLoginSetting: (x) => client.post(x.country ? `/api/system/login-settings/${x.country}` : "/api/system/login-settings", x),
  listAppVersions: (q?: AppVersionQ) => client.get("/api/system/app-versions", q),
  saveAppVersion: (x) => client.post(x.versionId ? `/api/system/app-versions/${x.versionId}` : "/api/system/app-versions", x),
  rollbackAppVersion: (id) => client.post(`/api/system/app-versions/${id}/rollback`, {}),
  listBanks: (q?: BankQ) => client.get("/api/system/banks", q),
  saveBank: (x) => client.post(x.bankCode ? `/api/system/banks/${x.bankCode}` : "/api/system/banks", x),
  listProblems: (q?: ProblemQ) => client.get("/api/system/problems", q),
  saveProblem: (x) => client.post(x.problemNo ? `/api/system/problems/${x.problemNo}` : "/api/system/problems", x),
  listTaxSettings: (q?: PageQ) => client.get("/api/system/tax-settings", q),
  saveTaxSetting: (x) => client.post(x.country ? `/api/system/tax-settings/${x.country}` : "/api/system/tax-settings", x),
};

import type { ShareSummaryQ, RechargeQ } from "./contract";
import type { DeviceLogQ, ReservationQ, FreeOrderQ, WhitelistQ } from "./contract";
import type { NotifyLogQ, NotifyBlacklistQ, AppVersionQ, BankQ, ProblemQ } from "./contract";
