"use client";

import { Suspense, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import { PageTitle, Pagination, StatCard } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab } from "@/lib/hooks/use-page-tab";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column, type SortDir } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Notice } from "@/components/ui/notice";
import { Tabs } from "@/components/ui/tabs";
import { DateInput } from "@/components/ui/date-input";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { money, fmtTime } from "@/lib/utils";
import { useAuth } from "@/lib/auth";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import type {
  ShareRule, Settlement, SettlementStatus, Withdrawal, LedgerEntry, ShareRecord,
  Reconcile, ReconAction, ReconHandleStatus, ReconDiff, ReconDiffType, Invoice, InvoiceStatus,
  ShareSummary, RechargeOrder, PageResult,

  VoucherCreatePayload, PayoutAccount, PayReceiptPayload, PayChannel,
} from "@/lib/types";
import {
  SHARE_BASES,
  RECON_TRANSITIONS, RECON_TERMINAL, canReconTransition, canEditInvoiceFields, parseReconDiffDetail,
  withdrawFeeOf, withdrawNetOf, WITHDRAW_FEE_PENDING,
  canPayWithdrawal, payReceiptError, PAY_CHANNELS, withdrawApplyError, computeWithdrawFee,
  // 记账期间复用报表域枚举：与站点坪效/代理绩效/绩效报表同一套周期口径
  REPORT_PERIODS, REPORT_PERIOD_DEFAULT, type ReportPeriod,
} from "@/lib/types";

/** 周期码 → 中文标签。取自 REPORT_PERIODS，不另抄一份。 */
const periodLabel = (p: string) => REPORT_PERIODS.find((x) => x.value === p)?.label ?? p;
// tab 只声明「有哪些、什么顺序」。名字与权限来自 nav.ts（见 navTabs）——
// 此前这里自己写了一份 label（把「提现审核」写成「提现」），且完全不判权：
// 没有 finance:withdrawal:read 的角色照样看得到并点得动那个 tab。
const TAB_KEYS = ["rules", "records", "summary", "settlements", "ledger",
  "withdrawals", "payout-accounts", "reconcile", "invoices", "recharges"] as const;

// 分润统计：维度切换器（竞品把「运营商佣金」「商户佣金」拆成两套菜单两张表，
// 我们一张表切 dimension——列完全相同，少一次跳转）
const SUMMARY_DIMS = [{ key: "VENUE", label: "场地方" }, { key: "AGENT", label: "代理商" }];
// 分润规则 · 双向视图：竞品「场地方分成」「代理商分成」是两个菜单两套规则，
// 我们是**同一份规则**按分成主体分开看——切视角只换 dimension 筛选，不是两套数据。
const RULE_VIEWS = [{ key: "VENUE", label: "按场地方看" }, { key: "AGENT", label: "按代理商看" }];
const RULE_VIEW_LABEL = { VENUE: "场地方", AGENT: "代理商" } as const;
const SUMMARY_PERIODS = ["2026-07", "2026-06", "2026-05"];
const PERIOD_OPTIONS = SUMMARY_PERIODS.map((p) => ({ value: p, label: p }));
const RECHARGE_STATUS: StatusMap<RechargeOrder["status"]> = {
  PENDING: { label: "待支付", tone: "warning" },
  PAID: { label: "已支付", tone: "success" },
  FAILED: { label: "支付失败", tone: "danger" },
  REFUNDED: { label: "已退款", tone: "muted" },
};

// 结算单状态：全站同色（待确认=warning / 已确认=default / 已打款=success）
const STL_STATUS: StatusMap<SettlementStatus> = {
  DRAFT: { label: "待确认", tone: "warning" },
  CONFIRMED: { label: "已确认", tone: "default" },
  PAID: { label: "已打款", tone: "success" },
};
// 提现状态：原先徽标直接印枚举值（"AUDIT"/"PAID"），那是系统内部词（规范 §13）。
// 键序 = 资金流转顺序：申请 → 审批 → 打款 → 到账 / 驳回。
/** 收款账户状态。停用不是删除 —— 历史提现单要能回溯到当时打给了哪条记录。 */
const PA_STATUS: StatusMap<PayoutAccount["status"]> = {
  ACTIVE: { label: "启用", tone: "success" },
  DISABLED: { label: "已停用", tone: "muted" },
};

/**
 * 收款账户字段。
 *
 * **账号填明文、只落掩码** —— 明文属于 PII，服务端不回传，所以编辑既有账户时
 * 这一栏是空的（不是「丢了」），留空即不改。
 */
const PAYOUT_FIELDS: FieldDef[] = [
  { key: "payeeType", label: "受益方类型", type: "select", required: true,
    options: [{ value: "AGENT", label: "代理商" }, { value: "VENUE", label: "场地方" }] },
  { key: "payeeNo", label: "受益方编号", required: true, placeholder: "如 AG001 / VN001" },
  { key: "bankCode", label: "开户行", required: true, placeholder: "如 ENBD / ADCB / FAB" },
  { key: "accountName", label: "户名", required: true,
    placeholder: "须与主体法人名一致，否则银行会退回" },
  { key: "accountMasked", label: "账号 / IBAN", required: true,
    placeholder: "填完整账号；系统只保存后四位" },
  { key: "currency", label: "币种", type: "select",
    options: [{ value: "AED", label: "AED" }, { value: "SAR", label: "SAR" }] },
];

const WD_STATUS: StatusMap<Withdrawal["status"]> = {
  APPLY: { label: "待审批", tone: "warning" },
  AUDIT: { label: "审批中", tone: "warning" },
  PAYING: { label: "打款中", tone: "info" },
  PAID: { label: "已打款", tone: "success" },
  // 驳回与打款失败共用 FAILED（两者都带 rejectReason），故文案兼表两义
  FAILED: { label: "驳回 / 失败", tone: "danger" },
};
const PAYEE_TYPE_LABEL = { VENUE: "场地方", AGENT: "代理商" } as const;
/**
 * 分成依据（ADR-027 §四）。一单里同一个伙伴可以有出资 + 运维两条，
 * **靠这一列区分** —— 没有它，明细里就是几条看起来一模一样、金额却不同的行。
 * 空串 = 不适用（场地方维度；以及 V53 之前配的老规则）。
 */
const SHARE_BASIS_LABEL: Record<string, string> = {
  INVEST: "出资", DEVELOP: "拓展", OPERATE: "运维", REFER: "牵线",
};
/** multiselect + csv 的值是逗号分隔业务号串。 */
const csvArr = (v: unknown) => String(v ?? "").split(",").map((s) => s.trim()).filter(Boolean);

const RULE_FIELDS: FieldDef[] = [
  { key: "ruleNo", label: "规则号", readOnlyOnEdit: true, placeholder: "新增留空自动生成" },
  { key: "dimension", label: "维度", type: "select", options: [{ value: "VENUE", label: "场地方" }, { value: "AGENT", label: "代理商" }] },
  { key: "payeeName", label: "分成方", placeholder: "如 XX 商场" },
  // 留空 = 该分成方的通用规则：任何依据找不到专属规则时都回落到它。
  // 所以「不填」是有意义的一档，不能做成必填。
  {
    key: "basis", label: "分成依据", type: "select", help: "留空 = 通用规则（该分成方的任何责任都能回落到它）",
    options: [{ value: "", label: "通用（不分责任）" },
      ...SHARE_BASES.map((b) => ({ value: b, label: SHARE_BASIS_LABEL[b] }))],
  },
  { key: "mode", label: "模式", type: "select", options: [{ value: "CHANNEL_SPLIT", label: "渠道分账" }, { value: "LEDGER", label: "平台记账" }] },
  { key: "rate", label: "比例（0~1，如 0.3）", type: "number" },
  { key: "priority", label: "优先级", type: "number" },
];
// —— 对账差错（S2）——
// 跑批结果与处置进度是两列：status 是机器算的事实，handleStatus 是人推的进度，不混为一谈。
const RECON_HANDLE_STATUS: StatusMap<ReconHandleStatus> = {
  OPEN: { label: "待处理", tone: "danger" },
  HANDLING: { label: "处理中", tone: "warning" },
  RESOLVED: { label: "已结案", tone: "success" },
  IGNORED: { label: "已忽略", tone: "muted" },
};
/** 跑批结果（机器算出的事实，与人推的 handleStatus 分列两栏，互不写回）。 */
const RECON_STATUS: StatusMap<Reconcile["status"]> = {
  MATCHED: { label: "已平", tone: "success" },
  DIFF: { label: "有差异", tone: "danger" },
};
/** 逐笔平账标记：resolved 是布尔，映射成两个键，好让它与其它状态列同走 StatusBadge。 */
const DIFF_RESOLVED: StatusMap<"RESOLVED" | "OPEN"> = {
  RESOLVED: { label: "已平账", tone: "success" },
  OPEN: { label: "未处置", tone: "danger" },
};
const RECON_RESULT_LABEL: Record<string, string> = {
  VERIFIED_OK: "核对无误", PLATFORM_ERROR: "平台侧差错", CHANNEL_ERROR: "渠道侧差错", COMPENSATED: "已补差",
};
/** 差错类型（子表 recon_diff.diff_type）：一句话说清「差在哪一侧」。 */
const RECON_DIFF_TYPE: StatusMap<ReconDiffType> = {
  ONLY_IN_NEARPAY: { label: "渠道单边", tone: "danger" },
  ONLY_IN_LEDGER: { label: "我方单边", tone: "danger" },
  AMOUNT_MISMATCH: { label: "金额不等", tone: "warning" },
  STATUS_MISMATCH: { label: "状态不一致", tone: "muted" },
};
/** 差错方向由 diff 的正负推出（不是新造的分类字段，就是同一个数的解读）。 */
const diffSideLabel = (diff: number) =>
  diff === 0 ? "已平" : diff > 0 ? "渠道多 / 账务少记" : "账务多记 / 渠道少到账";

// —— 发票（S2）——
const INV_STATUS: StatusMap<InvoiceStatus> = {
  DRAFT: { label: "草稿", tone: "muted" },
  ISSUED: { label: "已开具", tone: "success" },
  VOID: { label: "已作废", tone: "danger" },
};
// 状态不在表单里：开具/作废是显式动作（要生成票号、留痕、必填原因），不能靠改个下拉框糊过去。
const INVOICE_FIELDS_BASE: FieldDef[] = [
  { key: "invoiceNo", label: "发票号", readOnlyOnEdit: true, placeholder: "新增留空自动生成" },
  { key: "payeeName", label: "抬头", required: true, placeholder: "开票抬头" },
  { key: "amount", label: "金额", type: "number", required: true, help: "必须与来源结算单金额一致，否则开具时会被拒绝" },
  { key: "vatTrn", label: "VAT TRN", required: true, placeholder: "税号" },
  { key: "currency", label: "币种", placeholder: "AED" },
];

