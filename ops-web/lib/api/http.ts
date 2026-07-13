// 真实后端实现（Api 契约）。端点对齐 docs/api/README.md。
import { client } from "./http-client";
import type { Api, CabinetQ, OrderQ, WoQ, PageQ } from "./contract";

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
  auditWithdrawal: (no, approve) => client.post(`/api/trade/withdrawals/${no}/audit`, { approve }),

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
};
