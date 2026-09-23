// 契约一致性单测：279 个方法靠人眼查不出漏实现，这里用集合运算兜住。
//
// 三条防线：
// ① mockApi 与 httpApi 方法名集合完全相同 —— 防某一侧漏实现（页面切到真实后端才炸）。
// ② 两者与 API_METHODS 锚数组一致 —— 锚数组是 Api interface 的运行时投影；
//    tsc 保证 mockApi/httpApi 实现了 Api 的全部成员（少一个就编译不过），
//    因此「锚 == mock == http」等价于「锚 == Api」。新增方法必须同步改锚。
// ③ 各域切片之间方法名不重叠 —— 组合时后者覆盖前者会静默丢实现。
import { describe, it, expect } from "vitest";
import { mockApi, MOCK_SLICES } from "./mock";
import { httpApi, HTTP_SLICES } from "./http";

/** Api interface 的运行时锚：按域分组，顺序与 contracts/*.ts 一致。 */
const API_METHODS: Record<string, readonly string[]> = {
  dashboard: ["login", "logout", "getDashboard"],
  device: ["listCabinets", "getCabinet", "saveCabinet", "sendCommand", "listPowerbanks", "listCabinetMonitor", "listCommandRecords", "listInventoryTransfers", "listOtaRollouts", "savePowerbank", "saveInventoryTransfer", "saveOtaRollout", "listOtaReleases", "saveOtaRelease", "listOtaTasks", "listDeviceLogs", "listDeviceCodeBatches", "saveDeviceCodeBatch", "archiveCabinet", "unarchiveCabinet", "archivePowerbank", "unarchivePowerbank", "importCabinets"],
  alarm: ["listAlarmRecords", "listAlarmNotices", "listAlarmCodes", "listAlarmRules", "saveAlarmCode", "saveAlarmRule", "raiseAlarmWorkOrder", "ackAlarm", "autoRaiseWorkOrders", "resendAlarmNotice", "archiveAlarmCode", "unarchiveAlarmCode", "archiveAlarmRule", "unarchiveAlarmRule"],
  workorder: ["listWorkOrders", "createWorkOrder", "dispatchWorkOrder", "acceptWorkOrder", "processWorkOrder", "completeWorkOrder", "closeWorkOrder", "rejectWorkOrder", "reworkWorkOrder", "listSlaRules", "listInspectionPlans", "saveSlaRule", "saveInspectionPlan", "runInspectionPlan"],
  location: ["listSiteAgents", "saveSiteAgent", "removeSiteAgent", "listSites", "saveSite", "listLocations", "savePoint", "listVenues", "listContracts", "listLeads", "listSiteAnalysis", "saveLead", "saveVenue", "saveContract", "listLeadFollowUps", "addLeadFollowUp", "addContractAttachment", "removeContractAttachment", "listVenueOnboardings", "saveVenueOnboarding", "listSiteLifecycles", "changeSiteStage", "archiveSite", "unarchiveSite", "archivePoint", "unarchivePoint", "archiveVenue", "unarchiveVenue"],
  agent: ["listAgents", "saveAgent", "listAgentAssignments", "listAgentPerformance", "listAgentAccounts", "saveAgentAccount", "listAgentApplies", "acceptAgentApply", "auditAgentApply", "createAgentApply", "sendApplyOtp", "selfServiceApply", "myApply", "listAssignableAssets", "assignAgentAssets", "reclaimAgentAssets", "listAgentAssignmentRecords", "listAgentCommissions", "saveAgentCommission", "archiveAgent", "unarchiveAgent"],
  order: ["listOrders", "getOrder", "interveneOrder", "listOrderInterventions", "listOrderExceptions", "handleOrderException", "listDepositRecords", "releaseDeposit", "buyoutDeposit", "dunArrears", "listOrderComplaints", "createOrderComplaint", "handleOrderComplaint", "raiseComplaintWorkOrder", "listRefundRecords", "createRefund", "auditRefund", "listReservations", "cancelReservation", "listFreeOrders", "getFreeOrderStats"],
  pricing: ["listPricePlans", "listPricingSchedules", "savePricePlan", "savePricingSchedule", "listPlanScopes", "savePlanScope", "removePlanScope", "archivePricePlan", "unarchivePricePlan"],
  finance: ["listShareRules", "listLedger", "getVoucher", "createVoucher", "listSettlements", "listWithdrawals", "auditWithdrawal", "generateSettlements", "confirmSettlement", "listSettlementRecords", "listShareRecords", "listReconciles", "listInvoices", "saveShareRule", "saveInvoice", "listReconDiffs", "handleRecon", "getReconStats", "issueInvoice", "voidInvoice", "listShareSummaries", "listRechargeOrders", "listPayoutAccounts", "savePayoutAccount", "disablePayoutAccount"],
  user: ["listUsers", "setBlacklist", "getUserProfile", "listMembers", "listWallets", "listWalletTxns", "saveMember", "listMemberBenefits", "saveMemberBenefit", "listMemberCards", "grantMemberCard", "saveWallet", "listConsumerSegments", "listUserRisks", "listUserBlacklist", "adjustCreditScore", "listCreditScoreChanges", "listFreeWhitelist", "saveFreeWhitelist", "revokeFreeWhitelist", "listRechargePackages", "saveRechargePackage", "archiveRechargePackage", "unarchiveRechargePackage"],
  marketing: ["listCoupons", "saveCoupon", "issueCoupon", "listCouponIssueRecords", "listCampaigns", "listPushMessages", "listReferrals", "listAdSlots", "listAdCampaigns", "listAdDeliveries", "transitionAdCampaign", "listReferralRules", "saveReferralRule", "saveCampaign", "transitionCampaign", "savePushMessage", "sendPushMessage", "saveAdSlot", "saveAdCampaign", "listNotices", "saveNotice", "archiveCoupon", "unarchiveCoupon", "archiveNotice", "unarchiveNotice"],
  cs: ["listCsTickets", "listCsSessions", "saveCsTicket", "refundCsTicket", "woCsTicket", "listCsMessages", "replyCsSession"],
  report: ["listReportDevice", "listReportLocation", "listReportFinance", "listReportScreen", "listReportCustom", "getReportTrend", "getScreenBoard", "listReportMetrics", "getConsumerInsight"],
  org: ["listEmployees", "listRoles", "listAudits", "listDepartments", "listStaffPerformance", "saveDepartment", "saveRoleRow", "saveEmployee", "saveRoleDataScope", "listPermissions", "listRolePermissions", "saveRolePermissions", "getAuditDetail", "archiveRole", "unarchiveRole"],
  system: ["listBrands", "saveBrand", "archiveBrand", "unarchiveBrand", "listVendors", "saveVendor", "testVendorConnectivity", "listNotifyTemplates", "listDictEntries", "listRegions", "listSysParams", "listOpenApiApps", "listMarketCountries", "saveNotifyTemplate", "saveDictEntry", "saveRegion", "saveSysParam", "saveOpenApiApp", "saveMarketCountry", "listRegionTree", "previewNotifyTemplate", "testSendNotifyTemplate", "resetOpenApiAppSecret", "listPaymentChannels", "savePaymentChannel", "listNotifyLogs", "getNotifyLogStats", "resendNotifyLog", "listNotifyBlacklist", "saveNotifyBlacklist", "releaseNotifyBlacklist", "getBizRules", "saveBizRules", "listLoginSettings", "saveLoginSetting", "listAppVersions", "saveAppVersion", "rollbackAppVersion", "listBanks", "saveBank", "listProblems", "saveProblem", "listTaxSettings", "saveTaxSetting", "archiveBank", "unarchiveBank", "archiveProblem", "unarchiveProblem"],
  operation: ["getOperationOverview", "getSiteStats", "pauseSite", "resumeSite", "listPriceAdjustments", "savePriceAdjustment", "cancelPriceAdjustment", "revertPriceAdjustment", "retryPriceAdjustment", "listSiteSharing", "getSiteSharingStats", "listPayeeSharing"],
};

