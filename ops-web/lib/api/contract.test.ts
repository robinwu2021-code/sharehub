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
  dashboard: ["login", "logout", "me", "getMenus", "getDashboard", "getOpsFlowMetrics", "getRevenueSummary", "getRevenueTrend", "getRevenueByDevice", "getRevenueAlerts", "sendLoginOtp", "listOperators", "switchOperator", "changePassword"],
  device: ["goLiveGate", "goLive", "markDeviceFault", "repairDevice", "undeployDevice", "retireDevice", "listTrialRents", "startTrialRent", "listProtections", "applyProtection", "releaseProtection", "listSignalCodes", "listCabinets", "getCabinet", "saveCabinet", "sendCommand", "listPowerbanks", "listCabinetMonitor", "listCommandRecords", "listInventoryTransfers", "getInventoryTransfer", "listOtaRollouts", "savePowerbank", "saveInventoryTransfer", "saveOtaRollout", "listOtaReleases", "saveOtaRelease", "listOtaTasks", "listDeviceLogs", "listDeviceCodeBatches", "saveDeviceCodeBatch", "archiveCabinet", "unarchiveCabinet", "archivePowerbank", "unarchivePowerbank", "importCabinets", "transitPowerbank", "inspectCabinet", "inspectPowerbank",
  // 疑似丢失核实（后端 V113）：失联满 7 天打标记，由人确认丢失或找回
  "confirmPowerbankLost", "dismissPowerbankLost", "listQcRecords", "setTransferItems", "shipTransfer", "receiveTransfer", "listAssetDiffs", "resolveAssetDiff", "issueDevices", "returnPoolDevices", "claimDevices", "redirectIssue", "listDeviceMovements", "getInventoryEngine", "getStockStages", "getStockPeriodSummary", "getInventoryMigrationPlan", "runInventoryMigration", "listStockLedger", "listStockCounts", "getStockCount", "openStockCount", "scanStockCount", "submitStockCount", "approveStockCount", "rejectStockCount", "postStockCount", "cancelStockCount", "issuedSummary", "listWarehouses", "createWarehouse", "updateWarehouse", "archiveWarehouse", "unarchiveWarehouse", "listDisposals", "getDisposal", "createDisposal", "listSuppliers", "createSupplier", "updateSupplier", "archiveSupplier", "unarchiveSupplier", "listIntakes", "getIntake", "createIntake", "inspectIntakeItem", "inboundIntake", "disposeIntake", "bindDevice", "bindDevices", "unbindDevice"],
  alarm: ["alarmSummary", "getAlarmDetail", "alarmDispositionPreview", "disposeAlarm", "listAlarmRoutes", "saveAlarmRoutes", "alarmCodeStats", "listAlarmTodos", "alarmTodoCount", "doneAlarmTodo", "listAlarmRecords", "listAlarmNotices", "listAlarmCodes", "listAlarmRules", "saveAlarmCode", "saveAlarmRule", "raiseAlarmWorkOrder", "ackAlarm", "closeAlarm", "autoRaiseWorkOrders", "resendAlarmNotice", "archiveAlarmCode", "unarchiveAlarmCode", "archiveAlarmRule", "unarchiveAlarmRule"],
  workorder: ["woSummary", "getWorkOrderDetail", "assigneeCandidates", "deriveWorkOrder", "takeoverWorkOrder", "listWorkOrders", "createWorkOrder", "dispatchWorkOrder", "acceptWorkOrder", "processWorkOrder", "completeWorkOrder", "closeWorkOrder", "rejectWorkOrder", "reworkWorkOrder", "listSlaRules", "listInspectionPlans", "saveSlaRule", "saveInspectionPlan", "runInspectionPlan", "getSlaRule", "getInspectionPlan", "listWoPool", "grabWorkOrder", "listWoCosts"],
  location: ["listSiteAgents", "saveSiteAgent", "removeSiteAgent", "listSites", "saveSite", "listLocations", "savePoint", "listVenues", "listContracts", "listLeads", "getLead", "claimLead", "convertLead", "listSiteAnalysis", "saveLead", "saveVenue", "saveContract", "listLeadFollowUps", "addLeadFollowUp", "addContractAttachment", "removeContractAttachment", "listVenueOnboardings", "getVenueOnboarding", "saveVenueOnboarding", "reviewVenueOnboarding", "listSiteLifecycles", "siteLifecycleFunnel", "getContract", "contractSummary", "listContractLogs", "submitContract", "withdrawContract", "auditContract", "cosignContract", "signContract", "terminateContract", "auditContractTermination", "renewContract", "supplementContract", "getSite", "siteSummary", "listSiteStatusLogs", "siteOpeningChecklist", "siteCloseGate", "withdrawSite", "closeSite", "listSiteSurveys", "recordSiteSurvey", "archiveSite", "unarchiveSite", "archivePoint", "unarchivePoint", "archiveVenue", "unarchiveVenue"],
  agent: ["listAgents", "saveAgent", "grantAgentLogin", "listAgentAssignments", "listAgentPerformance", "listAgentAccounts", "saveAgentAccount", "listAgentApplies", "acceptAgentApply", "auditAgentApply", "resendAgentInvite", "createAgentApply", "sendApplyOtp", "selfServiceApply", "myApply", "listAssignableAssets", "assignAgentAssets", "reclaimAgentAssets", "listAgentAssignmentRecords", "listAgentCommissions", "saveAgentCommission", "archiveAgent", "unarchiveAgent", "getAgentAccount", "getAgentCommission", "getMyProfile", "updateMyContact", "startAgentExit", "getAgentExit", "getAgentOpenExit", "agentExitGate", "advanceAgentExit", "listAgentOpsAssessments"],
  order: ["listOrders", "exportOrders", "getOrder", "interveneOrder", "listOrderInterventions", "listOrderEvents", "listOrderExceptions", "handleOrderException", "listDepositRecords", "releaseDeposit", "buyoutDeposit", "dunArrears", "listOrderComplaints", "createOrderComplaint", "handleOrderComplaint", "raiseComplaintWorkOrder", "listRefundRecords", "createRefund", "auditRefund", "listReservations", "cancelReservation", "listFreeOrders", "getFreeOrderStats"],
  pricing: ["listPricePlans", "listPricingSchedules", "savePricePlan", "savePricingSchedule", "listPlanScopes", "savePlanScope", "removePlanScope", "archivePricePlan", "unarchivePricePlan"],
  finance: ["listShareRules", "listLedger", "getVoucher", "createVoucher", "listSettlements", "listWithdrawals", "auditWithdrawal", "payWithdrawal", "resubmitWithdrawalApproval", "retryWithdrawalApprovalEffect", "applyWithdrawal", "generateSettlements", "confirmSettlement", "listSettlementRecords", "listShareRecords", "listReconciles", "listInvoices", "getInvoice", "listTaxInvoices", "getTaxInvoice", "retryTaxInvoice", "exportTaxInvoices", "downloadTaxInvoicePdf", "listSelfBills", "listPayables", "listApprovals", "approveApproval", "rejectApproval", "issueSelfBill", "downloadSelfBillPdf", "listMySelfBills", "downloadMySelfBillPdf", "saveShareRule", "saveInvoice", "listReconDiffs", "handleRecon", "getReconStats", "issueInvoice", "voidInvoice", "listShareSummaries", "listRechargeOrders", "listPayoutAccounts", "savePayoutAccount", "disablePayoutAccount", "getSettlement", "getSettlementStatement", "getSettlementStatementHtml", "listSettlementAdjustments", "confirmSettlementAdjustment", "voidSettlementAdjustment",
    // 付款任务（V158，商家一期 B4）
    "previewPayoutTask", "createPayoutTask", "listPayoutTasks", "getPayoutTask", "auditPayoutTask", "exportPayoutTask", "confirmPayoutTask"],
  user: ["listUsers", "setBlacklist", "getUserProfile", "listMembers", "listWallets", "listWalletTxns", "saveMember", "listMemberPlans", "saveMemberPlan", "enableMemberPlan", "disableMemberPlan", "listMemberBenefits", "saveMemberBenefit", "listMemberCards", "grantMemberCard", "saveWallet", "listConsumerSegments", "listUserRisks", "listUserBlacklist", "adjustCreditScore", "listCreditScoreChanges", "listFreeWhitelist", "saveFreeWhitelist", "revokeFreeWhitelist", "listRechargePackages", "saveRechargePackage", "archiveRechargePackage", "unarchiveRechargePackage", "listLogoffs", "revokeLogoff", "listCUserInvoices", "issueCUserInvoice", "rejectCUserInvoice"],
  marketing: ["listCoupons", "saveCoupon", "issueCoupon", "listCouponIssueRecords", "listCampaigns", "listPushMessages", "listReferrals", "listAdSlots", "listAdCampaigns", "listAdDeliveries", "transitionAdCampaign", "listReferralRules", "saveReferralRule", "saveCampaign", "transitionCampaign", "savePushMessage", "sendPushMessage", "finishPushMessage", "saveAdSlot", "saveAdCampaign", "listNotices", "saveNotice", "transitionNotice", "archiveCoupon", "unarchiveCoupon", "archiveNotice", "unarchiveNotice"],
  cs: ["listCsTickets", "getCsTicket", "listCsSessions", "saveCsTicket", "refundCsTicket", "woCsTicket", "listCsMessages", "replyCsSession"],
  report: ["listReportDevice", "listReportLocation", "listReportFinance", "exportReport", "listReportScreen", "listReportCustom", "getReportTrend", "getScreenBoard", "listReportMetrics", "getConsumerInsight"],
  org: ["listEmployees", "listRoles", "listAudits", "listDepartments", "listStaffPerformance", "saveDepartment", "saveRoleRow", "saveEmployee", "resetEmployeeCredential", "getDataScope", "saveDataScope", "listAllMenus", "updateMenu", "listPermissions", "listRolePermissions", "saveRolePermissions", "getAuditDetail", "archiveRole", "unarchiveRole"],
  system: ["uploadFile", "fileUrl", "fileBlobUrl", "listBrands", "saveBrand", "archiveBrand", "unarchiveBrand", "listVendors", "saveVendor", "testVendorConnectivity", "listNotifyTemplates", "listDictEntries", "listRegions", "listSysParams", "getTimezoneOptions", "listOpenApiApps", "listMarketCountries", "saveNotifyTemplate", "saveDictEntry", "saveRegion", "saveSysParam", "saveOpenApiApp", "saveMarketCountry", "listRegionTree", "previewNotifyTemplate", "testSendNotifyTemplate", "resetOpenApiAppSecret", "listPaymentChannels", "savePaymentChannel", "listNotifyLogs", "getNotifyLogStats", "resendNotifyLog", "listNotifyBlacklist", "saveNotifyBlacklist", "releaseNotifyBlacklist", "getBizRules", "saveBizRules", "listLoginSettings", "saveLoginSetting", "listAppVersions", "saveAppVersion", "rollbackAppVersion", "listBanks", "saveBank", "listProblems", "saveProblem", "listTaxSettings", "saveTaxSetting", "archiveBank", "unarchiveBank", "archiveProblem", "unarchiveProblem"],
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

  it("方法总数仍为 490（新增/删除 API 时须自觉更新此数）", () => {
    // 2026-09-23：差异化定价 2 个退役，适用范围 3 个新增（ADR-028 / V49），净 +1。
    // 2026-09-23 B1：品牌四个端点（list/save/archive/unarchive）。
        // 2026-09-23 A2-1：站点伙伴责任三个端点。
    // 2026-09-24：推送 finishPushMessage（SENDING → SENT）。引入 SENDING 态就必须有出口，
    // 否则推送发出后永远卡在「发送中」。
    // 2026-09-25 B1：订单状态流转读端点 listOrderEvents（ord_event_log 此前只写不读）。
    // 2026-09-25：注销申请受理 / C 端开票受理各 2+3 个（运营端此前没有动作面）。
    // 2026-09-25 运营流程批次1：changeSiteStage 退役（生命周期与站点状态合并，
    //   「推进阶段」是第二套事实），siteLifecycleFunnel 新增 —— 净 0。
    // 2026-09-25 运营流程批次2：文件上传 uploadFile / fileUrl 两个（+2）。
    // 2026-09-25 运营流程批次3：合同审批链 12 个（详情/摘要/留痕 + 提交/撤回/审批/会签/签署
    //   + 终止申请与其审批 + 续签/补充协议）。
    // 2026-09-25 运营流程批次4：站点状态机与门禁 9 个（详情/摘要/留痕 + 开业清单/关闭门禁
    //   + 撤场/关闭）。暂停/恢复早在 OperationApi 里（同一对端点），不重复定义；
    //   goLive 也不在其列——首台设备上线由后端推进，是系统边。
    // 2026-09-25 运营流程批次5a：设备运维 12 个（上线门禁/上线/故障/修复/撤机/报废
    //   + 试借还 2 + 保护 3 + 信号码字典）。
    // 2026-09-25 运营流程批次6a：业务告警与待办 10 个（摘要/详情/处置预览/处置
    //   + 路由读写 + 每码统计 + 待办列表/计数/办结）。
    // 2026-09-25 运营流程批次7a：工单增强 5 个（摘要 / 详情含时间线+照片+关联告警 /
    //   派单候选人 / 派生子单 / 接管）。
    // 2026-09-25 运营流程批次6b：+1 运营核心流程指标（getOpsFlowMetrics）；
    //   getAlarmRecord 更名 getAlarmDetail（后端回的是 AlarmDetail，不是整行记录），不改总数。
    // 2026-09-25 场所域前端补全：商机详情 / 认领 / 签约转化 + 进件详情 + 站点勘测读写（+6）。
    // 2026-09-26 员工凭据登录（P3b·B2）：本人改密 + 管理员建号/重置（+2）。
    // 2026-09-26 代理开通登录（+1）。
    // 408：+1 = transitionNotice（公告发布/下线的动作端点，2026-09-27）
    // 412：+4 = 会员方案 CRUD（list/save + enable/disable 动作端点，2026-09-27）——
    //      mbr_plan 此前没有任何写入方，C 端会员页因此永远是空列表
    // 413：+1 = getTimezoneOptions（业务时区的候选与当前值，2026-09-28）——
    //      候选由后端给：前端写死的列表早晚与后端 ZoneId 能解析的集合漂移，
    //      而漂移的表现是「界面上选得到、保存时报 400」
    // 414：+1 = getCsTicket（报障单详情，2026-09-28）—— 列表放不下用户交上来的截图，
    //      而在此之前**运营端根本没有能看图的地方**：图传上去了，界面上不存在
    // 415：+1 = fileBlobUrl（带鉴权取个人数据文件的字节，2026-09-28）——
    //      PII 不发签名地址，<img> 又发不了 Authorization 头
    // 2026-09-28 M5 提现送 OpsKit：重新送审 / 重试生效（+2）；标题里的数此前停在 367，一并改正
    // 433：+3 = 商家营收首页 summary / trend / by-device（商家一期 B5，2026-10-28）
    // 434：+1 = resendAgentInvite（重发开通邀请，2026-10-28）
    // 436：+2 = 商家资料 getMyProfile / updateMyContact（V175，2026-10-28）
    // 437：+1 = getRevenueAlerts（营收首页设备提醒，2026-10-28）
    // 441：+3 = 租赁税务票据 list / get / retry（V181，运营方原始需求 C01，2026-10-29）
    // 444：+3 = 后端导出 exportReport / exportTaxInvoices / downloadTaxInvoicePdf（K8，2026-10-29）
    // 447：+3 = 商家分成自开票 list / issue / pdf（V183，C10，2026-10-29）
    // 448：+1 = exportOrders（第 5 批，订单后端导出，2026-10-29）
    // 449：+1 = listPayables（跨期应付 SHR-05，2026-10-29）
    // 452：+3 = 资金操作审批 list / approve / reject（SEC-03，V189，2026-10-29）
    // 454：+2 = 商家门户我的自开票 list / pdf（2026-10-29）
    expect(ALL_METHODS.length).toBe(490);
  });
});
