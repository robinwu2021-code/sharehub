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
  inventoryTransfers, otaRollouts, otaReleases, otaTasks, deviceLogs, deviceCodeBatches,
  listPowerbanks, listCabinetMonitor, listCommandRecords, listInventoryTransfers, getInventoryTransfer,
  listOtaRollouts, listOtaReleases, listOtaTasks, listDeviceLogs, listDeviceCodeBatches,
  savePowerbank, saveInventoryTransfer, saveOtaRollout, saveOtaRelease, saveDeviceCodeBatch,
  // 机柜建档 + 单柜指令下发留痕（S8）
  saveCabinet, recordCommand, cabinetPlacement, CabinetError, CommandError,
  archiveCabinet, unarchiveCabinet, archivePowerbank, unarchivePowerbank, importCabinets,
} from "./device";

export {
  alarmCodes, alarmRecords, alarmNotices, alarmRules,
  listAlarmRecords, listAlarmNotices, listAlarmCodes, listAlarmRules,
  saveAlarmCode, saveAlarmRule, raiseAlarmWorkOrder, ackAlarm, closeAlarm,
  resendAlarmNotice, AlarmNoticeSendError, autoRaiseWorkOrders,
  archiveAlarmCode, unarchiveAlarmCode, archiveAlarmRule, unarchiveAlarmRule,
} from "./alarm";

export {
  workOrders, slaRules, inspectionPlans,
  listSlaRules, listInspectionPlans, saveSlaRule, saveInspectionPlan,
} from "./workorder";

export {
  sites, locations, venues, contracts, leads,
  venueOnboardings, siteLifecycles,
  listLeads, listVenueOnboardings, listSiteLifecycles,
  saveLead, saveVenue, saveContract, saveVenueOnboarding, reviewVenueOnboarding,
  archiveSite, unarchiveSite, archivePoint, unarchivePoint, archiveVenue, unarchiveVenue,
  // 门店生命周期阶段流转（后端 POST /api/ops/site-lifecycles/{siteNo}/stage 的前端入口）
  siteLifecycleLogs, changeSiteStage, SiteLifecycleError,
} from "./location";

export {
  agents, siteAgents, listSiteAgents, saveSiteAgent, removeSiteAgent, agentAssignments, agentPerformances, agentAccounts, agentCommissions,
  listAgentAssignments, listAgentAccounts, listAgentCommissions,
  saveAgentAccount, saveAgentCommission,
  archiveAgent, unarchiveAgent,
  // S1 设备/点位划拨
  agentAssignmentRecords, listAgentAssignmentRecords, listAssignableAssets,
  assignAgentAssets, reclaimAgentAssets, refreshAgentAssetCounts, AgentAssignError,
} from "./agent";
export type { AssignmentRecordQuery, AssignableAssetQuery } from "./agent";
// 入驻申请（ADR-030 §三 · D4）。**这一行不是误提交** —— 它配套 ./apply.ts，
// 缺了它 lib/api/mocks/agent.ts 的四个入驻方法会编译不过（tsc 会红）。
export {
  applies, listAgentApplies, acceptAgentApply, auditAgentApply, createAgentApply,
  sendApplyOtp, selfServiceApply, myApply,
} from "./apply";

export {
  orders, orderExceptions, depositRecords, reservations, freeOrders,
  listOrderExceptions, listDepositRecords, listReservations, cancelReservation,
  listFreeOrders, getFreeOrderStats,
  // S1：订单干预（状态机 + 审计记录）与押金处置
  orderInterventions, interveneOrder, listOrderInterventions, OrderInterventionError,
  orderEvents, appendOrderEvent, listOrderEvents,
  transitionDeposit, releaseDeposit, buyoutDeposit, dunArrears, DepositTransitionError,
  // S2：异常订单处置（转工单 / 发起退款 / 直接关闭）
  handleOrderException, OrderExceptionError,
} from "./order";

export {
  pricePlans, planScopes, pricingSchedules,
  listPlanScopes, listPricingSchedules,
  savePricePlan, savePlanScope, removePlanScope, savePricingSchedule,
  archivePricePlan, unarchivePricePlan,
} from "./pricing";

export {
  shareRules, settlements, withdrawals, ledger, shareRecords, reconciles, invoices,
  listShareRecords, listReconciles, listInvoices, getInvoice, saveShareRule, saveInvoice, auditWithdrawal, payWithdrawal, applyWithdrawal,
  shareSummaries, listShareSummaries,
  rechargeOrders, listRechargeOrders,
  rechargePackages, listRechargePackages, saveRechargePackage,
  archiveRechargePackage, unarchiveRechargePackage,
  // S1 结算单：生成 / 确认 / 构成明细
  listSettlements, generateSettlements, confirmSettlement, transitionSettlement,
  listSettlementRecords, aggregateShareRecords, SettlementError,
  // S2 对账差错处理 / 发票开具作废
  handleRecon, getReconStats, ReconError, reconDiffs, listReconDiffs,
  // 账务分录：凭证下钻 + 借贷平衡 + 期间筛选 + 手工记账（借贷平衡在 db 层强制）
  listVoucherEntries, voucherBalance, listLedgerInPeriod, createVoucher, VoucherError,
  issueInvoice, voidInvoice, InvoiceError,
  // 收款账户（B3）：提现审批的前置 —— 没有它审批完不知道往哪打钱
  payoutAccounts, listPayoutAccounts, defaultPayoutAccountOf, savePayoutAccount, disablePayoutAccount,
} from "./finance";
export type { ShareSummaryQuery, RechargeQuery, SettlementQuery, ShareRecordQuery, ReconQuery, InvoiceQuery } from "./finance";