const ALL_METHODS = Object.values(API_METHODS).flat();
const sorted = (xs: readonly string[]) => [...xs].sort();
const keysOf = (o: object) => Object.keys(o);

describe("API 契约一致性", () => {
  it("锚数组自身无重名（域划分互斥）", () => {
    expect(sorted(ALL_METHODS)).toEqual(sorted([...new Set(ALL_METHODS)]));
  });

  it("mockApi 与 httpApi 方法名集合完全相同", () => {
    const m = new Set(keysOf(mockApi));
    const h = new Set(keysOf(httpApi));
    expect({
      仅mock有: sorted([...m].filter((k) => !h.has(k))),
      仅http有: sorted([...h].filter((k) => !m.has(k))),
    }).toEqual({ 仅mock有: [], 仅http有: [] });
  });

  it("mockApi 方法名与 Api 锚一致", () => {
    expect(sorted(keysOf(mockApi))).toEqual(sorted(ALL_METHODS));
  });

  it("httpApi 方法名与 Api 锚一致", () => {
    expect(sorted(keysOf(httpApi))).toEqual(sorted(ALL_METHODS));
  });

  it("所有方法都是函数（防被数据字段污染）", () => {
    for (const k of ALL_METHODS) {
      expect(typeof (mockApi as Record<string, unknown>)[k], `mock.${k}`).toBe("function");
      expect(typeof (httpApi as Record<string, unknown>)[k], `http.${k}`).toBe("function");
    }
  });
});

describe("域切片划分", () => {
  it("mock 切片之间无重复实现（组合时不会静默覆盖）", () => {
    const flat = Object.values(MOCK_SLICES).flatMap(keysOf);
    expect(flat.length, `重复项: ${sorted(flat.filter((k, i) => flat.indexOf(k) !== i))}`).toBe(new Set(flat).size);
  });

  it("http 切片之间无重复实现（组合时不会静默覆盖）", () => {
    const flat = Object.values(HTTP_SLICES).flatMap(keysOf);
    expect(flat.length, `重复项: ${sorted(flat.filter((k, i) => flat.indexOf(k) !== i))}`).toBe(new Set(flat).size);
  });

  it.each(Object.keys(API_METHODS))("域 %s 的 mock/http 切片与契约逐域对齐", (domain) => {
    const expected = sorted(API_METHODS[domain]);
    expect(sorted(keysOf((MOCK_SLICES as Record<string, object>)[domain]))).toEqual(expected);
    expect(sorted(keysOf((HTTP_SLICES as Record<string, object>)[domain]))).toEqual(expected);
  });

  it("方法总数仍为 298（新增/删除 API 时须自觉更新此数）", () => {
    // 2026-09-23：差异化定价 2 个退役，适用范围 3 个新增（ADR-028 / V49），净 +1。
    // 2026-09-23 B1：品牌四个端点（list/save/archive/unarchive）。
        // 2026-09-23 A2-1：站点伙伴责任三个端点。
    expect(ALL_METHODS.length).toBe(298);
  });
});
