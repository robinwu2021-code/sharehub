// Mock 实现（Api 契约）。全部走 lib/mock/db 内存数据 + 模拟延迟。
import * as db from "../mock/db";
import type { Api, CabinetQ, OrderQ, WoQ, PageQ, AlarmQ, StatusQ } from "./contract";
import type { Vendor, Agent, Site, Location } from "../types";

const wait = <T>(v: T, ms = 200): Promise<T> => new Promise((r) => setTimeout(() => r(v), ms));

export const mockApi: Api = {
  login: (username, role, agentNo) => wait({ token: `mock-${role}`, username, role, agentNo: role === "AGENT" ? (agentNo ?? "AG001") : "" }),
  getDashboard: () => wait(db.dashboard),

  listCabinets: (q: CabinetQ = {}) =>
    wait(db.paginate(db.cabinets, q.page, q.size, (c) =>
      db.kwHit(q.keyword, c.cabinetNo, c.locationName) &&
      (!q.onlineStatus || c.onlineStatus === q.onlineStatus) &&
      (!q.status || c.status === q.status))),
  getCabinet: (no) => wait({ cabinet: db.cabinets.find((c) => c.cabinetNo === no)!, slots: db.slotsOf(no) }),
  sendCommand: (_no, _type) => wait({ commandId: `CMD${Math.floor(performance.now() * 1000)}` }, 480),

  listOrders: (q: OrderQ = {}) =>
    wait(db.paginate(db.orders, q.page, q.size, (o) =>
      db.kwHit(q.keyword, o.orderNo, o.cUserNo) && (!q.status || o.status === q.status))),
  getOrder: (no) => wait(db.orders.find((o) => o.orderNo === no)!),
  // 「申请退款」不是终态动作：落一条 PENDING 退款申请，进 /orders?tab=refunds 审批队列
  interveneOrder: (no, action) => {
    if (action === "refund_apply") db.applyRefund(no);
    return wait({ ok: true } as const, 400);
  },

  listWorkOrders: (q: WoQ = {}) =>
    wait(db.paginate(db.workOrders, q.page, q.size, (w) =>
      db.kwHit(q.keyword, w.woNo, w.cabinetNo) && (!q.status || w.status === q.status) && (!q.type || w.type === q.type))),
  dispatchWorkOrder: (no, assignee) => {
    const w = db.workOrders.find((x) => x.woNo === no);
    if (w) { w.assigneeName = assignee; w.status = "DISPATCHED"; }
    return wait({ ok: true } as const, 400);
  },

  listSites: (q: PageQ = {}) => wait(db.paginate(db.sites, q.page, q.size, (s) => db.kwHit(q.keyword, s.name, s.venueName, s.regionId))),
  saveSite: (s) => {
    const idx = db.sites.findIndex((x) => x.siteNo === s.siteNo);
    const merged = { ...(db.sites[idx] ?? { name: "", venueName: "", agentNo: null, regionId: "", address: "", sceneType: "商场", pointCount: 0, cabinetCount: 0, status: "ACTIVE", siteNo: `ST${300 + db.sites.length}` }), ...s } as Site;
    if (idx >= 0) db.sites[idx] = merged; else db.sites.unshift(merged);
    return wait(merged, 350);
  },
  listLocations: (q: PageQ = {}) => wait(db.paginate(db.locations, q.page, q.size, (l) => db.kwHit(q.keyword, l.name, l.siteName))),
  savePoint: (l) => {
    const idx = db.locations.findIndex((x) => x.locationNo === l.locationNo);
    const merged = { ...(db.locations[idx] ?? { name: "", siteNo: "", siteName: "", spotDesc: "", cabinetCount: 0, status: "ACTIVE", locationNo: `LOC${200 + db.locations.length}` }), ...l } as Location;
    if (idx >= 0) db.locations[idx] = merged; else db.locations.unshift(merged);
    return wait(merged, 350);
  },
  listVenues: (q: PageQ = {}) => wait(db.paginate(db.venues, q.page, q.size, (v) => db.kwHit(q.keyword, v.name))),
  listContracts: (q: PageQ = {}) => wait(db.paginate(db.contracts, q.page, q.size, (c) => db.kwHit(q.keyword, c.venueName, c.siteName))),

  listShareRules: (q: PageQ = {}) => wait(db.paginate(db.shareRules, q.page, q.size, (s) => db.kwHit(q.keyword, s.payeeName))),
  listLedger: (q: PageQ = {}) => wait(db.paginate(db.ledger, q.page, q.size, (l) => db.kwHit(q.keyword, l.account, l.orderNo, l.voucherNo))),
  listSettlements: (q: PageQ = {}) => wait(db.paginate(db.settlements, q.page, q.size, (s) => db.kwHit(q.keyword, s.payeeName, s.settleNo))),
  listWithdrawals: (q: PageQ = {}) => wait(db.paginate(db.withdrawals, q.page, q.size, (w) => db.kwHit(q.keyword, w.payeeName, w.withdrawNo))),
  auditWithdrawal: (no, approve) => {
    const w = db.withdrawals.find((x) => x.withdrawNo === no);
    if (w) w.status = approve ? "PAYING" : "FAILED";
    return wait({ ok: true } as const, 400);
  },

  listVendors: () => wait(db.vendors),
  saveVendor: (v: Partial<Vendor> & { vendorCode: string }) => {
    const idx = db.vendors.findIndex((x) => x.vendorCode === v.vendorCode);
    const merged = { ...(db.vendors[idx] ?? { name: v.vendorCode, accessMode: "HTTP_API", status: "ENABLED", apiBase: null, deviceCount: 0 }), ...v } as Vendor;
    if (idx >= 0) db.vendors[idx] = merged; else db.vendors.push(merged);
    return wait(merged, 350);
  },

  listUsers: (q: PageQ = {}) => wait(db.paginate(db.cUsers, q.page, q.size, (u) => db.kwHit(q.keyword, u.nickname, u.phone, u.cUserNo))),
  setBlacklist: (no, blacklisted) => {
    const u = db.cUsers.find((x) => x.cUserNo === no);
    if (u) u.blacklisted = blacklisted;
    return wait({ ok: true } as const, 350);
  },
  listCoupons: (q: PageQ = {}) => wait(db.paginate(db.coupons, q.page, q.size, (c) => db.kwHit(q.keyword, c.name))),
  saveCoupon: (c) => wait(db.saveCoupon(c), 350),

  listPricePlans: (q: PageQ = {}) => wait(db.paginate(db.pricePlans, q.page, q.size, (p) => db.kwHit(q.keyword, p.name, p.scope))),

  listAgents: (q: PageQ = {}) => wait(db.paginate(db.agents, q.page, q.size, (a) => db.kwHit(q.keyword, a.name, a.agentNo, a.regionScope))),
  saveAgent: (a) => {
    const idx = db.agents.findIndex((x) => x.agentNo === a.agentNo);
    const merged = { ...(db.agents[idx] ?? { name: "", contact: "", regionScope: "", shareRate: 0.3, cabinetCount: 0, status: "ENABLED", agentNo: `AG${db.agents.length + 1}` }), ...a } as Agent;
    if (idx >= 0) db.agents[idx] = merged; else db.agents.push(merged);
    return wait(merged, 350);
  },

  listEmployees: (q: PageQ = {}) => wait(db.paginate(db.employees, q.page, q.size, (e) => db.kwHit(q.keyword, e.name))),
  listRoles: () => wait(db.roles),
  listAudits: (q: PageQ = {}) => wait(db.paginate(db.audits, q.page, q.size, (a) => db.kwHit(q.keyword, a.actor, a.action, a.target))),

  // 设备扩展
  listPowerbanks: (q: PageQ = {}) => wait(db.listPowerbanks(q)),
  listCabinetMonitor: (q: PageQ = {}) => wait(db.listCabinetMonitor(q)),
  listCommandRecords: (q: PageQ = {}) => wait(db.listCommandRecords(q)),
  listInventoryTransfers: (q: PageQ = {}) => wait(db.listInventoryTransfers(q)),
  listOtaRollouts: (q: PageQ = {}) => wait(db.listOtaRollouts(q)),
  // 工单扩展
  listSlaRules: (q: PageQ = {}) => wait(db.listSlaRules(q)),
  listInspectionPlans: (q: PageQ = {}) => wait(db.listInspectionPlans(q)),
  // 场所扩展
  listLeads: (q: PageQ = {}) => wait(db.listLeads(q)),
  listSiteAnalysis: (q: PageQ = {}) => wait(db.listSiteAnalysis(q)),
  // 代理商扩展
  listAgentAssignments: (q: PageQ = {}) => wait(db.listAgentAssignments(q)),
  listAgentPerformance: (q: PageQ = {}) => wait(db.listAgentPerformance(q)),
  listAgentAccounts: (q: PageQ = {}) => wait(db.listAgentAccounts(q)),
  // 订单扩展
  listOrderExceptions: (q: PageQ = {}) => wait(db.listOrderExceptions(q)),
  // 定价扩展
  listPricingDiffs: (q: PageQ = {}) => wait(db.listPricingDiffs(q)),
  listPricingSchedules: (q: PageQ = {}) => wait(db.listPricingSchedules(q)),
  // 财务扩展
  listShareRecords: (q: PageQ = {}) => wait(db.listShareRecords(q)),
  listReconciles: (q: PageQ = {}) => wait(db.listReconciles(q)),
  listInvoices: (q: PageQ = {}) => wait(db.listInvoices(q)),
  // 用户扩展
  listMembers: (q: PageQ = {}) => wait(db.listMembers(q)),
  listWallets: (q: PageQ = {}) => wait(db.listWallets(q)),
  // 营销扩展
  listCampaigns: (q: PageQ = {}) => wait(db.listCampaigns(q)),
  listPushMessages: (q: PageQ = {}) => wait(db.listPushMessages(q)),
  listReferrals: (q: PageQ = {}) => wait(db.listReferrals(q)),
  listAdSlots: (q: PageQ = {}) => wait(db.listAdSlots(q)),
  listAdCampaigns: (q: PageQ = {}) => wait(db.listAdCampaigns(q)),
  listAdDeliveries: (q: PageQ = {}) => wait(db.listAdDeliveries(q)),
  // 员工扩展
  listDepartments: (q: PageQ = {}) => wait(db.listDepartments(q)),
  listStaffPerformance: (q: PageQ = {}) => wait(db.listStaffPerformance(q)),
  // 客服
  listCsTickets: (q: PageQ = {}) => wait(db.listCsTickets(q)),
  listCsSessions: (q: PageQ = {}) => wait(db.listCsSessions(q)),
  // 报表
  listReportDevice: (q: PageQ = {}) => wait(db.listReportDevice(q)),
  listReportLocation: (q: PageQ = {}) => wait(db.listReportLocation(q)),
  listReportFinance: (q: PageQ = {}) => wait(db.listReportFinance(q)),
  listReportScreen: (q: PageQ = {}) => wait(db.listReportScreen(q)),
  listReportCustom: (q: PageQ = {}) => wait(db.listReportCustom(q)),
  // 系统扩展
  listNotifyTemplates: (q: PageQ = {}) => wait(db.listNotifyTemplates(q)),
  listDictEntries: (q: PageQ = {}) => wait(db.listDictEntries(q)),
  listRegions: (q: PageQ = {}) => wait(db.listRegions(q)),
  listSysParams: (q: PageQ = {}) => wait(db.listSysParams(q)),
  listOpenApiApps: (q: PageQ = {}) => wait(db.listOpenApiApps(q)),
  listDepositRecords: (q: PageQ = {}) => wait(db.listDepositRecords(q)),
  listMarketCountries: (q: PageQ = {}) => wait(db.listMarketCountries(q)),
  listConsumerSegments: (q: PageQ = {}) => wait(db.listConsumerSegments(q)),
  // 用户风控
  listUserRisks: (q: PageQ = {}) => wait(db.listUserRisks(q)),
  listUserBlacklist: (q: PageQ = {}) => wait(db.listUserBlacklist(q)),
  // 代理分润
  listAgentCommissions: (q: PageQ = {}) => wait(db.listAgentCommissions(q)),
  saveAgentCommission: (x) => wait(db.saveAgentCommission(x), 350),
  // 门店 Onboarding / 生命周期
  listVenueOnboardings: (q: PageQ = {}) => wait(db.listVenueOnboardings(q)),
  saveVenueOnboarding: (x) => wait(db.saveVenueOnboarding(x), 350),
  listSiteLifecycles: (q: PageQ = {}) => wait(db.listSiteLifecycles(q)),
  // 告警治理
  listAlarmRecords: (q: AlarmQ = {}) => wait(db.listAlarmRecords(q)),
  listAlarmNotices: (q: PageQ = {}) => wait(db.listAlarmNotices(q)),
  listAlarmCodes: (q: PageQ = {}) => wait(db.listAlarmCodes(q)),
  listAlarmRules: (q: PageQ = {}) => wait(db.listAlarmRules(q)),
  saveAlarmCode: (x) => wait(db.saveAlarmCode(x), 350),
  saveAlarmRule: (x) => wait(db.saveAlarmRule(x), 350),
  raiseAlarmWorkOrder: (no) => wait(db.raiseAlarmWorkOrder(no), 400),
  // 售后处置
  listOrderComplaints: (q: StatusQ = {}) => wait(db.listOrderComplaints(q)),
  handleOrderComplaint: (no, resolution, note) => wait(db.handleOrderComplaint(no, resolution, note), 400),
  raiseComplaintWorkOrder: (no) => wait(db.raiseComplaintWorkOrder(no), 400),
  listRefundRecords: (q: StatusQ = {}) => wait(db.listRefundRecords(q)),
  auditRefund: (no, approve, rejectReason) => wait(db.auditRefund(no, approve, rejectReason), 400),

  // 扩展实体 save
  savePowerbank: (x) => wait(db.savePowerbank(x), 350),
  saveInventoryTransfer: (x) => wait(db.saveInventoryTransfer(x), 350),
  saveOtaRollout: (x) => wait(db.saveOtaRollout(x), 350),
  saveSlaRule: (x) => wait(db.saveSlaRule(x), 350),
  saveInspectionPlan: (x) => wait(db.saveInspectionPlan(x), 350),
  saveLead: (x) => wait(db.saveLead(x), 350),
  saveVenue: (x) => wait(db.saveVenue(x), 350),
  saveContract: (x) => wait(db.saveContract(x), 350),
  saveAgentAccount: (x) => wait(db.saveAgentAccount(x), 350),
  savePricePlan: (x) => wait(db.savePricePlan(x), 350),
  savePricingDiff: (x) => wait(db.savePricingDiff(x), 350),
  savePricingSchedule: (x) => wait(db.savePricingSchedule(x), 350),
  saveShareRule: (x) => wait(db.saveShareRule(x), 350),
  saveInvoice: (x) => wait(db.saveInvoice(x), 350),
  saveMember: (x) => wait(db.saveMember(x), 350),
  saveWallet: (x) => wait(db.saveWallet(x), 350),
  saveCampaign: (x) => wait(db.saveCampaign(x), 350),
  savePushMessage: (x) => wait(db.savePushMessage(x), 350),
  saveAdSlot: (x) => wait(db.saveAdSlot(x), 350),
  saveAdCampaign: (x) => wait(db.saveAdCampaign(x), 350),
  saveCsTicket: (x) => wait(db.saveCsTicket(x), 350),
  saveDepartment: (x) => wait(db.saveDepartment(x), 350),
  saveRoleRow: (x) => wait(db.saveRoleRow(x), 350),
  saveNotifyTemplate: (x) => wait(db.saveNotifyTemplate(x), 350),
  saveDictEntry: (x) => wait(db.saveDictEntry(x), 350),
  saveRegion: (x) => wait(db.saveRegion(x), 350),
  saveSysParam: (x) => wait(db.saveSysParam(x), 350),
  saveOpenApiApp: (x) => wait(db.saveOpenApiApp(x), 350),

  // 公告管理 / 支付渠道
  listNotices: (q: PageQ = {}) => wait(db.listNotices(q)),
  saveNotice: (x) => wait(db.saveNotice(x), 350),
  listPaymentChannels: (q: PageQ = {}) => wait(db.listPaymentChannels(q)),
  savePaymentChannel: (x) => wait(db.savePaymentChannel(x), 350),
};