export {
  cUsers, userRisks, userBlacklist, members, wallets, freeWhitelist, consumerSegments,
  listMembers, listWallets, listUserRisks, listUserBlacklist, listConsumerSegments,
  saveMember, saveWallet,
  listFreeWhitelist, saveFreeWhitelist, revokeFreeWhitelist,
  // 2026-09-25 消费者受理队列：C 端在产生数据、运营端此前没有动作面
  logoffs, listLogoffs, revokeLogoff,
  cuserInvoices, listCUserInvoices, issueCUserInvoice, rejectCUserInvoice,
  // S2：信用分调整（上下限强制 + 风控等级联动 + 变更留痕）
  creditScoreChanges, adjustCreditScore, listCreditScoreChanges, CreditScoreError,
  // 钱包流水（后端 /api/user/wallets/{userNo}/txns 的前端入口）
  walletTxns, listWalletTxns, sumPrincipal, sumBonus,
  // S4 用户详情 + 会员权益/次卡发放
  getUserProfileBase,
  memberBenefits, listMemberBenefits, saveMemberBenefit, MemberBenefitError,
  memberCards, listMemberCards, grantMemberCard, MemberCardError,
} from "./user";

export {
  coupons, saveCoupon, campaigns, pushMessages, referrals,
  adSlots, adCampaigns, adDeliveries,
  listCampaigns, listPushMessages, listReferrals, listAdSlots, listAdCampaigns, listAdDeliveries,
  saveCampaign, savePushMessage, saveAdSlot, saveAdCampaign,
  notices, listNotices, saveNotice,
  archiveCoupon, unarchiveCoupon, archiveNotice, unarchiveNotice,
  // S2：优惠券发放 / 推送发送
  couponIssueRecords, listCouponIssueRecords, issueCoupon,
  // 广告投放动作 + 曝光按周期过滤（动作挂广告活动，不挂曝光事实行）
  transitionAdCampaign, AdCampaignError, listAdDeliveriesInPeriod,
  referralRules, listReferralRules, saveReferralRule, ReferralRuleError,
  resolveAudience, MEMBER_LEVELS, sendPushMessage, finishPushMessage, transitionPush,
  CouponIssueError, PushError, AudienceError,
} from "./marketing";

export {
  csTickets, csSessions, csMessages, listCsTickets, listCsSessions, saveCsTicket,
  refundCsTicket, woCsTicket, listCsMessages, replyCsSession,
  orderComplaints, refundRecords, listOrderComplaints, listRefundRecords,
  saveOrderComplaint, createOrderComplaint, handleOrderComplaint, raiseComplaintWorkOrder,
  applyRefund, createRefund, auditRefund,
} from "./cs";

export {
  siteAnalyses, listSiteAnalysis,        // 站点坪效：自 location 迁来（读模型，避免互引）
  listAgentPerformance,                  // 代理绩效：自 agent 迁来（GMV 与坪效同源）
  reportDevices, reportLocations, reportFinances, reportScreens, reportCustoms,
  listReportDevice, listReportLocation, listReportFinance, listReportScreen, listReportCustom,
  // S3 报表域成型：周期趋势 / 大屏看板 / 指标目录 / 消费者洞察
  getReportTrend, getScreenBoard, listReportMetrics, getConsumerInsight,
} from "./report";

export {
  tenants, tenantConfigs, employees, roles, audits, departments, staffPerformances,
  listEmployees, listDepartments, listStaffPerformance,
  saveDepartment, saveRoleRow, saveEmployee, getDataScope, saveDataScope,
  listRoles, archiveRole, unarchiveRole,
  // S6 功能权限勾选树 + 审计详情
  permissions, listRolePermissions, saveRolePermissions, getAuditDetail,
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
  brands, listBrands, saveBrand, archiveBrand, unarchiveBrand,
  banks, listBanks, saveBank,
  problems, listProblems, saveProblem,
  taxSettings, listTaxSettings, saveTaxSetting,
  archiveBank, unarchiveBank, archiveProblem, unarchiveProblem,
  // S6/S7：地区库树 / 供应商连通性探测 / 模板预览与试发 / 发送记录重发 / OpenAPI 密钥重置
  listRegionTree, testVendorConnectivity, VendorProbeError,
  previewNotifyTemplate, testSendNotifyTemplate, resendNotifyLog, NotifySendError,
  resetOpenApiAppSecret,
} from "./system";

export {
  getOperationOverview, getSiteStats, pauseSite, resumeSite,
} from "./operation";

export {
  priceAdjustments, tickAdjustments, listPriceAdjustments, savePriceAdjustment,
  cancelPriceAdjustment, revertPriceAdjustment, retryPriceAdjustment,
} from "./adjust";

export { listSiteSharing, getSiteSharingStats, listPayeeSharing } from "./sharing";
