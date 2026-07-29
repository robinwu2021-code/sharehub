// 完整 mock 数据集（覆盖 docs/api 全部域）+ 通用查询/CRUD helper。
// 仅 NEXT_PUBLIC_USE_MOCK=1 时经 lib/api/mock.ts 使用；切真实后端后不参与。
//
// 本文件只做「组装」：公开 API 与拆分前的 lib/mock/db.ts 逐名一致，
// 导入路径 `@/lib/mock/db` 保持不变。新增 mock 请放到对应的域文件，再在这里补一行导出。
// 域文件里的私有常量（internal.ts、cabNo 等）一律不对外导出。

export { paginate, kwHit, upsert, nextNo, liveHit, setArchived, archiveRow, unarchiveRow } from "./helpers";

export { dashboard } from "./dashboard";

export {
  cabinets, slotsOf, vendors, powerbanks, cabinetMonitors, commandRecords,
  inventoryTransfers, otaRollouts, deviceLogs, deviceCodeBatches,
  listPowerbanks, listCabinetMonitor, listCommandRecords, listInventoryTransfers,
  listOtaRollouts, listDeviceLogs, listDeviceCodeBatches,
  savePowerbank, saveInventoryTransfer, saveOtaRollout, saveDeviceCodeBatch,
  archiveCabinet, unarchiveCabinet, archivePowerbank, unarchivePowerbank, importCabinets,
} from "./device";

export {
  alarmCodes, alarmRecords, alarmNotices, alarmRules,
  listAlarmRecords, listAlarmNotices, listAlarmCodes, listAlarmRules,
  saveAlarmCode, saveAlarmRule, raiseAlarmWorkOrder,
  archiveAlarmCode, unarchiveAlarmCode, archiveAlarmRule, unarchiveAlarmRule,
} from "./alarm";

export {
  workOrders, slaRules, inspectionPlans,
  listSlaRules, listInspectionPlans, saveSlaRule, saveInspectionPlan,
} from "./workorder";

export {
  sites, locations, venues, contracts, leads, siteAnalyses,
  venueOnboardings, siteLifecycles,
  listLeads, listSiteAnalysis, listVenueOnboardings, listSiteLifecycles,
  saveLead, saveVenue, saveContract, saveVenueOnboarding,
  archiveSite, unarchiveSite, archivePoint, unarchivePoint, archiveVenue, unarchiveVenue,
} from "./location";

export {
  agents, agentAssignments, agentPerformances, agentAccounts, agentCommissions,
  listAgentAssignments, listAgentPerformance, listAgentAccounts, listAgentCommissions,
  saveAgentAccount, saveAgentCommission,
  archiveAgent, unarchiveAgent,
} from "./agent";

export {
  orders, orderExceptions, depositRecords, reservations, freeOrders,
  listOrderExceptions, listDepositRecords, listReservations, cancelReservation,
  listFreeOrders, getFreeOrderStats,
} from "./order";

export {
  pricePlans, pricingDiffs, pricingSchedules,
  listPricingDiffs, listPricingSchedules,
  savePricePlan, savePricingDiff, savePricingSchedule,
  archivePricePlan, unarchivePricePlan,
} from "./pricing";

export {
  shareRules, settlements, withdrawals, ledger, shareRecords, reconciles, invoices,
  listShareRecords, listReconciles, listInvoices, saveShareRule, saveInvoice, auditWithdrawal,
  shareSummaries, listShareSummaries,
  rechargeOrders, listRechargeOrders,
  rechargePackages, listRechargePackages, saveRechargePackage,
  archiveRechargePackage, unarchiveRechargePackage,
} from "./finance";
export type { ShareSummaryQuery, RechargeQuery } from "./finance";

export {
  cUsers, userRisks, userBlacklist, members, wallets, freeWhitelist, consumerSegments,
  listMembers, listWallets, listUserRisks, listUserBlacklist, listConsumerSegments,
  saveMember, saveWallet,
  listFreeWhitelist, saveFreeWhitelist, revokeFreeWhitelist,
} from "./user";

export {
  coupons, saveCoupon, campaigns, pushMessages, referrals,
  adSlots, adCampaigns, adDeliveries,
  listCampaigns, listPushMessages, listReferrals, listAdSlots, listAdCampaigns, listAdDeliveries,
  saveCampaign, savePushMessage, saveAdSlot, saveAdCampaign,
  notices, listNotices, saveNotice,
  archiveCoupon, unarchiveCoupon, archiveNotice, unarchiveNotice,
} from "./marketing";

export {
  csTickets, csSessions, listCsTickets, listCsSessions, saveCsTicket,
  orderComplaints, refundRecords, listOrderComplaints, listRefundRecords,
  saveOrderComplaint, handleOrderComplaint, raiseComplaintWorkOrder, applyRefund, auditRefund,
} from "./cs";

export {
  reportDevices, reportLocations, reportFinances, reportScreens, reportCustoms,
  listReportDevice, listReportLocation, listReportFinance, listReportScreen, listReportCustom,
} from "./report";

export {
  tenants, tenantConfigs, employees, roles, audits, departments, staffPerformances,
  listEmployees, listDepartments, listStaffPerformance,
  saveDepartment, saveRoleRow, saveEmployee, saveRoleDataScope,
  listRoles, archiveRole, unarchiveRole,
} from "./org";

export {
  notifyTemplates, dictEntries, regions, sysParams, openApiApps, marketCountries,
  listNotifyTemplates, listDictEntries, listRegions, listSysParams, listOpenApiApps, listMarketCountries,
  saveNotifyTemplate, saveDictEntry, saveRegion, saveSysParam, saveOpenApiApp, saveMarketCountry,
  paymentChannels, listPaymentChannels, savePaymentChannel,
  maskTarget,
  notifyLogs, listNotifyLogs, getNotifyLogStats,
  notifyBlacklist, listNotifyBlacklist, saveNotifyBlacklist, releaseNotifyBlacklist,
  bizRules, getBizRules, saveBizRules,
  loginSettings, listLoginSettings, saveLoginSetting,
  appVersions, listAppVersions, saveAppVersion, rollbackAppVersion,
  banks, listBanks, saveBank,
  problems, listProblems, saveProblem,
  taxSettings, listTaxSettings, saveTaxSetting,
  archiveBank, unarchiveBank, archiveProblem, unarchiveProblem,
} from "./system";
