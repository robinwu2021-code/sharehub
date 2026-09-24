// 覆盖范围：分账规则与流水、总账、结算、提现审批、对账、发票、分润统计、充值订单。
// 端点前缀：/api/trade/**；充值订单与钱包同主体，归 /api/user/**。
import { client } from "../http-client";
import type { FinanceApi } from "../contracts/finance";
import type { PageQ, ShareRuleQ, ShareSummaryQ, RechargeQ, SettlementQ, ShareRecordQ, ReconQ, InvoiceQ , ReportQ } from "../query";

export const financeHttp: FinanceApi = {
  listPayoutAccounts: (q) => client.get("/api/trade/payout-accounts", q),
  savePayoutAccount: (x) => client.post("/api/trade/payout-accounts", x),
  disablePayoutAccount: (no) => client.post(`/api/trade/payout-accounts/${no}/disable`, {}),

  // ⚠️ 后端缺口（参数级）：GET /api/trade/share-rules（TradeController#shareRules）只收 page/size/keyword，
  //    不认 dimension。双向视图的视角参数照传，后端补上 dimension 过滤前，切视角在真后端下不生效
  //    （不改成前端自己筛：一筛就只筛当前页，翻页立刻打脸）。
  listShareRules: (q?: ShareRuleQ) => client.get("/api/trade/share-rules", q),
  listLedger: (q?: ReportQ) => client.get("/api/trade/ledger", q),
  // ⚠️ 后端缺口：凭证下钻端点不存在（TradeController 只有平铺的 /ledger）。
  getVoucher: (no) => client.get(`/api/trade/ledger/vouchers/${no}`),
  // ⚠️ 后端缺口：手工记账端点不存在。后端实现时**借贷平衡必须在服务端校验**，
  //    且只允许新增、不允许改历史分录（更正走反向凭证）。
  createVoucher: (x) => client.post("/api/trade/ledger/vouchers", x),
  listSettlements: (q?: SettlementQ) => client.get("/api/trade/settlements", q),
  listWithdrawals: (q?: PageQ) => client.get("/api/trade/withdrawals", q),
  auditWithdrawal: (no, approve, rejectReason, auditorName) => client.post(`/api/trade/withdrawals/${no}/audit`, { approve, rejectReason, auditorName }),
  // 打款回执：与 audit 分开的端点、分开的权限码（finance:withdrawal:pay）——
  // 审批是「同意打出去」，回执是「确实出去了」，中间隔着一次真实资金动作
  payWithdrawal: (no, body) => client.post(`/api/trade/withdrawals/${no}/pay`, body),
  applyWithdrawal: (body) => client.post("/api/trade/withdrawals", body),

  // S1 结算单闭环：生成/确认都是动作端点（状态迁移），详情明细挂在结算单资源下
  //
  // ⚠️ T0-3 待拍板，**故意未改路径**：后端唯一的出账实现是
  //    POST /internal/trade/settlements/generate，且注释明写「批处理作业调用，非页面入口，
  //    内网受信，故不挂 @PreAuthorize」。把前端指过去能让按钮通，但等于让浏览器调一个
  //    无权限校验的内网端点 —— 这是放大攻击面，不是修 bug。
  //    另有形状不符：后端只收 {period, payeeType} 且返回 List<String>（单号），
  //    前端契约是 SettlementDraft{payeeNos[]} → Settlement[]。
  //    二选一：后端补 POST /api/trade/settlements/generate（挂 finance:settlement:create），
  //    或运营端撤掉「生成结算单」按钮、改由定时任务出账。
  generateSettlements: (x) => client.post("/api/trade/settlements/generate", x),
  confirmSettlement: (no, operatorName) => client.post(`/api/trade/settlements/${no}/confirm`, { operatorName }),
  // ⚠️ T1-D 后端缺口：结算单明细子资源无端点。
  listSettlementRecords: (no, q?: PageQ) => client.get(`/api/trade/settlements/${no}/records`, q),

  // 财务扩展
  listShareRecords: (q?: ShareRecordQ) => client.get("/api/trade/share-records", q),
  listReconciles: (q?: ReconQ) => client.get("/api/trade/reconciles", q),
  listInvoices: (q?: InvoiceQ) => client.get("/api/trade/invoices", q),
  saveShareRule: (x) => client.post(x.ruleNo ? `/api/trade/share-rules/${x.ruleNo}` : "/api/trade/share-rules", x),
  saveInvoice: (x) => client.post(x.invoiceNo ? `/api/trade/invoices/${x.invoiceNo}` : "/api/trade/invoices", x),

  // S2：差错处置与开具/作废都是动作端点（状态迁移），汇总是对账资源下的聚合子资源
  // T0-2：后端动作名是 /resolve（FinanceController#resolveRecon），此前发 /handle 直接 404。
  // ⚠️ 语义缩水：后端只读 body 里的 diffId（不带则处置该批次全部未处置差错），
  //    完全忽略 action / handleNote / operatorName —— 页面上「核销/平台承担/渠道承担/补偿」
  //    四种处置到后端会塌缩成同一个动作，且处置理由不留痕。字段照传，待后端补齐 ReconResolveReq。
  //    diffId 是后端唯一真读的字段，差错明细抽屉逐条处置时透传（不传 = 整批处置）。
  listReconDiffs: (no) => client.get(`/api/trade/reconciles/${no}/diffs`),
  handleRecon: (no, action, handleNote, operatorName, diffId) =>
    client.post(`/api/trade/reconciles/${no}/resolve`, { action, handleNote, operatorName, diffId }),
  // ⚠️ T1-D 后端缺口：/reconciles/stats 无端点（后端只有 /{batchNo}/diffs）。
  getReconStats: () => client.get("/api/trade/reconciles/stats"),
  // ⚠️ T1-D 后端缺口：开具/作废无动作端点（后端只有 POST /invoices 与 /invoices/{no} upsert）。
  issueInvoice: (no, operatorName) => client.post(`/api/trade/invoices/${no}/issue`, { operatorName }),
  voidInvoice: (no, voidReason, operatorName) => client.post(`/api/trade/invoices/${no}/void`, { voidReason, operatorName }),

  // 财务 B5：分润统计（trade 域聚合）/ 充值订单（钱包同主体，归 user 域）
  listShareSummaries: (q?: ShareSummaryQ) => client.get("/api/trade/share-summaries", q),
  listRechargeOrders: (q?: RechargeQ) => client.get("/api/user/recharge-orders", q),
};