function FinanceInner() {
  const sp = useSearchParams();
  const paging = usePaging();
  const tabs = useNavTabs("/finance", TAB_KEYS);
  const { tab, setTab } = usePageTab(tabs, paging.reset);
  // 账务分录期间（缺省近 30 日，与坪效/绩效同一套 REPORT_PERIODS）
  const [ledgerPeriod, setLedgerPeriod] = useState<ReportPeriod>(REPORT_PERIOD_DEFAULT);
  // 凭证下钻：会计上有意义的单位是「凭证」而非单条分录（一借一贷必须等额）
  const [voucherNo, setVoucherNo] = useState<string | null>(null);
  // 手工记账。凭证是「一组分录」而不是一条，故用数组而非单对象 —— 借贷必须成对。
  const [entryDraft, setEntryDraft] = useState<{
    summary: string; orderNo: string;
    rows: { account: string; direction: "DEBIT" | "CREDIT"; amount: string }[];
  } | null>(null);
  const [keyword, setKeyword] = useState("");
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const { confirm, dialog } = useConfirm();
  // 结算单（S1）：生成抽屉 / 详情抽屉（详情展示构成它的分润明细）
  const [genForm, setGenForm] = useState<{ payeeType: string; period: string; payeeNos: string } | null>(null);
  const [stlDetail, setStlDetail] = useState<Settlement | null>(null);
  const [stlStatus, setStlStatus] = useState("");
  const [ruleForm, setRuleForm] = useState<Partial<ShareRule> | null>(null);
  // 分润规则视角：默认场地方（规则数量最多的一侧）
  const [ruleDim, setRuleDim] = useState<"VENUE" | "AGENT">("VENUE");
  const [invoiceForm, setInvoiceForm] = useState<Partial<Invoice> | null>(null);
  // 对账差错处置（S2）：处理走抽屉——结论必填，与提现审批同一套「审批类抽屉」范式
  const [reconHandle, setReconHandle] = useState<Reconcile | null>(null);
  const [reconAction, setReconAction] = useState<ReconAction>("verify");
  const [reconNote, setReconNote] = useState("");
  // 差错明细下钻：批次行只给「差了多少钱」，差在哪几笔要看子表——也是逐条处置的取号来源。
  // reconDiffRow 非空 = 本次处置只针对这一条（带 diffId），为空 = 整批处置。
  const [reconDiffsOf, setReconDiffsOf] = useState<Reconcile | null>(null);
  const [reconDiffRow, setReconDiffRow] = useState<ReconDiff | null>(null);
  const [reconStatusFilter, setReconStatusFilter] = useState("");
  // 发票（S2）：详情下钻（含来源结算单核对）+ 作废抽屉（原因必填 + 二次确认）
  const [invDetail, setInvDetail] = useState<Invoice | null>(null);
  const [invVoid, setInvVoid] = useState<Invoice | null>(null);
  const [invVoidReason, setInvVoidReason] = useState("");
  const [invStatusFilter, setInvStatusFilter] = useState("");
  // 提现审批：走抽屉而非行内按钮——驳回必须留原因，是资金审批的留痕底线
  const [wdAudit, setWdAudit] = useState<Withdrawal | null>(null);
  // 申请提现（代理端自助）。运营端没有也不该有创建入口（api/README §六·A）
  const [wdApplyOpen, setWdApplyOpen] = useState(false);
  const [wdApplyAmount, setWdApplyAmount] = useState("");
  // 打款回执（⑮）。与审批分开的抽屉 —— 它们是两次不同的动作，隔着一次真实资金流动
  const [wdPay, setWdPay] = useState<Withdrawal | null>(null);
  const [payOk, setPayOk] = useState("1");
  const [payChannel, setPayChannel] = useState<PayChannel>("MANUAL");
  const [payRef, setPayRef] = useState("");
  const [payFail, setPayFail] = useState("");
  const [wdApprove, setWdApprove] = useState("1");
  const [wdReject, setWdReject] = useState("");
  const username = useAuth((s) => s.username);
  const realm = useAuth((s) => s.realm);
  const currentOperatorNo = useAuth((s) => s.currentOperatorNo);
  const memberships = useAuth((s) => s.memberships);
  /*
   * 当前主体的**名字**。不能用 username —— 代理端登录后它是手机号，
   * 切过一次主体才变成主体名。提现单上的收款方写成手机号，审批人看到的就是一串号码。
   */
  const currentOperatorName = memberships.find((m) => m.operatorNo === currentOperatorNo)?.name
    ?? currentOperatorNo;
  // 分润统计：维度 / 周期 / 排序（排序受控，实际排序在 mock·后端做，翻页后仍成立）
  const [sumDim, setSumDim] = useState("VENUE");
  const [sumPeriod, setSumPeriod] = useState(SUMMARY_PERIODS[0]);
  const [sumSortKey, setSumSortKey] = useState("shareAmount");
  const [sumSortDir, setSumSortDir] = useState<SortDir>("desc");
  // 充值订单：状态 + 日期范围（按下单时间）
  const [rcStatus, setRcStatus] = useState("");
  const [rcFrom, setRcFrom] = useState("");
  const [rcTo, setRcTo] = useState("");
  useEffect(() => { setKeyword(""); }, [tab]);
  // 从分润统计深链过来：/finance?tab=records&payee=xxx —— 把 payee 落成分润明细的搜索词，
  // 不静默丢弃参数（本 effect 必须排在上面的清空 effect 之后，否则会被清掉）
  const qPayee = sp.get("payee");
  useEffect(() => { if (qPayee && tab === "records") setKeyword(qPayee); }, [qPayee, tab]);

  const canEditRule = allow("finance:share_rule:config");
  // 发票：登记草稿与开具共用 `:issue`（功能权限清单 §财务域「发票 查 / 开具」）；
  // 作废另立 `finance:invoice:void` —— 作废比开具更危险（账面凭空少一张票），不该被开票权顺带拿到。
  const canEditInvoice = allow("finance:invoice:issue");
  const canVoidInvoice = allow("finance:invoice:void");
  const canHandleRecon = allow("finance:recon:handle");
  // 差错明细是只读下钻，与「对账」菜单同一个权限码（nav.ts 上 /finance?tab=reconcile 就挂它）
  const canReadRecon = allow("finance:recon:read");

  const q = useQuery<PageResult<ShareRule | Settlement | Withdrawal | LedgerEntry | ShareRecord | Reconcile | Invoice | ShareSummary | RechargeOrder | PayoutAccount>>({
    // currentOperatorNo 进 key：代理端的列表是按主体收敛的，换了主体就是另一份数据。
    // 切换器那边也会 qc.clear()，但那是**另一处代码**的善后 —— 依赖它等于把正确性
    // 押在「以后没人改那行」上。key 带上它，这里自己就说得通。
    queryKey: ["fin", tab, paging.page, paging.size, keyword, ruleDim, sumDim, sumPeriod, sumSortKey, sumSortDir, rcStatus, rcFrom, rcTo, stlStatus, reconStatusFilter, invStatusFilter, ledgerPeriod, currentOperatorNo],
    queryFn: () =>
      tab === "rules" ? api.listShareRules({ page: paging.page, size: paging.size, keyword, dimension: ruleDim })
      : tab === "ledger" ? api.listLedger({ page: paging.page, size: paging.size, keyword, period: ledgerPeriod })
      : tab === "settlements" ? api.listSettlements({ page: paging.page, size: paging.size, keyword, status: stlStatus || undefined })
      : tab === "records" ? api.listShareRecords({ page: paging.page, size: paging.size, keyword })
      : tab === "summary" ? api.listShareSummaries({ page: paging.page, size: paging.size, keyword, dimension: sumDim, period: sumPeriod, sortKey: sumSortKey, sortDir: sumSortDir })
      : tab === "recharges" ? api.listRechargeOrders({ page: paging.page, size: paging.size, keyword, status: rcStatus || undefined, from: rcFrom || undefined, to: rcTo || undefined })
      : tab === "reconcile" ? api.listReconciles({ page: paging.page, size: paging.size, keyword, handleStatus: reconStatusFilter || undefined })
      : tab === "invoices" ? api.listInvoices({ page: paging.page, size: paging.size, keyword, status: invStatusFilter || undefined })
      : tab === "payout-accounts" ? api.listPayoutAccounts({ page: paging.page, size: paging.size, keyword })
      // 代理端只看自己的单子。**这是展示过滤，不是安全边界** ——
      // 真正管住「只能看自己的」的是服务端 AGENT 数据范围硬过滤，前端传什么它都会再交一次集。
      // 不传的话 mock 下代理会看到全平台的提现，页面看着像越权。
      : api.listWithdrawals({
          page: paging.page, size: paging.size, keyword,
          payeeNo: realm === "AGENT" ? currentOperatorNo : undefined,
        }),
    placeholderData: keepPreviousData,
  });

  const canAuditWithdrawal = allow("finance:withdrawal:audit");
  // 独立权限码：审批「同意打出去」与回执「确实出去了」分开，便于将来做双人复核
  const canPayWithdrawal_ = allow("finance:withdrawal:pay");
  // —— 收款账户（B3）：读写分开发码，能看账户不等于能改账户 ——
  const canEditPayout = allow("finance:payout_account:update");
  const [payoutForm, setPayoutForm] = useState<Record<string, unknown> | null>(null);
  /*
   * 代理门户的「你还不能收款」判据（B3）。
   *
   * **判据是有没有可用收款账户，不是主体状态** —— agt_agent.status=ENABLED 只说明能经营
   * （能铺设备、能产生分润明细），能不能提现看这里（ADR-030 §3.6）。
   * ai-shop 因为没分开，出现过「商家能卖、订单在来、结算单在生成，而收款号解析不到，
   * 账单留空钱欠着，商家一路上没收到任何提示」。
   */
  const myPayoutQ = useQuery({
    queryKey: ["fin", "my-payout", currentOperatorNo],
    // 只为判断「有没有默认账户」，一次取全量（同本页其它下拉的做法）
    queryFn: () => api.listPayoutAccounts({ page: 1, size: UNPAGED_SIZE, payeeType: "AGENT", payeeNo: currentOperatorNo }),
    enabled: realm === "AGENT" && !!currentOperatorNo && tab === "withdrawals",
  });
  const cannotGetPaidYet = realm === "AGENT" && myPayoutQ.isSuccess
    && !(myPayoutQ.data?.list ?? []).some((a) => a.status === "ACTIVE" && a.isDefault);

  const afterPayoutWrite = () => {
    qc.invalidateQueries({ queryKey: ["fin", "payout-accounts"] });
    // 提现审批读的是同一份账户 —— 账户变了，「能不能放行」也跟着变
    qc.invalidateQueries({ queryKey: ["fin", "withdrawals"] });
  };
  const savePayout = useMutation({
    mutationFn: (v: Record<string, unknown>) => api.savePayoutAccount({
      accountNo: v.accountNo ? String(v.accountNo) : undefined,
      payeeType: (v.payeeType as PayoutAccount["payeeType"]) ?? "AGENT",
      payeeNo: String(v.payeeNo ?? ""),
      bankCode: String(v.bankCode ?? ""),
      accountName: String(v.accountName ?? ""),
      accountMasked: String(v.accountMasked ?? ""),
      currency: v.currency ? String(v.currency) : undefined,
      makeDefault: v.makeDefault === true,
    }),
    onSuccess: () => { notify.success("收款账户已保存"); setPayoutForm(null); afterPayoutWrite(); },
    onError: (e: Error) => notify.error(e.message),
  });
  const setPayoutDefault = useMutation({
    mutationFn: (a: PayoutAccount) => api.savePayoutAccount({
      accountNo: a.accountNo, payeeType: a.payeeType, payeeNo: a.payeeNo,
      bankCode: a.bankCode, accountName: a.accountName,
      // 改默认时原样回传掩码：服务端见 **** 开头不再二次掩码，账号不变
      accountMasked: a.accountMasked,
      currency: a.currency, makeDefault: true,
    }),
    onSuccess: () => { notify.success("已设为默认收款账户"); afterPayoutWrite(); },
    onError: (e: Error) => notify.error(e.message),
  });
  const disablePayout = useMutation({
    mutationFn: (no: string) => api.disablePayoutAccount(no),
    onSuccess: () => { notify.success("已停用"); afterPayoutWrite(); },
    onError: (e: Error) => notify.error(e.message),
  });
  // —— 提现手续费接「业务规则」（S7）——
  // 费率/封顶/最低提现额的唯一来源是 系统设置 · 业务规则（/system?tab=rules），页面上原先写死 0.6%
  // 与它并存：改了规则页提现页不动，正是「口径分叉 → 对账差钱」。这里改成读同一份配置。
  // 取不到（未加载 / 无 system:biz_rule:read 权限）时退回落库手续费，绝不自己编一个费率。
  const bizRulesQ = useQuery({
    queryKey: ["fin", "biz-rules"],
    queryFn: () => api.getBizRules(),
    enabled: tab === "withdrawals",
  });
  const feeRule = bizRulesQ.data?.withdraw;
  const audit = useMutation({
    mutationFn: (v: { no: string; approve: boolean; rejectReason?: string }) =>
      api.auditWithdrawal(v.no, v.approve, v.rejectReason, username || undefined),
    onSuccess: (_r, v) => {
      notify.success(v.approve ? "提现已通过，转打款中" : "提现已驳回");
      qc.invalidateQueries({ queryKey: ["fin"] });
      setWdAudit(null);
    },
  });

  const canApplyWithdrawal = allow("finance:withdrawal:apply");
  const applyWithdraw = useMutation({
    mutationFn: (amount: number) => api.applyWithdrawal({
      payeeType: "AGENT", payeeNo: currentOperatorNo, payeeName: currentOperatorName,
      amount, currency: "AED",
    }),
    onSuccess: (w) => {
      notify.success(`提现申请 ${w.withdrawNo} 已提交，等待审核`);
      qc.invalidateQueries({ queryKey: ["fin"] });
      setWdApplyOpen(false); setWdApplyAmount("");
    },
  });
  /** 申请金额的校验与 mock/后端共用同一份判据，不各写一遍。 */
  const applyAmountErr = () => withdrawApplyError(
    Number(wdApplyAmount),
    feeRule ? { ...feeRule, minAmount: bizRulesQ.data?.withdraw?.minAmount } : undefined,
  );

  const payReceipt = useMutation({
    mutationFn: (v: { no: string; body: PayReceiptPayload }) => api.payWithdrawal(v.no, v.body),
    onSuccess: (_r, v) => {
      notify.success(v.body.success ? "已登记到账" : "已登记打款失败");
      qc.invalidateQueries({ queryKey: ["fin"] });
      setWdPay(null);
    },
  });
  /** 当前回执草稿。页面与 mock/后端共用 payReceiptError 这一份判据，不各写一遍。 */
  const payDraft = (): PayReceiptPayload => ({
    success: payOk === "1", channel: payChannel,
    payRef: payRef, failReason: payFail,
  });

  // —— 结算单闭环（S1）——
  // 生成：金额从该周期的分润明细汇总而来；确认：DRAFT → CONFIRMED。
  // 幂等冲突 / 无明细 / 非法状态迁移都由服务端（mock db 层）拒绝并给出可读原因，页面不重复兜底。
  const canGenSettlement = allow("finance:settlement:generate");
  const canConfirmSettlement = allow("finance:settlement:confirm");
  // 结算对象候选：场地方 / 代理商各自的主数据，抽屉打开才拉
  const venuesQ = useQuery({
    queryKey: ["stl-venues"],
    queryFn: () => api.listVenues({ page: 1, size: UNPAGED_SIZE }),
    enabled: !!genForm && genForm.payeeType === "VENUE",
  });
  const agentsQ = useQuery({
    queryKey: ["stl-agents"],
    queryFn: () => api.listAgents({ page: 1, size: UNPAGED_SIZE }),
    enabled: !!genForm && genForm.payeeType === "AGENT",
  });
  // 结算单构成明细：这张单的钱是哪几笔分润凑出来的
  const stlRecordsQ = useQuery({
    queryKey: ["stl-records", stlDetail?.settleNo ?? ""],
    queryFn: () => api.listSettlementRecords(stlDetail!.settleNo, { page: 1, size: UNPAGED_SIZE }),
    enabled: !!stlDetail,
  });

  const payeeOptions = useMemo(() => {
    if (genForm?.payeeType === "AGENT") {
      return (agentsQ.data?.list ?? []).filter((a) => !a.archivedAt).map((a) => ({ value: a.agentNo, label: `${a.agentNo} · ${a.name}` }));
    }
    return (venuesQ.data?.list ?? []).filter((v) => !v.archivedAt).map((v) => ({ value: v.venueNo, label: `${v.venueNo} · ${v.name}` }));
  }, [genForm?.payeeType, venuesQ.data, agentsQ.data]);

  const GEN_FIELDS: FieldDef[] = useMemo(() => [
    {
      key: "payeeType", label: "结算对象类型", type: "select", required: true,
      options: [{ value: "VENUE", label: "场地方" }, { value: "AGENT", label: "代理商" }],
    },
    {
      key: "period", label: "结算周期", type: "select", required: true,
      options: SUMMARY_PERIODS.map((p) => ({ value: p, label: p })),
      help: "金额取该周期分润明细的汇总值；同一对象同一周期只能出一次账",
    },
    {
      key: "payeeNos", label: "结算对象（可多选）", type: "multiselect", csv: true, required: true,
      placeholder: "选择要出账的对象", options: payeeOptions,
      help: "该周期没有分润明细的对象会被拒绝——结算金额不凭空生成",
    },
  ], [payeeOptions]);

  const genSettlements = useMutation({
    mutationFn: (v: { payeeType: "VENUE" | "AGENT"; period: string; payeeNos: string[] }) =>
      api.generateSettlements({ ...v, operatorName: username || undefined }),
    onSuccess: (rows) => {
      qc.invalidateQueries({ queryKey: ["fin"] });
      notify.success(`已生成 ${rows.length} 张结算单（待确认），合计 ${money(rows.reduce((s, r) => s + r.totalAmount, 0), rows[0]?.currency ?? "AED")}`);
      setGenForm(null);
    },
  });
  const confirmSettlement = useMutation({
    mutationFn: (no: string) => api.confirmSettlement(no, username || undefined),
    onSuccess: (s) => {
      qc.invalidateQueries({ queryKey: ["fin"] });
      notify.success(`结算单 ${s.settleNo} 已确认`);
      setStlDetail((cur) => (cur && cur.settleNo === s.settleNo ? s : cur));
    },
  });

  async function submitGen() {
    if (!genForm) return;
    const payeeNos = csvArr(genForm.payeeNos);
    if (!genForm.payeeType || !genForm.period) { notify.error("请选择结算对象类型与周期"); return; }
    if (!payeeNos.length) { notify.error("请至少选择一个结算对象"); return; }
    const ok = await confirm({
      title: "确认生成结算单",
      desc: `将为 ${payeeNos.length} 个${PAYEE_TYPE_LABEL[genForm.payeeType as "VENUE" | "AGENT"]}生成 ${genForm.period} 的结算单（状态：待确认）。`
        + "金额来自该周期分润明细汇总，同一对象同一周期不可重复生成。",
      confirmText: "生成",
    });
    if (ok) genSettlements.mutate({ payeeType: genForm.payeeType as "VENUE" | "AGENT", period: genForm.period, payeeNos });
  }
  async function askConfirmSettlement(s: Settlement) {
    const ok = await confirm({
      title: `确认结算 ${s.settleNo}`,
      desc: `确认后 ${s.payeeName}（${s.period}）的 ${money(s.totalAmount, s.currency)} 进入应付，金额锁定不可再改，确认人与时间将留痕。`,
      confirmText: "确认结算",
    });
    if (ok) confirmSettlement.mutate(s.settleNo);
  }

  // —— S2 对账差错处理 ——
  // 汇总条与列表同源于服务端的 reconciles：处理完一笔，未结笔数/金额当场下降（不是前端自己算的）。
  // queryKey 挂在 ["fin"] 下，处理成功后统一 invalidate，一次刷新汇总 + 列表。
  const reconStatsQ = useQuery({
    queryKey: ["fin", "recon-stats"],
    queryFn: () => api.getReconStats(),
    enabled: tab === "reconcile",
  });
  // 差错明细：抽屉打开才拉；queryKey 同样挂在 ["fin"] 下，处置成功后一次 invalidate 刷新明细+汇总+列表
  const reconDiffsQ = useQuery({
    queryKey: ["fin", "recon-diffs", reconDiffsOf?.batchNo ?? ""],
    queryFn: () => api.listReconDiffs(reconDiffsOf!.batchNo),
    enabled: !!reconDiffsOf,
  });
  const handleRecon = useMutation({
    mutationFn: (v: { batchNo: string; action: ReconAction; note: string; diffId?: number }) =>
      api.handleRecon(v.batchNo, v.action, v.note, username || undefined, v.diffId),
    onSuccess: (r, v) => {
      qc.invalidateQueries({ queryKey: ["fin"] });
      // 逐条处置时批次进度可能还没动（半平不算平），所以提示按「处置了哪一条」说，不谎报批次已结案
      notify.success(v.diffId
        ? `差错明细 #${v.diffId} 已平账（${RECON_TRANSITIONS[v.action].label}）`
        : `差错 ${r.batchNo} 已${RECON_HANDLE_STATUS[r.handleStatus!].label}（${RECON_TRANSITIONS[v.action].label}）`);
      setReconHandle(null);
      setReconDiffRow(null);
    },
  });
  /** 当前差错允许的动作：状态机说了算，终态返回空数组（页面因此不出处理按钮）。 */
  const reconActionsFor = (r: Reconcile | null): ReconAction[] =>
    !r || r.handleStatus === null ? []
      : (Object.keys(RECON_TRANSITIONS) as ReconAction[]).filter((a) => canReconTransition(r.handleStatus!, a));
  /** 打开处置抽屉。带 diff = 只处置这一条（抽屉里会带上它的支付单号与两侧金额）。 */
  function openReconHandle(r: Reconcile, diff?: ReconDiff) {
    setReconHandle(r);
    setReconDiffRow(diff ?? null);
    setReconAction(reconActionsFor(r)[0] ?? "verify");
    setReconNote("");
  }

  // —— S2 发票开具 / 作废 ——
  // 开票只能挂在**已确认**的结算单上（草稿单还可能重算），金额由该单带出，页面不让手输一个对不上的数。
  const invSettlementsQ = useQuery({
    queryKey: ["inv-settlements"],
    queryFn: () => api.listSettlements({ page: 1, size: UNPAGED_SIZE }),
    enabled: !!invoiceForm,
  });
  const invSourceOptions = useMemo(
    () => [
      // 首项留空：原生 select 没有空选项时会显示第一条却不触发 onChange，
      // 结果「看着已选 STL700、抬头金额却是空的」——保存时才报错，太晚
      { value: "", label: "请选择来源结算单" },
      ...(invSettlementsQ.data?.list ?? [])
        .filter((s) => s.status !== "DRAFT")
        .map((s) => ({ value: s.settleNo, label: `${s.settleNo} · ${s.payeeName} · ${s.period} · ${money(s.totalAmount, s.currency)}` })),
    ],
    [invSettlementsQ.data],
  );
  const INVOICE_FIELDS: FieldDef[] = useMemo(() => [
    {
      key: "sourceNo", label: "来源结算单", type: "select", required: true, options: invSourceOptions,
      help: "只列已确认/已打款的结算单；选定后自动带出抬头、金额与币种（开具时会再校验一次）",
    },
    ...INVOICE_FIELDS_BASE,
  ], [invSourceOptions]);
  /** 选了来源结算单就把抬头/金额/币种带出来——保证「发票金额与来源单据对得上」不靠人手抄。 */
  function onInvoiceFormChange(v: Partial<Invoice>) {
    const changedSource = v.sourceNo && v.sourceNo !== invoiceForm?.sourceNo;
    const src = changedSource ? (invSettlementsQ.data?.list ?? []).find((s) => s.settleNo === v.sourceNo) : undefined;
    setInvoiceForm(src ? { ...v, payeeName: src.payeeName, amount: src.totalAmount, currency: src.currency } : v);
  }

  const issueInvoice = useMutation({
    mutationFn: (no: string) => api.issueInvoice(no, username || undefined),
    onSuccess: (inv) => {
      qc.invalidateQueries({ queryKey: ["fin"] });
      notify.success(`发票 ${inv.invoiceNo} 已开具（代码 ${inv.invoiceCode} / 号码 ${inv.invoiceNumber}），抬头与金额已锁定`);
      setInvDetail((cur) => (cur && cur.invoiceNo === inv.invoiceNo ? inv : cur));
    },
  });
  const voidInvoice = useMutation({
    mutationFn: (v: { no: string; reason: string }) => api.voidInvoice(v.no, v.reason, username || undefined),
    onSuccess: (inv) => {
      qc.invalidateQueries({ queryKey: ["fin"] });
      notify.success(`发票 ${inv.invoiceNo} 已作废`);
      setInvDetail((cur) => (cur && cur.invoiceNo === inv.invoiceNo ? inv : cur));
      setInvVoid(null);
    },
  });
  async function askIssueInvoice(inv: Invoice) {
    const ok = await confirm({
      title: `确认开具发票 ${inv.invoiceNo}`,
      desc: `将为「${inv.payeeName}」开具 ${money(inv.amount, inv.currency)} 的发票（来源结算单 ${inv.sourceNo}）。`
        + "开具后系统生成发票代码与号码，抬头与金额不可再修改，如需更正只能作废重开。",
      confirmText: "开具",
    });
    if (ok) issueInvoice.mutate(inv.invoiceNo);
  }
  async function submitVoidInvoice() {
    if (!invVoid) return;
    const reason = invVoidReason.trim();
    if (!reason) { notify.error("作废原因必填"); return; }
    const ok = await confirm({
      title: `确认作废发票 ${invVoid.invoiceNo}`,
      desc: `作废不可撤销：${money(invVoid.amount, invVoid.currency)} 的已开具发票（代码 ${invVoid.invoiceCode ?? "-"} / 号码 ${invVoid.invoiceNumber ?? "-"}）将失效，`
        + "作废人、时间与原因将留痕备查。如客户仍需开票，请作废后重新登记草稿。",
      confirmText: "确认作废",
      danger: true,
      // 不可逆（规范 §12.6）：手输发票号才解锁，防「误点两下就废掉一张已进税务口径的票」
      requireText: invVoid.invoiceNo,
    });
    if (ok) voidInvoice.mutate({ no: invVoid.invoiceNo, reason });
  }

  const saveRule = useMutation({
    mutationFn: (v: Partial<ShareRule>) => api.saveShareRule(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["fin"] }); notify.success(t("common.success")); setRuleForm(null); },
  });
  const saveInvoice = useMutation({
    mutationFn: (v: Partial<Invoice>) => api.saveInvoice(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["fin"] }); notify.success(t("common.success")); setInvoiceForm(null); },
  });

  // 视角选定后「维度」列必然是同一个值，占一列纯浪费——把它并进表头（「分成方」→「场地方 / 代理商」）
  // 业务号列一律 txt-strong（规范 §12.3 主键列加强，扫描时有锚点）；
  // 金额/比例/计数列一律 text-right + tabular-nums（§12.4），同一张表里不许一半左一半右。
  const ruleCols: Column<ShareRule>[] = [
    { header: "规则号", cell: (r) => <span className="txt-strong">{r.ruleNo}</span> },
    { header: RULE_VIEW_LABEL[ruleDim], cell: (r) => r.payeeName },
    { header: "模式", cell: (r) => <Badge tone="outline">{r.mode === "CHANNEL_SPLIT" ? "渠道分账" : "平台记账"}</Badge> },
    { header: "比例", className: "text-right", cell: (r) => <span className="tabular-nums">{(r.rate * 100).toFixed(0)}%</span> },
    { header: "优先级", className: "text-right", cell: (r) => <span className="tabular-nums">{r.priority}</span> },
    { header: t("common.actions"), cell: (r) => canEditRule ? <Button size="sm" variant="outline" onClick={() => setRuleForm(r)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];
  const stlCols: Column<Settlement>[] = [
    {
      header: "结算单号",
      cell: (s) => (
        <button type="button" className="txt-strong tabular-nums underline-offset-4 hover:underline" onClick={() => setStlDetail(s)}>
          {s.settleNo}
        </button>
      ),
    },
    { header: "对象", cell: (s) => <span>{s.payeeName} <span className="text-muted-foreground tabular-nums">{s.payeeNo}</span>（{PAYEE_TYPE_LABEL[s.payeeType]}）</span> },
    { header: "周期", cell: (s) => <span className="tabular-nums">{s.period}</span> },
    { header: "金额", className: "text-right", cell: (s) => <span className="tabular-nums">{money(s.totalAmount, s.currency)}</span> },
    // 明细笔数：金额是这几笔分润加出来的，点单号可逐笔核对
    { header: "明细笔数", className: "text-right", cell: (s) => <span className="tabular-nums text-muted-foreground">{s.recordCount}</span> },
    { header: "状态", cell: (s) => <StatusBadge map={STL_STATUS} value={s.status} /> },
    { header: "生成时间", cell: (s) => <span className="text-muted-foreground">{fmtTime(s.createdAt)}</span> },
    { header: "确认人", cell: (s) => s.confirmedBy ?? <span className="text-muted-foreground">未确认</span> },
    { header: "确认时间", cell: (s) => <span className="text-muted-foreground">{s.confirmedAt ? fmtTime(s.confirmedAt) : "-"}</span> },
    {
      header: t("common.actions"),
      cell: (s) => (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setStlDetail(s)}>明细</Button>
          {/* 只有 DRAFT 能确认（状态机 STL_TRANSITIONS），其余状态不给按钮 */}
          {s.status === "DRAFT" && canConfirmSettlement && (
            <Button size="sm" onClick={() => askConfirmSettlement(s)} disabled={confirmSettlement.isPending}>确认结算</Button>
          )}
        </div>
      ),
    },
  ];
  const wdCols: Column<Withdrawal>[] = [
    { header: "提现号", cell: (w) => <span className="txt-strong">{w.withdrawNo}</span> },
    { header: "对象", cell: (w) => w.payeeName },
    { header: "金额", className: "text-right", cell: (w) => <span className="tabular-nums">{money(w.amount, w.currency)}</span> },
    // 手续费与实际到账同屏：审批人不必心算，避免按毛额放款。
    // 两列都走 withdrawFeeOf/withdrawNetOf 同一个口径函数——未审批的按业务规则现行费率实时算，
    // 已审批的按落库值（事后调费率不该改写历史放款额），费率来源在表上方的提示条里写明。
    {
      header: "手续费",
      className: "text-right",
      cell: (w) => (
        <div>
          <span className="tabular-nums">{money(withdrawFeeOf(w, feeRule), w.currency)}</span>
          {feeRule && WITHDRAW_FEE_PENDING.includes(w.status) && (
            <div className="text-xs text-muted-foreground tabular-nums">按现行 {(feeRule.feeRate * 100).toFixed(2)}%</div>
          )}
        </div>
      ),
    },
    { header: "实际到账", className: "text-right", cell: (w) => <span className="tabular-nums">{money(withdrawNetOf(w, feeRule), w.currency)}</span> },
    { header: "状态", cell: (w) => <StatusBadge map={WD_STATUS} value={w.status} /> },
    { header: "申请时间", cell: (w) => <span className="text-muted-foreground">{fmtTime(w.appliedAt)}</span> },
    // 审批留痕三列：谁批的 / 何时批的 / 驳回为什么
    { header: "审批人", cell: (w) => w.auditorName ?? <span className="text-muted-foreground">未审批</span> },
    { header: "审批时间", cell: (w) => <span className="text-muted-foreground">{w.auditedAt ? fmtTime(w.auditedAt) : "-"}</span> },
    /*
     * 「没通过」有两种，分两列显示而不是合成一列：
     *   驳回原因 = 审批没同意，钱从没打算出去
     *   失败原因 = 批了、打了，钱又退回来了 —— 客诉与对账走的是完全不同的流程
     */
    { header: "驳回原因", cell: (w) => <span className="text-muted-foreground">{w.rejectReason ?? "-"}</span> },
    {
      header: "打款",
      cell: (w) => (
        w.failReason
          ? <span className="text-destructive">{w.failReason}</span>
          : w.payRef
            ? (
              <div>
                <div className="tabular-nums">{w.payRef}</div>
                <div className="text-xs text-muted-foreground">
                  {w.payChannel === "NEARPAY" ? "nearpay" : "人工转账"}
                  {w.payerName ? ` · ${w.payerName}` : ""}
                </div>
              </div>
            )
            : <span className="text-muted-foreground">-</span>
      ),
    },
    {
      header: t("common.actions"),
      cell: (w) => {
        if ((w.status === "AUDIT" || w.status === "APPLY") && canAuditWithdrawal) {
          return <Button size="sm" variant="outline" onClick={() => { setWdAudit(w); setWdApprove("1"); setWdReject(""); }}>审批</Button>;
        }
        // 出款在途的单子此前**没有任何后续动作** —— 它会永远停在这个状态
        if (canPayWithdrawal(w.status) && canPayWithdrawal_) {
          return (
            <Button size="sm" variant="outline" onClick={() => {
              setWdPay(w); setPayOk("1"); setPayChannel("MANUAL"); setPayRef(""); setPayFail("");
            }}>登记打款</Button>
          );
        }
        return <span className="text-muted-foreground">-</span>;
      },
    },
  ];

  // 手工记账权限：本轮在 功能权限清单 §账务分录 新登记 finance:ledger:create。
  // 记账会直接改总账，与「查账」不是一回事，故不复用 :read。
  const canPostVoucher = allow("finance:ledger:create");
  const postVoucher = useMutation({
    // 借贷平衡等五条校验全在 mock/后端强制，页面只管把错误提示出来（全局 MutationCache 接管）
    mutationFn: (x: VoucherCreatePayload) => api.createVoucher(x),
    onSuccess: (rows) => {
      qc.invalidateQueries({ queryKey: ["fin"] });
      notify.success(`凭证 ${rows[0]?.voucherNo} 已记账（${rows.length} 条分录）`);
      setEntryDraft(null);
    },
  });

  const voucherQ = useQuery({
    queryKey: ["fin-voucher", voucherNo],
    queryFn: () => api.getVoucher(voucherNo!),
    enabled: !!voucherNo,
  });

  const ledgerCols: Column<LedgerEntry>[] = [
    { header: "分录号", cell: (l) => <span className="txt-strong">{l.entryNo}</span> },
    {
      header: "凭证",
      // 凭证号做成入口：分录表是平铺的，同一张凭证的借贷两方可能隔着几页，
      // 逐条看根本判断不了平不平衡
      cell: (l) => (
        <button
          type="button"
          className="text-primary hover:underline tabular-nums"
          onClick={() => setVoucherNo(l.voucherNo)}
        >{l.voucherNo}</button>
      ),
    },
    { header: "订单", cell: (l) => <span className="text-muted-foreground">{l.orderNo ?? "-"}</span> },
    { header: "账户", cell: (l) => l.account },
    { header: "方向", cell: (l) => l.direction === "DEBIT" ? <Badge tone="outline">借</Badge> : <Badge tone="muted">贷</Badge> },
    { header: "金额", className: "text-right", cell: (l) => <span className="tabular-nums">{money(l.amount, l.currency)}</span> },
    { header: "摘要", cell: (l) => <span className="text-muted-foreground">{l.summary}</span> },
    { header: "时间", cell: (l) => <span className="text-muted-foreground">{fmtTime(l.createdAt)}</span> },
  ];

  const recordCols: Column<ShareRecord>[] = [
    { header: "明细号", cell: (r) => <span className="txt-strong">{r.recordNo}</span> },
    { header: "订单", cell: (r) => <span className="text-muted-foreground tabular-nums">{r.orderNo}</span> },
    { header: "维度", cell: (r) => PAYEE_TYPE_LABEL[r.dimension] },
    { header: "分成方", cell: (r) => <span>{r.payeeName} <span className="text-muted-foreground tabular-nums">{r.payeeNo}</span></span> },
    // 依据：同一单同一伙伴可能有两条（出资 + 运维），不显示这列就分不清哪条是哪条
    { header: "依据", cell: (r) => r.basis ? SHARE_BASIS_LABEL[r.basis] ?? r.basis : <span className="text-muted-foreground">—</span> },
    { header: "金额", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.amount, r.currency)}</span> },
    { header: "比例", className: "text-right", cell: (r) => <span className="tabular-nums">{(r.rate * 100).toFixed(0)}%</span> },
    // 周期是结算单的汇总键：明细上直接看得到它归哪一期，才对得上结算单
    { header: "周期", cell: (r) => <span className="tabular-nums">{r.period}</span> },
    { header: "时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
  ];

  // 分润统计：分成方点进去 = 深链到分润明细并带 payee（跨模块跳转一律 <Link> + 完整 href）
  const summaryCols: Column<ShareSummary>[] = [
    {
      header: "分成方",
      cell: (s) => (
        <Link href={`/finance?tab=records&payee=${encodeURIComponent(s.payeeName)}`} className="txt-strong underline-offset-4 hover:underline">
          {s.payeeName}
        </Link>
      ),
    },
    { header: "编号", cell: (s) => <span className="text-muted-foreground tabular-nums">{s.payeeNo}</span> },
    { header: "统计周期", cell: (s) => <span className="tabular-nums">{s.period}</span> },
    { header: "订单数", className: "text-right", cell: (s) => <span className="tabular-nums">{s.orderCount}</span>, sortKey: "orderCount" },
    { header: "交易额", className: "text-right", cell: (s) => <span className="tabular-nums">{money(s.gmv, s.currency)}</span>, sortKey: "gmv" },
    { header: "分润额", className: "text-right", cell: (s) => <span className="tabular-nums">{money(s.shareAmount, s.currency)}</span>, sortKey: "shareAmount" },
    { header: "已结算", className: "text-right", cell: (s) => <span className="tabular-nums text-muted-foreground">{money(s.settledAmount, s.currency)}</span> },
    // 待结算 = 分润 − 已结算：财务最关心的数，未结清高亮，结清则弱化
    {
      header: "待结算",
      className: "text-right",
      cell: (s) => (
        <span className={s.pendingAmount > 0 ? "txt-strong tabular-nums text-[var(--destructive)]" : "tabular-nums text-muted-foreground"}>
          {money(s.pendingAmount, s.currency)}
        </span>
      ),
      sortKey: "pendingAmount",
    },
  ];

  const rechargeCols: Column<RechargeOrder>[] = [
    { header: "充值单号", cell: (r) => <span className="txt-strong tabular-nums">{r.rechargeNo}</span> },
    { header: "用户", cell: (r) => <span>{r.nickname} <span className="text-muted-foreground tabular-nums">{r.userNo}</span></span> },
    { header: "套餐", cell: (r) => r.packageNo ? <span className="tabular-nums">{r.packageNo}</span> : <Badge tone="outline">自定义金额</Badge> },
    { header: "实付", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.payAmount, r.currency)}</span> },
    { header: "赠送", className: "text-right", cell: (r) => <span className="tabular-nums text-muted-foreground">{money(r.giftAmount, r.currency)}</span> },
    { header: "到账", className: "text-right", cell: (r) => <span className="txt-strong tabular-nums">{money(r.creditAmount, r.currency)}</span> },
    // 渠道码与 系统设置·支付渠道（/system?tab=payment）同一套 channelCode
    { header: "支付渠道", cell: (r) => <Badge tone="outline">{r.channelCode}</Badge> },
    { header: "状态", cell: (r) => <StatusBadge map={RECHARGE_STATUS} value={r.status} /> },
    { header: "支付时间", cell: (r) => <span className="text-muted-foreground">{r.paidAt ? fmtTime(r.paidAt) : "-"}</span> },
    { header: "网关流水号", cell: (r) => <span className="text-muted-foreground tabular-nums">{r.psgTxnNo ?? "-"}</span> },
  ];

  const reconcileCols: Column<Reconcile>[] = [
    { header: "批次号", cell: (r) => <span className="txt-strong">{r.batchNo}</span> },
    { header: "周期", cell: (r) => <span className="tabular-nums">{r.period}</span> },
    { header: "nearpay 汇总", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.nearpayTotal, r.currency)}</span> },
    { header: "账务汇总", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.ledgerTotal, r.currency)}</span> },
    // 差额同时给「多少钱」和「往哪边偏」——方向就是 diff 的正负，不是另造的分类
    {
      header: "差额",
      className: "text-right",
      cell: (r) => (
        <div>
          <span className={r.diff === 0 ? "tabular-nums text-muted-foreground" : "txt-strong tabular-nums text-[var(--destructive)]"}>
            {money(r.diff, r.currency)}
          </span>
          {r.diff !== 0 && <div className="text-xs text-muted-foreground">{diffSideLabel(r.diff)}</div>}
        </div>
      ),
    },
    { header: "跑批结果", cell: (r) => <StatusBadge map={RECON_STATUS} value={r.status} /> },
    // 处置进度是另一列：把「已核对无误」写回跑批结果会篡改事实，日后无从审计
    {
      header: "处置进度",
      cell: (r) => r.handleStatus
        ? <StatusBadge map={RECON_HANDLE_STATUS} value={r.handleStatus} />
        : <span className="text-muted-foreground">无需处理</span>,
    },
    { header: "定责", cell: (r) => <span className="text-muted-foreground">{r.handleResult ? RECON_RESULT_LABEL[r.handleResult] : "-"}</span> },
    // 结论 + 谁在什么时候处理的合成一列：留痕三件套挤三列会把表撑爆，读的时候本来也是一起看
    {
      header: "处理结论 / 处理人",
      cell: (r) => r.handleNote
        ? (
          <div className="max-w-[22rem]">
            <div>{r.handleNote}</div>
            <div className="text-xs text-muted-foreground">{r.handledBy} · {fmtTime(r.handledAt!)}</div>
          </div>
        )
        : <span className="text-muted-foreground">未处理</span>,
    },
    { header: "跑批时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
    {
      header: t("common.actions"),
      // 终态（已结案/已忽略）与已平批次都不出「处理」按钮——状态机说了不能动，页面就不该给入口。
      // 但「差错明细」终态也给：结案后照样要能查这批差在哪几笔（只读，不受状态机管）。
      cell: (r) => (
        <div className="flex gap-2">
          {r.status === "DIFF" && canReadRecon && (
            <Button size="sm" variant="outline" onClick={() => setReconDiffsOf(r)}>差错明细</Button>
          )}
          {r.handleStatus && !RECON_TERMINAL.includes(r.handleStatus) && canHandleRecon && (
            <Button size="sm" variant="outline" onClick={() => openReconHandle(r)}>处理差错</Button>
          )}
          {r.status !== "DIFF" && <span className="text-muted-foreground">-</span>}
        </div>
      ),
    },
  ];

  // 差错明细列：两侧金额 + 逐笔差额（差额由 detail 里两侧金额相减推出，不是另存的一列，
  // 所以它们加总必然等于批次差额）。处置按钮带 diff.id，落到后端 resolve 的 diffId。
  const reconDiffCurrency = reconDiffsOf?.currency ?? "AED";
  const reconDiffDelta = (d: ReconDiff) => {
    const v = parseReconDiffDetail(d.detail);
    return v ? (v.nearpay ?? 0) - (v.ledger ?? 0) : 0;
  };
  const reconDiffCols: Column<ReconDiff>[] = [
    { header: "#", className: "text-right", cell: (d) => <span className="text-muted-foreground tabular-nums">{d.id}</span> },
    { header: "支付单号", cell: (d) => <span className="txt-strong tabular-nums">{d.payNo}</span> },
    { header: "差错类型", cell: (d) => <StatusBadge map={RECON_DIFF_TYPE} value={d.diffType} /> },
    {
      header: "nearpay / 账务",
      className: "text-right",
      cell: (d) => {
        const v = parseReconDiffDetail(d.detail);
        // detail 解析不出来就退化成原文：格式变了也不该让这个抽屉打不开
        return v
          ? <span className="tabular-nums">{money(v.nearpay ?? 0, reconDiffCurrency)} / {money(v.ledger ?? 0, reconDiffCurrency)}</span>
          : <span className="text-muted-foreground">{d.detail}</span>;
      },
    },
    {
      header: "逐笔差额",
      className: "text-right",
      cell: (d) => {
        const delta = reconDiffDelta(d);
        return (
          <span className={delta === 0 ? "tabular-nums text-muted-foreground" : "txt-strong tabular-nums text-[var(--destructive)]"}>
            {money(delta, reconDiffCurrency)}
          </span>
        );
      },
    },
    { header: "说明", cell: (d) => <span className="max-w-[20rem] text-xs text-muted-foreground">{parseReconDiffDetail(d.detail)?.note ?? "-"}</span> },
    { header: "平账", cell: (d) => <StatusBadge map={DIFF_RESOLVED} value={d.resolved ? "RESOLVED" : "OPEN"} /> },
    {
      header: t("common.actions"),
      cell: (d) => !d.resolved && reconDiffsOf?.handleStatus && !RECON_TERMINAL.includes(reconDiffsOf.handleStatus) && canHandleRecon
        ? <Button size="sm" variant="outline" onClick={() => openReconHandle(reconDiffsOf, d)}>处置</Button>
        : <span className="text-muted-foreground">-</span>,
    },
  ];

  const invoiceCols: Column<Invoice>[] = [
    {
      header: "发票号",
      cell: (i) => (
        <button type="button" className="txt-strong tabular-nums underline-offset-4 hover:underline" onClick={() => setInvDetail(i)}>
          {i.invoiceNo}
        </button>
      ),
    },
    { header: "抬头", cell: (i) => i.payeeName },
    { header: "金额", className: "text-right", cell: (i) => <span className="tabular-nums">{money(i.amount, i.currency)}</span> },
    // 来源单号：这张票的钱是哪张结算单来的（开具时校验两者金额必须一致）
    { header: "来源结算单", cell: (i) => <span className="text-muted-foreground tabular-nums">{i.sourceNo}</span> },
    { header: "发票代码 / 号码", cell: (i) => <span className="text-muted-foreground tabular-nums">{i.invoiceCode ? `${i.invoiceCode} / ${i.invoiceNumber}` : "未开具"}</span> },
    { header: "VAT TRN", cell: (i) => <span className="text-muted-foreground tabular-nums">{i.vatTrn}</span> },
    { header: "状态", cell: (i) => <StatusBadge map={INV_STATUS} value={i.status} /> },
    // 开具/作废留痕合成一列：分四列会把表撑到横向滚动，而它们本来就是一起看的
    {
      header: "开具 / 作废留痕",
      cell: (i) => i.status === "DRAFT" ? <span className="text-muted-foreground">未开具</span> : (
        <div className="max-w-[18rem]">
          <div className="text-xs text-muted-foreground">开具 {i.issuedBy} · {fmtTime(i.issuedAt!)}</div>
          {i.status === "VOID" && (
            <div className="text-xs text-[var(--destructive)]">作废 {i.voidedBy} · {fmtTime(i.voidedAt!)}：{i.voidReason}</div>
          )}
        </div>
      ),
    },
    {
      header: t("common.actions"),
      cell: (i) => (
        <div className="flex items-center gap-2">
          {/* 草稿：可改可开。开具后抬头/金额锁定——编辑按钮禁用并写明原因，不静默消失 */}
          {canEditInvoiceFields(i.status) ? (
            <>
              {canEditInvoice && <Button size="sm" variant="outline" onClick={() => setInvoiceForm(i)}>{t("common.edit")}</Button>}
              {canEditInvoice && <Button size="sm" onClick={() => askIssueInvoice(i)} disabled={issueInvoice.isPending}>开具</Button>}
            </>
          ) : i.status === "ISSUED" ? (
            <>
              <Button size="sm" variant="outline" disabled title="已开具：抬头与金额已进税务口径，不可再改；如需更正请作废后重开">{t("common.edit")}</Button>
              {canVoidInvoice && (
                <Button size="sm" variant="destructive" onClick={() => { setInvVoid(i); setInvVoidReason(""); }} disabled={voidInvoice.isPending}>作废</Button>
              )}
            </>
          ) : (
            <span className="text-muted-foreground">已作废</span>
          )}
        </div>
      ),
    },
  ];

  const payoutColumns: Column<PayoutAccount>[] = [
    { header: "受益方", cell: (a) => (
      <div>
        <div>{a.payeeNo}</div>
        <div className="txt-caption text-muted-foreground">{PAYEE_TYPE_LABEL[a.payeeType]}</div>
      </div>
    ) },
    { header: "户名", cell: (a) => a.accountName },
    { header: "开户行 / 账号", cell: (a) => (
      <div className="txt-caption">
        <div>{a.bankCode}</div>
        {/* 只显示掩码。同号段的掩码可能相同 —— 不能拿它做任何等值判断 */}
        <div className="font-mono text-muted-foreground">{a.accountMasked}</div>
      </div>
    ) },
    { header: "币种", cell: (a) => <span className="tabular-nums">{a.currency}</span> },
    { header: "状态", cell: (a) => (
      <div className="flex items-center gap-1.5">
        <StatusBadge map={PA_STATUS} value={a.status} />
        {a.isDefault && <Badge tone="info">默认</Badge>}
      </div>
    ) },
    { header: "操作", cell: (a) => {
      if (!canEditPayout) return <span className="text-muted-foreground">-</span>;
      if (a.status === "DISABLED") return <span className="txt-caption text-muted-foreground">已停用</span>;
      return (
        <div className="flex gap-2">
          {!a.isDefault && (
            <Button size="sm" variant="outline" disabled={setPayoutDefault.isPending}
              onClick={() => setPayoutDefault.mutate(a)}>设为默认</Button>
          )}
          <Button size="sm" variant="outline"
            onClick={() => setPayoutForm({ ...a, accountMasked: "" })}>编辑</Button>
          <Button size="sm" variant="outline" disabled={disablePayout.isPending}
            onClick={() => disablePayout.mutate(a.accountNo)}>停用</Button>
        </div>
      );
    } },
  ];

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />
      {tab === "rules" && (
        <>
          {/* 双向视图：切的是同一张表的 dimension 参数（同分润统计的维度切换器），不是两个 tab 两套规则 */}
          <Tabs tabs={RULE_VIEWS} value={ruleDim} onChange={(k) => { setRuleDim(k as "VENUE" | "AGENT"); paging.reset(); }} />
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); paging.reset(); }}
            searchPlaceholder={`搜索${RULE_VIEW_LABEL[ruleDim]}名称/规则号`}
            // 新增默认落在当前视角：在「按代理商看」下点新增却建出一条场地方规则，会当场从列表里消失
            onAdd={canEditRule ? () => setRuleForm({ dimension: ruleDim, mode: "CHANNEL_SPLIT", rate: ruleDim === "AGENT" ? 0.3 : 0.2, priority: 1 }) : undefined}
            addLabel={`新增${RULE_VIEW_LABEL[ruleDim]}分润规则`}
            onExport={() => exportCsv<ShareRule>(`分润规则-${RULE_VIEW_LABEL[ruleDim]}`, [
              { header: "规则号", value: (r) => r.ruleNo },
              { header: "维度", value: (r) => (r.dimension === "VENUE" ? "场地方" : "代理商") },
              { header: "分成方", value: (r) => r.payeeName },
              { header: "模式", value: (r) => (r.mode === "CHANNEL_SPLIT" ? "渠道分账" : "平台记账") },
              { header: "比例", value: (r) => r.rate },
              { header: "优先级", value: (r) => r.priority },
            ], (q.data?.list ?? []) as ShareRule[])}
          />
        </>
      )}
      {tab === "ledger" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
          searchPlaceholder="搜索账户/订单/凭证"
          onExport={() => exportCsv<LedgerEntry>("账务分录", [
            { header: "分录号", value: (l) => l.entryNo },
            { header: "凭证", value: (l) => l.voucherNo },
            { header: "订单", value: (l) => l.orderNo },
            { header: "账户", value: (l) => l.account },
            { header: "方向", value: (l) => (l.direction === "DEBIT" ? "借" : "贷") },
            { header: "金额", value: (l) => l.amount },
            { header: "币种", value: (l) => l.currency },
            { header: "摘要", value: (l) => l.summary },
            { header: "时间", value: (l) => l.createdAt },
          ], (q.data?.list ?? []) as LedgerEntry[])}
        >
          {canPostVoucher && (
            <Button size="sm" onClick={() => setEntryDraft({
              summary: "", orderNo: "",
              rows: [{ account: "", direction: "DEBIT", amount: "" }, { account: "", direction: "CREDIT", amount: "" }],
            })}>手工记账</Button>
          )}
          <FilterSelect
            value={ledgerPeriod}
            onChange={(v) => { setLedgerPeriod(v as ReportPeriod); paging.reset(); }}
            options={REPORT_PERIODS.map((x) => ({ value: x.value, label: x.label }))}
            aria-label="按记账期间筛选"
          />
        </Toolbar>
      )}
      {tab === "settlements" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
          searchPlaceholder="搜索结算单号/对象/周期/确认人"
          onAdd={canGenSettlement ? () => setGenForm({ payeeType: "VENUE", period: SUMMARY_PERIODS[0], payeeNos: "" }) : undefined}
          addLabel="生成结算单"
          onExport={() => exportCsv<Settlement>("结算单", [
            { header: "结算单号", value: (s) => s.settleNo },
            { header: "对象编号", value: (s) => s.payeeNo },
            { header: "对象", value: (s) => `${s.payeeName}（${PAYEE_TYPE_LABEL[s.payeeType]}）` },
            { header: "周期", value: (s) => s.period },
            { header: "金额", value: (s) => s.totalAmount },
            { header: "明细笔数", value: (s) => s.recordCount },
            { header: "币种", value: (s) => s.currency },
            { header: "状态", value: (s) => STL_STATUS[s.status].label },
            { header: "生成时间", value: (s) => s.createdAt },
            { header: "确认人", value: (s) => s.confirmedBy },
            { header: "确认时间", value: (s) => s.confirmedAt },
          ], (q.data?.list ?? []) as Settlement[])}
        >
          <FilterSelect value={stlStatus} onChange={(v) => { setStlStatus(v); paging.reset(); }} allLabel="全部状态" options={STL_STATUS} />
        </Toolbar>
      )}
      {tab === "payout-accounts" && (
        <>
          {!canEditPayout && (
            <ReadOnlyNotice
              what="收款账户的增改与停用"
              perm="finance:payout_account:update"
              note="与提现审核分开发码 —— 同一个人不应既能改钱去哪、又能放行这笔钱"
            />
          )}
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); paging.reset(); }}
            searchPlaceholder="搜户名 / 受益方编号"
            onAdd={() => setPayoutForm({ payeeType: "AGENT", currency: "AED" })}
            addLabel="新增收款账户"
            canAdd={canEditPayout}
          />
          <DataTable
            rows={(q.data?.list ?? []) as PayoutAccount[]}
            loading={q.isPending}
            rowKey={(a) => a.accountNo}
            empty="还没有收款账户 —— 没有它，这些受益方的提现审批放行不了（审批完不知道往哪打钱）"
            columns={payoutColumns}
          />
        </>
      )}

      {tab === "withdrawals" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
          searchPlaceholder="搜索提现号/对象/审批人"
          onExport={() => exportCsv<Withdrawal>("提现", [
            { header: "提现号", value: (w) => w.withdrawNo },
            { header: "对象", value: (w) => w.payeeName },
            { header: "金额", value: (w) => w.amount },
            // 导出必须和屏幕上一致：同样走口径函数，否则导出的表拿去对账又是另一个数
            { header: "手续费", value: (w) => withdrawFeeOf(w, feeRule) },
            { header: "实际到账", value: (w) => withdrawNetOf(w, feeRule) },
            { header: "币种", value: (w) => w.currency },
            // 状态导出走同一张映射表：屏幕上是「已打款」，导出不该是 PAID
            { header: "状态", value: (w) => WD_STATUS[w.status].label },
            { header: "申请时间", value: (w) => w.appliedAt },
            { header: "审批人", value: (w) => w.auditorName },
            { header: "审批时间", value: (w) => w.auditedAt },
            { header: "驳回原因", value: (w) => w.rejectReason },
          ], (q.data?.list ?? []) as Withdrawal[])}
        />
      )}
      {tab === "records" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
          searchPlaceholder="搜索明细号/订单/分成方编号或名称"
          onExport={() => exportCsv<ShareRecord>("分润明细", [
            { header: "明细号", value: (r) => r.recordNo },
            { header: "订单", value: (r) => r.orderNo },
            { header: "维度", value: (r) => PAYEE_TYPE_LABEL[r.dimension] },
            { header: "分成方编号", value: (r) => r.payeeNo },
            { header: "分成方", value: (r) => r.payeeName },
            { header: "金额", value: (r) => r.amount },
            { header: "币种", value: (r) => r.currency },
            { header: "比例", value: (r) => r.rate },
            { header: "周期", value: (r) => r.period },
            { header: "时间", value: (r) => r.createdAt },
          ], (q.data?.list ?? []) as ShareRecord[])}
        />
      )}
      {tab === "summary" && (
        <>
          {/* 维度切换器：切的是同一张表的 dimension 参数，不是两个 tab */}
          <Tabs tabs={SUMMARY_DIMS} value={sumDim} onChange={(k) => { setSumDim(k); paging.reset(); }} />
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); paging.reset(); }}
            searchPlaceholder="搜索分成方名称/编号"
            onExport={() => exportCsv<ShareSummary>(`分润统计-${sumDim === "VENUE" ? "场地方" : "代理商"}-${sumPeriod}`, [
              { header: "分成方", value: (s) => s.payeeName },
              { header: "编号", value: (s) => s.payeeNo },
              { header: "维度", value: (s) => (s.dimension === "VENUE" ? "场地方" : "代理商") },
              { header: "统计周期", value: (s) => s.period },
              { header: "订单数", value: (s) => s.orderCount },
              { header: "交易额", value: (s) => s.gmv },
              { header: "分润额", value: (s) => s.shareAmount },
              { header: "已结算", value: (s) => s.settledAmount },
              { header: "待结算", value: (s) => s.pendingAmount },
              { header: "币种", value: (s) => s.currency },
            ], (q.data?.list ?? []) as ShareSummary[])}
          >
            <FilterSelect value={sumPeriod} onChange={(v) => { setSumPeriod(v); paging.reset(); }} options={PERIOD_OPTIONS} />
          </Toolbar>
        </>
      )}
      {tab === "recharges" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
          searchPlaceholder="搜索充值单号/用户/网关流水号"
          onExport={() => exportCsv<RechargeOrder>("充值订单", [
            { header: "充值单号", value: (r) => r.rechargeNo },
            { header: "用户号", value: (r) => r.userNo },
            { header: "昵称", value: (r) => r.nickname },
            { header: "套餐", value: (r) => r.packageNo ?? "自定义金额" },
            { header: "实付", value: (r) => r.payAmount },
            { header: "赠送", value: (r) => r.giftAmount },
            { header: "到账", value: (r) => r.creditAmount },
            { header: "币种", value: (r) => r.currency },
            { header: "支付渠道", value: (r) => r.channelCode },
            { header: "状态", value: (r) => RECHARGE_STATUS[r.status].label },
            { header: "下单时间", value: (r) => r.createdAt },
            { header: "支付时间", value: (r) => r.paidAt },
            { header: "网关流水号", value: (r) => r.psgTxnNo },
          ], (q.data?.list ?? []) as RechargeOrder[])}
        >
          <FilterSelect value={rcStatus} onChange={(v) => { setRcStatus(v); paging.reset(); }} allLabel="全部状态" options={RECHARGE_STATUS} />
          {/* 日期范围按下单时间：待支付/失败单没有支付时间，用支付时间会把它们全筛掉 */}
          <DateInput className="w-40" aria-label="下单时间起" value={rcFrom} onChange={(e) => { setRcFrom(e.target.value); paging.reset(); }} />
          <span className="text-muted-foreground">~</span>
          <DateInput className="w-40" aria-label="下单时间止" value={rcTo} onChange={(e) => { setRcTo(e.target.value); paging.reset(); }} />
        </Toolbar>
      )}
      {tab === "reconcile" && (
        <>
          {/* 汇总条：未结差错笔数/金额来自服务端同一份对账数据，处理完一笔当场下降（不是前端按当前页算的） */}
          <div className="mb-4 grid grid-cols-2 gap-4 lg:grid-cols-4">
            <StatCard
              label="未结差错笔数"
              value={reconStatsQ.data ? `${reconStatsQ.data.diffCount} / ${reconStatsQ.data.batchCount}` : "-"}
              sub={reconStatsQ.data ? `待处理 ${reconStatsQ.data.openCount} · 处理中 ${reconStatsQ.data.handlingCount}` : undefined}
              tone={reconStatsQ.data && reconStatsQ.data.diffCount > 0 ? "down" : "up"}
            />
            <StatCard
              label="未结差错金额"
              value={reconStatsQ.data ? money(reconStatsQ.data.diffAmount, reconStatsQ.data.currency) : "-"}
              sub="正负差错取绝对值——两边都是要找平的钱"
              tone={reconStatsQ.data && reconStatsQ.data.diffAmount > 0 ? "down" : "up"}
            />
            <StatCard
              label="已结案差错"
              value={reconStatsQ.data ? `${reconStatsQ.data.closedCount} 笔` : "-"}
              sub={reconStatsQ.data ? `累计 ${money(reconStatsQ.data.closedAmount, reconStatsQ.data.currency)}` : undefined}
            />
            <StatCard
              label="已平批次"
              value={reconStatsQ.data ? `${reconStatsQ.data.matchedCount} 期` : "-"}
              sub="跑批即平，无需人工处置"
            />
          </div>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); paging.reset(); }}
            searchPlaceholder="搜索批次号/周期/处理人/结论"
            onExport={() => exportCsv<Reconcile>("对账", [
              { header: "批次号", value: (r) => r.batchNo },
              { header: "周期", value: (r) => r.period },
              { header: "nearpay 汇总", value: (r) => r.nearpayTotal },
              { header: "账务汇总", value: (r) => r.ledgerTotal },
              { header: "差额", value: (r) => r.diff },
              { header: "差错方向", value: (r) => diffSideLabel(r.diff) },
              { header: "币种", value: (r) => r.currency },
              { header: "跑批结果", value: (r) => RECON_STATUS[r.status].label },
              { header: "处置进度", value: (r) => (r.handleStatus ? RECON_HANDLE_STATUS[r.handleStatus].label : "无需处理") },
              { header: "定责", value: (r) => (r.handleResult ? RECON_RESULT_LABEL[r.handleResult] : "") },
              { header: "处理结论", value: (r) => r.handleNote },
              { header: "处理人", value: (r) => r.handledBy },
              { header: "处理时间", value: (r) => r.handledAt },
              { header: "跑批时间", value: (r) => r.createdAt },
            ], (q.data?.list ?? []) as Reconcile[])}
          >
            <FilterSelect value={reconStatusFilter} onChange={(v) => { setReconStatusFilter(v); paging.reset(); }} allLabel="全部处置进度" options={RECON_HANDLE_STATUS} />
          </Toolbar>
        </>
      )}
      {tab === "invoices" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
          searchPlaceholder="搜索发票号/抬头/税号/来源单号/票号"
          onAdd={canEditInvoice ? () => setInvoiceForm({ currency: "AED", amount: 0 }) : undefined}
          addLabel="登记发票草稿"
          onExport={() => exportCsv<Invoice>("发票", [
            { header: "发票号", value: (i) => i.invoiceNo },
            { header: "抬头", value: (i) => i.payeeName },
            { header: "金额", value: (i) => i.amount },
            { header: "币种", value: (i) => i.currency },
            { header: "来源单据", value: (i) => i.sourceNo },
            { header: "发票代码", value: (i) => i.invoiceCode },
            { header: "发票号码", value: (i) => i.invoiceNumber },
            { header: "VAT TRN", value: (i) => i.vatTrn },
            { header: "状态", value: (i) => INV_STATUS[i.status].label },
            { header: "开具时间", value: (i) => i.issuedAt },
            { header: "开具人", value: (i) => i.issuedBy },
            { header: "作废时间", value: (i) => i.voidedAt },
            { header: "作废人", value: (i) => i.voidedBy },
            { header: "作废原因", value: (i) => i.voidReason },
          ], (q.data?.list ?? []) as Invoice[])}
        >
          <FilterSelect value={invStatusFilter} onChange={(v) => { setInvStatusFilter(v); paging.reset(); }} allLabel="全部状态" options={INV_STATUS} />
        </Toolbar>
      )}
      {tab === "rules" && (
        <DataTable
          rowKey={(r: ShareRule) => r.ruleNo}
          columns={ruleCols}
          rows={q.data?.list as ShareRule[]}
          loading={q.isLoading} error={q.error} onRetry={q.refetch}
          empty={`当前视角（${RULE_VIEW_LABEL[ruleDim]}）暂无分润规则——换个视角看看，或点右上新增为该${RULE_VIEW_LABEL[ruleDim]}配置分成比例，否则订单收入全归平台`}
        />
      )}
      {tab === "ledger" && <DataTable rowKey={(l: LedgerEntry) => l.entryNo} columns={ledgerCols} rows={q.data?.list as LedgerEntry[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty={`${periodLabel(ledgerPeriod)}内没有账务分录——订单结算与分账完成后自动记账，可换更长的期间或放宽搜索条件`} />}
      {tab === "settlements" && !canGenSettlement && !canConfirmSettlement && (
        <ReadOnlyNotice what="结算单生成/确认" perm={["finance:settlement:generate", ":confirm"]} />
      )}
      {tab === "settlements" && <DataTable rowKey={(s: Settlement) => s.settleNo} columns={stlCols} rows={q.data?.list as Settlement[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="暂无结算单——点右上「生成结算单」按周期出账（金额取该周期分润明细汇总），或放宽筛选条件" />}
      {tab === "withdrawals" && !canAuditWithdrawal && <ReadOnlyNotice what="提现审批" perm="finance:withdrawal:audit" />}
      {/* 手续费口径必须写明出处：审批人看到的数从哪来、改哪里能改，否则「唯一来源」只是一句话 */}
      {/*
        * 「你还不能收款」。放在提现页最上面 —— 代理商是在这一页发现自己提不了现的，
        * 而不是在审批被拒之后。ai-shop 的教训：结算侧的兜底「保证了不出错，没保证有人知道」。
        */}
      {/* 代理端自助提现入口。运营端不显示 —— 运营没有也不该有创建入口（api/README §六·A）：
          替别人发起提现，等于绕开「本人申请」这道最基本的授权。
          没有可用收款账户时**按钮直接禁用**，而不是让人填完金额提交、再在审批环节被退回。 */}
      {tab === "withdrawals" && realm === "AGENT" && canApplyWithdrawal && (
        <div className="mb-3 flex justify-end">
          <Button disabled={cannotGetPaidYet} onClick={() => { setWdApplyOpen(true); setWdApplyAmount(""); }}>
            申请提现
          </Button>
        </div>
      )}

      {tab === "withdrawals" && cannotGetPaidYet && (
        <div className="mb-3 rounded-card border border-warning/40 bg-warning/5 p-3">
          <div className="txt-body font-medium">你还不能收款</div>
          <div className="mt-1 txt-body text-muted-foreground">
            主体已启用、分润也在正常产生，但还没有设置默认收款账户 ——
            提现申请提交后会在审批环节被退回。请联系运营补录收款账户后再申请。
          </div>
        </div>
      )}

      {tab === "withdrawals" && (
        <Notice>
          {feeRule ? (
            <>
              手续费口径来自「
              <Link href="/system?tab=rules" className="underline underline-offset-4">系统设置 · 业务规则</Link>
              」：费率 <span className="tabular-nums">{(feeRule.feeRate * 100).toFixed(2)}%</span>、
              封顶 <span className="tabular-nums">{money(feeRule.feeCap, bizRulesQ.data!.currency)}</span>、
              最低提现额 <span className="tabular-nums">{money(feeRule.minAmount, bizRulesQ.data!.currency)}</span>。
              未审批的单子按现行费率实时计算；已审批的按当时落库值展示，改费率不会改写历史放款额。
            </>
          ) : (
            // 取不到规则不装作有：表上显示的是落库手续费，别让人以为已经按最新费率算过
            <>⚠️ 未取到「业务规则」中的提现手续费配置（可能缺 <code>system:biz_rule:read</code> 权限），
              下表手续费为申请时的落库值，未按现行费率重算。</>
          )}
        </Notice>
      )}
      {tab === "withdrawals" && <DataTable rowKey={(w: Withdrawal) => w.withdrawNo} columns={wdCols} rows={q.data?.list as Withdrawal[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="暂无提现申请——场地方/代理商发起提现后在此审批，通过才会进入打款队列" />}
      {tab === "records" && <DataTable rowKey={(r: ShareRecord) => r.recordNo} columns={recordCols} rows={q.data?.list as ShareRecord[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="暂无分润明细——订单结算时按「分润规则」逐笔生成，先确认规则已配置" />}
      {tab === "summary" && (
        <DataTable
          rowKey={(s: ShareSummary) => `${s.dimension}-${s.payeeNo}-${s.period}`}
          columns={summaryCols}
          rows={q.data?.list as ShareSummary[]}
          loading={q.isLoading} error={q.error} onRetry={q.refetch}
          empty={`${sumPeriod} 该维度暂无分润统计 —— 换个周期，或确认该周期已有已结算订单`}
          sortKey={sumSortKey}
          sortDir={sumSortDir}
          onSortChange={(k, d) => { setSumSortKey(k); setSumSortDir(d); paging.reset(); }}
        />
      )}
      {tab === "recharges" && (
        <DataTable
          rowKey={(r: RechargeOrder) => r.rechargeNo}
          columns={rechargeCols}
          rows={q.data?.list as RechargeOrder[]}
          loading={q.isLoading} error={q.error} onRetry={q.refetch}
          empty="暂无充值订单 —— 该筛选条件下没有记录，或用户尚未使用钱包充值"
        />
      )}
      {tab === "reconcile" && !canHandleRecon && (
        <ReadOnlyNotice what="对账差错处理" perm="finance:recon:handle" />
      )}
      {tab === "reconcile" && <DataTable rowKey={(r: Reconcile) => r.batchNo} columns={reconcileCols} rows={q.data?.list as Reconcile[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="暂无对账批次——每日与 nearpay 流水自动跑批比对，本周期尚未生成批次；也可能是「处置进度」筛窄了" />}
      {tab === "invoices" && !canEditInvoice && !canVoidInvoice && (
        <ReadOnlyNotice what="发票开具/作废" perm={["finance:invoice:issue", ":void"]} />
      )}
      {tab === "invoices" && <DataTable rowKey={(i: Invoice) => i.invoiceNo} columns={invoiceCols} rows={q.data?.list as Invoice[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="暂无发票——商户提出开票需求后点右上「登记发票草稿」挂到对应结算单，再开具" />}
      {/* 9 个 tab 共用同一个查询与同一条分页条，所以这里是 DataTable + Pagination
          而不是 PagedTable：共用查询是 union 类型，逐 tab 断言反而更容易出错。
          代价是错误态要自己接——上面每个列表都接了，棘轮 lib/table-wiring.test.ts 守住不回退。 */}
      {q.data && <Pagination page={paging.page} size={paging.size} total={q.data.total} onPage={paging.setPage} onSize={paging.setSize} />}

      {/* 提现审批抽屉：通过 → 转打款中；驳回必填原因；审批人取当前登录账号 */}
      <Drawer
        open={!!wdAudit}
        onOpenChange={(o) => !o && setWdAudit(null)}
        title={`提现审批 ${wdAudit?.withdrawNo ?? ""}`}
        desc="资金操作：通过后进入打款队列，审批人/时间将留痕不可改"
        footer={
          wdAudit && canAuditWithdrawal && (
            <Button
              disabled={audit.isPending || (wdApprove === "0" && !wdReject.trim())}
              variant={wdApprove === "0" ? "destructive" : "default"}
              onClick={() => audit.mutate({ no: wdAudit.withdrawNo, approve: wdApprove === "1", rejectReason: wdReject })}
            >提交审批</Button>
          )
        }
      >
        {wdAudit && (
          <>
            <Field label="提现对象">{wdAudit.payeeName}</Field>
            <Field label="申请金额">
              {money(wdAudit.amount, wdAudit.currency)}
              {/* 低于业务规则的最低提现额是该被看见的：批过去就是违反自己定的规则 */}
              {feeRule && wdAudit.amount < feeRule.minAmount && (
                <Badge tone="danger" className="ml-2">低于最低提现额 {money(feeRule.minAmount, wdAudit.currency)}</Badge>
              )}
            </Field>
            <Field label="手续费">
              {money(withdrawFeeOf(wdAudit, feeRule), wdAudit.currency)}
              {feeRule && (
                <span className="ml-2 text-muted-foreground">
                  费率 {(feeRule.feeRate * 100).toFixed(2)}% · 封顶 {money(feeRule.feeCap, wdAudit.currency)}（业务规则）
                </span>
              )}
            </Field>
            <Field label="实际到账">{money(withdrawNetOf(wdAudit, feeRule), wdAudit.currency)}</Field>
            <Field label="申请时间">{fmtTime(wdAudit.appliedAt)}</Field>
            <Field label="审批人">{username || "admin"}</Field>
            <Field label="审批结果">
              <Select className="w-full" value={wdApprove} onChange={(e) => setWdApprove(e.target.value)}>
                <option value="1">通过（转打款）</option>
                <option value="0">驳回</option>
              </Select>
            </Field>
            {wdApprove === "0" && (
              <Field label="驳回原因（必填）">
                <Input className="w-full" value={wdReject} placeholder="如：银行账户与合同主体不一致" onChange={(e) => setWdReject(e.target.value)} />
              </Field>
            )}
          </>
        )}
      </Drawer>

      {/* 申请提现（代理端自助）。手续费与实际到账当场算给本人看 ——
          此前审批页已经这么做了，申请这一侧却没有，于是申请人直到收到钱才知道扣了多少。 */}
      <Drawer
        open={wdApplyOpen}
        onOpenChange={(o) => !o && setWdApplyOpen(false)}
        title="申请提现"
        desc="提交后进入运营审核；手续费按当前业务规则计算"
        footer={
          <Button
            disabled={applyWithdraw.isPending || !!applyAmountErr()}
            onClick={() => applyWithdraw.mutate(Number(wdApplyAmount))}
          >提交申请</Button>
        }
      >
        <Field label="收款主体">{currentOperatorName}</Field>
        <Field label="提现金额（AED）">
          <Input
            className="w-full" inputMode="decimal" value={wdApplyAmount}
            placeholder={feeRule ? `不低于 ${bizRulesQ.data?.withdraw?.minAmount ?? 0}` : "请输入金额"}
            onChange={(e) => setWdApplyAmount(e.target.value)}
          />
          {/* 校验文案即时显示：等到点提交才报错的话，人已经填完一轮了 */}
          {wdApplyAmount && applyAmountErr() && (
            <div className="mt-1 txt-caption text-destructive">{applyAmountErr()}</div>
          )}
        </Field>
        {feeRule && Number(wdApplyAmount) > 0 && !applyAmountErr() && (
          <>
            <Field label="手续费">
              {money(computeWithdrawFee(Number(wdApplyAmount), feeRule), "AED")}
              <span className="ml-2 text-muted-foreground">
                费率 {(feeRule.feeRate * 100).toFixed(2)}% · 封顶 {money(feeRule.feeCap, "AED")}
              </span>
            </Field>
            <Field label="实际到账">
              {money(Number(wdApplyAmount) - computeWithdrawFee(Number(wdApplyAmount), feeRule), "AED")}
            </Field>
          </>
        )}
      </Drawer>

      {/* 打款回执（⑮）：PAYING → PAID / FAILED。
          此前状态机有 PAY/FAIL 两条迁移却没有任何入口调用 —— 审批完的单子永远停在「出款在途」。
          与审批分成两个抽屉，因为中间隔着一次真实的资金动作：可能失败、可能延迟几天。 */}
      <Drawer
        open={!!wdPay}
        onOpenChange={(o) => !o && setWdPay(null)}
        title={`登记打款 ${wdPay?.withdrawNo ?? ""}`}
        desc="资金操作：登记后进入终态，不可撤销；登记人将留痕"
        footer={
          wdPay && canPayWithdrawal_ && (
            <Button
              disabled={payReceipt.isPending || !!payReceiptError(payDraft())}
              variant={payOk === "0" ? "destructive" : "default"}
              onClick={() => payReceipt.mutate({ no: wdPay.withdrawNo, body: payDraft() })}
            >提交回执</Button>
          )
        }
      >
        {wdPay && (
          <>
            <Field label="提现对象">{wdPay.payeeName}</Field>
            <Field label="应付金额">{money(withdrawNetOf(wdPay, feeRule), wdPay.currency)}</Field>
            <Field label="审批人">{wdPay.auditorName ?? "-"}</Field>
            <Field label="登记人">{username || "admin"}</Field>
            <Field label="打款结果">
              <Select className="w-full" value={payOk} onChange={(e) => setPayOk(e.target.value)}>
                <option value="1">已到账</option>
                <option value="0">打款失败</option>
              </Select>
            </Field>
            <Field label="打款渠道">
              <Select className="w-full" value={payChannel} onChange={(e) => setPayChannel(e.target.value as PayChannel)}>
                {PAY_CHANNELS.map((c) => (
                  <option key={c} value={c}>{c === "MANUAL" ? "人工转账" : "nearpay 代付"}</option>
                ))}
              </Select>
            </Field>
            {payOk === "1" ? (
              <Field label="渠道流水号（必填）">
                <Input className="w-full" value={payRef} placeholder="银行回单号 / nearpay 打款单号"
                       onChange={(e) => setPayRef(e.target.value)} />
                <div className="text-xs text-muted-foreground mt-1">
                  「已到账」要能在对账时被证实 —— 没有流水号，事后只剩一句人说的话。
                </div>
              </Field>
            ) : (
              <Field label="失败原因（必填）">
                <Input className="w-full" value={payFail} placeholder="如：收款账号已销户"
                       onChange={(e) => setPayFail(e.target.value)} />
                <div className="text-xs text-muted-foreground mt-1">
                  失败原因与「审批驳回原因」分开记录：前者钱出去又退回来，后者钱从没打算出去。
                </div>
              </Field>
            )}
          </>
        )}
      </Drawer>

      {/* 生成结算单：类型 + 周期 + 多选对象；金额不在这里填——由服务端按分润明细汇总 */}
      {/* 收款账户表单。账号填明文、只落掩码 —— 编辑时这一栏是空的（不是丢了），留空即不改 */}
      <FormDrawer
        open={!!payoutForm}
        onOpenChange={(o) => !o && setPayoutForm(null)}
        titleNew="新增收款账户"
        titleEdit={`编辑收款账户 ${payoutForm?.accountNo ?? ""}`}
        isEdit={!!payoutForm?.accountNo}
        fields={PAYOUT_FIELDS}
        value={payoutForm ?? {}}
        onChange={(v) => setPayoutForm(v)}
        onSubmit={() => payoutForm && savePayout.mutate(payoutForm)}
        submitting={savePayout.isPending}
      />

      <FormDrawer
        open={!!genForm}
        onOpenChange={(o) => !o && setGenForm(null)}
        titleNew="生成结算单"
        titleEdit="生成结算单"
        isEdit={false}
        width="w-[520px]"
        fields={GEN_FIELDS}
        value={(genForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setGenForm(v as { payeeType: string; period: string; payeeNos: string })}
        onSubmit={submitGen}
        submitting={genSettlements.isPending}
      />

      {/* 结算单详情：金额是怎么来的——逐笔列出构成它的分润明细 */}
      <Drawer
        open={!!stlDetail}
        onOpenChange={(o) => !o && setStlDetail(null)}
        title={`结算单 ${stlDetail?.settleNo ?? ""}`}
        desc="金额 = 下方分润明细之和；确认后进入应付，金额锁定"
        width="w-[760px]"
        footer={
          stlDetail?.status === "DRAFT" && canConfirmSettlement && (
            <Button disabled={confirmSettlement.isPending} onClick={() => askConfirmSettlement(stlDetail)}>确认结算</Button>
          )
        }
      >
        {stlDetail && (
          <>
            <Field label="结算对象">{stlDetail.payeeName}（{PAYEE_TYPE_LABEL[stlDetail.payeeType]} {stlDetail.payeeNo}）</Field>
            <Field label="结算周期">{stlDetail.period}</Field>
            <Field label="结算金额">{money(stlDetail.totalAmount, stlDetail.currency)}（{stlDetail.recordCount} 笔明细）</Field>
            <Field label="状态"><StatusBadge map={STL_STATUS} value={stlDetail.status} /></Field>
            <Field label="生成时间">{fmtTime(stlDetail.createdAt)}</Field>
            <Field label="确认人 / 确认时间">
              {stlDetail.confirmedBy ? `${stlDetail.confirmedBy} · ${fmtTime(stlDetail.confirmedAt!)}` : "未确认"}
            </Field>
            <div className="mb-2 mt-4 text-xs text-muted-foreground">构成明细（{stlDetail.period}）</div>
            <DataTable
              rowKey={(r: ShareRecord) => r.recordNo}
              columns={recordCols.filter((c) => c.header !== "维度" && c.header !== "分成方")}
              rows={stlRecordsQ.data?.list}
              loading={stlRecordsQ.isLoading}
              error={stlRecordsQ.error}
              onRetry={stlRecordsQ.refetch}
              empty="该周期没有分润明细——理论上不该出现（无明细不允许出单），若看到请核对分润规则"
            />
          </>
        )}
      </Drawer>

      <FormDrawer
        open={!!ruleForm}
        onOpenChange={(o) => !o && setRuleForm(null)}
        titleNew="新增分润规则"
        titleEdit={`编辑分润规则 ${ruleForm?.ruleNo ?? ""}`}
        isEdit={!!ruleForm?.ruleNo}
        fields={RULE_FIELDS}
        value={(ruleForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setRuleForm(v as Partial<ShareRule>)}
        onSubmit={() => ruleForm && saveRule.mutate(ruleForm)}
        submitting={saveRule.isPending}
      />
      {/* 手工记账。**实时显示借贷合计与差额** —— 校验虽在服务端强制，但让人提交后才知道
          不平是糟糕的交互：凭证有好几行，事后回头找哪行错很费劲。 */}
      <Drawer
        open={!!entryDraft}
        onOpenChange={(o) => !o && setEntryDraft(null)}
        title="手工记账"
        width="w-[760px]"
        footer={entryDraft && (() => {
          const num = (v: string) => Number(v) || 0;
          const debit = Math.round(entryDraft.rows.filter((r) => r.direction === "DEBIT").reduce((n, r) => n + num(r.amount), 0) * 100) / 100;
          const credit = Math.round(entryDraft.rows.filter((r) => r.direction === "CREDIT").reduce((n, r) => n + num(r.amount), 0) * 100) / 100;
          const balanced = debit > 0 && Math.abs(debit - credit) < 0.01;
          return (
            <div className="flex items-center justify-between gap-3">
              <span className="txt-body">
                借 <span className="tabular-nums">{money(debit, "AED")}</span>
                {" / "}贷 <span className="tabular-nums">{money(credit, "AED")}</span>{" "}
                {balanced ? <Badge tone="success">平衡</Badge>
                  : <Badge tone="danger">差 {money(Math.abs(debit - credit), "AED")}</Badge>}
              </span>
              <Button
                disabled={!balanced || !entryDraft.summary.trim() || postVoucher.isPending}
                onClick={() => postVoucher.mutate({
                  summary: entryDraft.summary,
                  orderNo: entryDraft.orderNo || null,
                  entries: entryDraft.rows.map((r) => ({ account: r.account, direction: r.direction, amount: Number(r.amount) })),
                })}
              >{postVoucher.isPending ? "记账中…" : "确认记账"}</Button>
            </div>
          );
        })()}
      >
        {entryDraft && (
          <>
            <Notice className="mb-4">
              凭证一经记账**不可修改**——需要更正请再记一张反向凭证。直接改历史分录会让账实相符无从追溯。
            </Notice>
            <Field label="凭证摘要（必填）">
              <Input
                value={entryDraft.summary}
                placeholder="如：补记 7 月场地方分润差额"
                onChange={(e) => setEntryDraft({ ...entryDraft, summary: e.target.value })}
              />
            </Field>
            <Field label="关联订单号（可选）">
              <Input
                value={entryDraft.orderNo}
                placeholder="ORD500001"
                onChange={(e) => setEntryDraft({ ...entryDraft, orderNo: e.target.value })}
              />
            </Field>
            <div className="mt-4 mb-2 flex items-center justify-between">
              <span className="txt-strong">分录（至少两条，借贷两侧都要有）</span>
              <Button size="sm" variant="outline"
                onClick={() => setEntryDraft({ ...entryDraft, rows: [...entryDraft.rows, { account: "", direction: "DEBIT", amount: "" }] })}
              >添加一行</Button>
            </div>
            <div className="space-y-2">
              {entryDraft.rows.map((r, i) => (
                <div key={i} className="flex items-center gap-2">
                  <Input
                    className="flex-1" placeholder="账户，如 现金-nearpay"
                    value={r.account}
                    onChange={(e) => { const rows=[...entryDraft.rows]; rows[i]={...r,account:e.target.value}; setEntryDraft({...entryDraft,rows}); }}
                  />
                  <Select
                    className="w-24" value={r.direction}
                    onChange={(e) => { const rows=[...entryDraft.rows]; rows[i]={...r,direction:e.target.value as "DEBIT"|"CREDIT"}; setEntryDraft({...entryDraft,rows}); }}
                  >
                    <option value="DEBIT">借</option>
                    <option value="CREDIT">贷</option>
                  </Select>
                  <Input
                    className="w-32 text-right" type="number" min="0" step="0.01" placeholder="金额"
                    value={r.amount}
                    onChange={(e) => { const rows=[...entryDraft.rows]; rows[i]={...r,amount:e.target.value}; setEntryDraft({...entryDraft,rows}); }}
                  />
                  <Button
                    size="sm" variant="outline"
                    disabled={entryDraft.rows.length <= 2}
                    title={entryDraft.rows.length <= 2 ? "至少保留两条分录" : undefined}
                    onClick={() => setEntryDraft({ ...entryDraft, rows: entryDraft.rows.filter((_, j) => j !== i) })}
                  >删除</Button>
                </div>
              ))}
            </div>
          </>
        )}
      </Drawer>

      {/* 凭证下钻。核心是**借贷平衡判定** —— 不平的凭证是记账错误，
          必须一眼看出来而不是靠人心算；判定由服务端算，前端只渲染结论。 */}
      <Drawer
        open={!!voucherNo}
        onOpenChange={(o) => !o && setVoucherNo(null)}
        title={`凭证 ${voucherNo ?? ""}`}
        width="w-[720px]"
      >
        {voucherQ.data && (
          <>
            <div className="mb-4 grid grid-cols-3 gap-3">
              <Field label="借方合计"><span className="tabular-nums">{money(voucherQ.data.debit, "AED")}</span></Field>
              <Field label="贷方合计"><span className="tabular-nums">{money(voucherQ.data.credit, "AED")}</span></Field>
              <Field label="平衡">
                {voucherQ.data.balanced
                  ? <Badge tone="success">借贷平衡</Badge>
                  : <Badge tone="danger">不平（差 {money(Math.abs(voucherQ.data.debit - voucherQ.data.credit), "AED")}）</Badge>}
              </Field>
            </div>
            {!voucherQ.data.balanced && (
              <Notice className="mb-4">
                该凭证借贷不等额，属记账错误：请核对是否有分录漏记或金额录错。会计上一张凭证必须借贷相等。
              </Notice>
            )}
            <DataTable
              rowKey={(l: LedgerEntry) => l.entryNo}
              columns={ledgerCols.filter((c) => c.header !== "凭证")}
              rows={voucherQ.data.entries}
              empty="该凭证下没有分录——凭证号可能有误"
            />
          </>
        )}
      </Drawer>

      <FormDrawer
        open={!!invoiceForm}
        onOpenChange={(o) => !o && setInvoiceForm(null)}
        titleNew="登记发票草稿"
        titleEdit={`编辑发票 ${invoiceForm?.invoiceNo ?? ""}`}
        isEdit={!!invoiceForm?.invoiceNo}
        width="w-[520px]"
        fields={INVOICE_FIELDS}
        value={(invoiceForm ?? {}) as Record<string, unknown>}
        onChange={(v) => onInvoiceFormChange(v as Partial<Invoice>)}
        onSubmit={() => invoiceForm && saveInvoice.mutate(invoiceForm)}
        submitting={saveInvoice.isPending}
      />

      {/* 差错明细下钻：批次差额是怎么来的——逐笔列出，每笔可单独处置（同结算单详情的「构成明细」范式） */}
      <Drawer
        open={!!reconDiffsOf}
        onOpenChange={(o) => !o && setReconDiffsOf(null)}
        title={`差错明细 ${reconDiffsOf?.batchNo ?? ""}`}
        desc="逐笔差额之和 = 批次差额；可对单笔处置，全部平账后批次进度才迁移（半平不算平）"
        width="w-[860px]"
      >
        {reconDiffsOf && (
          <>
            <Field label="对账周期">{reconDiffsOf.period}</Field>
            <Field label="批次差额">
              <span className="font-medium tabular-nums text-[var(--destructive)]">{money(reconDiffsOf.diff, reconDiffsOf.currency)}</span>
              <span className="ml-2 text-muted-foreground">{diffSideLabel(reconDiffsOf.diff)}</span>
            </Field>
            <Field label="当前处置进度">
              {reconDiffsOf.handleStatus
                ? <StatusBadge map={RECON_HANDLE_STATUS} value={reconDiffsOf.handleStatus} />
                : <span className="text-muted-foreground">无需处理</span>}
            </Field>
            {/* 逐笔加总当场对给用户看：对不上说明明细与批次脱钩（数据问题），比默默显示更该让人看见 */}
            <Field label="逐笔加总">
              <span className="tabular-nums">
                {money((reconDiffsQ.data ?? []).reduce((s, d) => s + reconDiffDelta(d), 0), reconDiffsOf.currency)}
              </span>
              <span className="ml-2 text-muted-foreground">
                （{(reconDiffsQ.data ?? []).length} 笔，未处置 {(reconDiffsQ.data ?? []).filter((d) => !d.resolved).length} 笔）
              </span>
            </Field>
            <div className="mb-2 mt-4 text-xs text-muted-foreground">逐笔差错</div>
            <DataTable
              rowKey={(d: ReconDiff) => String(d.id)}
              columns={reconDiffCols}
              rows={reconDiffsQ.data}
              loading={reconDiffsQ.isLoading}
              error={reconDiffsQ.error}
              onRetry={reconDiffsQ.refetch}
              empty="该批次没有差错明细——理论上不该出现（有差额必有逐笔差错），若看到请核对跑批作业"
            />
          </>
        )}
      </Drawer>

      {/* 对账差错处理：动作按状态机过滤，结论必填——「审批类抽屉」范式（同提现审批） */}
      <Drawer
        open={!!reconHandle}
        onOpenChange={(o) => !o && setReconHandle(null)}
        title={reconDiffRow ? `处置差错明细 #${reconDiffRow.id}` : `处理对账差错 ${reconHandle?.batchNo ?? ""}`}
        desc={reconDiffRow
          ? "只处置这一笔：批次其余未处置差错不动，全部平账后批次进度才迁移"
          : "差错是真金白银对不上：处置需定责并留下结论，处理人与时间将留痕不可改"}
        width="w-[560px]"
        footer={
          reconHandle && canHandleRecon && (
            <Button
              disabled={handleRecon.isPending || !reconNote.trim()}
              variant={reconAction === "compensate" ? "destructive" : "default"}
              onClick={() => handleRecon.mutate({
                batchNo: reconHandle.batchNo, action: reconAction, note: reconNote, diffId: reconDiffRow?.id,
              })}
            >提交处理</Button>
          )
        }
      >
        {reconHandle && (
          <>
            <Field label="对账周期">{reconHandle.period}</Field>
            {/* 逐条处置时先把「处置的是哪一笔」摊开：光有 diffId 数字，操作的人无从核对 */}
            {reconDiffRow && (
              <>
                <Field label="支付单号">
                  <span className="tabular-nums">{reconDiffRow.payNo}</span>
                  <StatusBadge map={RECON_DIFF_TYPE} value={reconDiffRow.diffType} className="ml-2" />
                </Field>
                <Field label="本笔差额">
                  <span className="font-medium tabular-nums text-[var(--destructive)]">
                    {money(reconDiffDelta(reconDiffRow), reconHandle.currency)}
                  </span>
                  <span className="ml-2 text-muted-foreground">{parseReconDiffDetail(reconDiffRow.detail)?.note ?? ""}</span>
                </Field>
              </>
            )}
            <Field label="nearpay 汇总 / 账务汇总">
              {money(reconHandle.nearpayTotal, reconHandle.currency)} / {money(reconHandle.ledgerTotal, reconHandle.currency)}
            </Field>
            <Field label="差额">
              <span className="font-medium tabular-nums text-[var(--destructive)]">{money(reconHandle.diff, reconHandle.currency)}</span>
              <span className="ml-2 text-muted-foreground">{diffSideLabel(reconHandle.diff)}</span>
            </Field>
            <Field label="当前处置进度">
              <StatusBadge map={RECON_HANDLE_STATUS} value={reconHandle.handleStatus!} />
            </Field>
            {reconHandle.handleNote && <Field label="上一次结论">{reconHandle.handleNote}（{reconHandle.handledBy} · {fmtTime(reconHandle.handledAt!)}）</Field>}
            <Field label="处理人">{username || "admin"}</Field>
            <Field label="处理动作">
              {/* 选项由状态机过滤：处理中的差错不能再「定责」一次，只能核对结案或发起补差 */}
              <Select className="w-full" value={reconAction} onChange={(e) => setReconAction(e.target.value as ReconAction)}>
                {reconActionsFor(reconHandle).map((a) => (
                  <option key={a} value={a}>{RECON_TRANSITIONS[a].label}</option>
                ))}
              </Select>
              <div className="mt-1 text-xs text-muted-foreground">{RECON_TRANSITIONS[reconAction].hint}</div>
              <div className="mt-1 text-xs text-muted-foreground">
                处理后进度将变为「{RECON_HANDLE_STATUS[RECON_TRANSITIONS[reconAction].to].label}」
                {RECON_TERMINAL.includes(RECON_TRANSITIONS[reconAction].to) ? "（终态，不可再处置）" : ""}
              </div>
            </Field>
            <Field label="处理结论（必填）">
              <Input
                className="w-full"
                value={reconNote}
                placeholder="如：差额来自 07-15 跨日切分，凭证 V2031 已核对；或 已提补差单 ADJ-2026-0041"
                onChange={(e) => setReconNote(e.target.value)}
              />
            </Field>
          </>
        )}
      </Drawer>

      {/* 发票详情：这张票的钱从哪来——与来源结算单逐项对照 + 开具/作废留痕 */}
      <Drawer
        open={!!invDetail}
        onOpenChange={(o) => !o && setInvDetail(null)}
        title={`发票 ${invDetail?.invoiceNo ?? ""}`}
        desc="开具后抬头与金额锁定；作废需填原因，两者都留痕"
        width="w-[560px]"
        footer={invDetail && (
          <div className="flex gap-2">
            {invDetail.status === "DRAFT" && canEditInvoice && (
              <Button disabled={issueInvoice.isPending} onClick={() => askIssueInvoice(invDetail)}>开具</Button>
            )}
            {invDetail.status === "ISSUED" && canVoidInvoice && (
              <Button variant="destructive" disabled={voidInvoice.isPending} onClick={() => { setInvVoid(invDetail); setInvVoidReason(""); }}>作废</Button>
            )}
          </div>
        )}
      >
        {invDetail && (
          <>
            <Field label="状态"><StatusBadge map={INV_STATUS} value={invDetail.status} /></Field>
            <Field label="抬头">{invDetail.payeeName}</Field>
            <Field label="金额">{money(invDetail.amount, invDetail.currency)}</Field>
            <Field label="VAT TRN">{invDetail.vatTrn}</Field>
            {/* 来源单号 + 金额核对：对不上就不许开具（服务端也会再拦一次） */}
            <Field label="来源结算单">
              <Link href="/finance?tab=settlements" className="tabular-nums underline-offset-4 hover:underline">{invDetail.sourceNo}</Link>
              <span className="ml-2 text-muted-foreground">（结算单出账 → 开票，金额必须一致）</span>
            </Field>
            <Field label="发票代码 / 号码">
              {invDetail.invoiceCode ? `${invDetail.invoiceCode} / ${invDetail.invoiceNumber}` : "未开具（开具时由系统生成）"}
            </Field>
            <Field label="开具人 / 开具时间">
              {invDetail.issuedBy ? `${invDetail.issuedBy} · ${fmtTime(invDetail.issuedAt!)}` : "未开具"}
            </Field>
            <Field label="作废人 / 作废时间">
              {invDetail.voidedBy ? `${invDetail.voidedBy} · ${fmtTime(invDetail.voidedAt!)}` : "-"}
            </Field>
            <Field label="作废原因">{invDetail.voidReason ?? "-"}</Field>
            {/* 「为什么这屉里改不了」原先是手写灰底块，圆角还取自 Tailwind 默认阶（已废弃）。
                收敛到 Notice 原语：形状与字号跟着规范走，不各页各一套 */}
            {invDetail.status !== "DRAFT" && (
              <Notice className="mb-0">
                {invDetail.status === "ISSUED"
                  ? "已开具：抬头与金额已进税务口径，不可再修改——如需更正请作废后重新登记草稿再开具。"
                  : "已作废：本票为历史记录，不可修改也不可再开具。"}
              </Notice>
            )}
          </>
        )}
      </Drawer>

      {/* 作废：原因必填 + useConfirm 二次确认（不可逆） */}
      <Drawer
        open={!!invVoid}
        onOpenChange={(o) => !o && setInvVoid(null)}
        title={`作废发票 ${invVoid?.invoiceNo ?? ""}`}
        desc="不可逆操作：已开具的票作废后失效，作废人/时间/原因将留痕备查"
        footer={
          invVoid && canVoidInvoice && (
            <Button variant="destructive" disabled={voidInvoice.isPending || !invVoidReason.trim()} onClick={submitVoidInvoice}>
              作废发票
            </Button>
          )
        }
      >
        {invVoid && (
          <>
            <Field label="抬头">{invVoid.payeeName}</Field>
            <Field label="金额">{money(invVoid.amount, invVoid.currency)}</Field>
            <Field label="发票代码 / 号码">{invVoid.invoiceCode} / {invVoid.invoiceNumber}</Field>
            <Field label="来源结算单">{invVoid.sourceNo}</Field>
            <Field label="作废人">{username || "admin"}</Field>
            <Field label="作废原因（必填）">
              <Input
                className="w-full"
                value={invVoidReason}
                placeholder="如：抬头填错需重开 / 客户取消开票需求 / 税号有误"
                onChange={(e) => setInvVoidReason(e.target.value)}
              />
            </Field>
          </>
        )}
      </Drawer>
      {dialog}
    </div>
  );
}

export default function FinancePage() {
  return <Suspense fallback={null}><FinanceInner /></Suspense>;
}
