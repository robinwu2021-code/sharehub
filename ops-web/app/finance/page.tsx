"use client";

import { Suspense, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Pagination, StatCard } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column, type SortDir } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Tabs } from "@/components/ui/tabs";
import { DateInput } from "@/components/ui/date-input";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { money, fmtTime } from "@/lib/utils";
import { useAuth } from "@/lib/auth";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import type {
  ShareRule, Settlement, SettlementStatus, Withdrawal, LedgerEntry, ShareRecord,
  Reconcile, ReconAction, ReconHandleStatus, Invoice, InvoiceStatus,
  ShareSummary, RechargeOrder, PageResult,
} from "@/lib/types";
import { RECON_TRANSITIONS, RECON_TERMINAL, canReconTransition, canEditInvoiceFields } from "@/lib/types";

const SIZE = 10;
const TABS = [{ key: "rules", label: "分润规则" }, { key: "records", label: "分润明细" }, { key: "summary", label: "分润统计", phase: 2 as const }, { key: "settlements", label: "结算单" }, { key: "ledger", label: "账务分录", phase: 2 as const }, { key: "withdrawals", label: "提现", phase: 2 as const }, { key: "reconcile", label: "对账", phase: 3 as const }, { key: "invoices", label: "发票", phase: 3 as const }, { key: "recharges", label: "充值订单", phase: 3 as const }];

// 分润统计：维度切换器（竞品把「运营商佣金」「商户佣金」拆成两套菜单两张表，
// 我们一张表切 dimension——列完全相同，少一次跳转）
const SUMMARY_DIMS = [{ key: "VENUE", label: "场地方" }, { key: "AGENT", label: "代理商" }];
const SUMMARY_PERIODS = ["2026-07", "2026-06", "2026-05"];
const RECHARGE_STATUS: Record<RechargeOrder["status"], { label: string; tone: "success" | "warning" | "danger" | "muted" }> = {
  PENDING: { label: "待支付", tone: "warning" },
  PAID: { label: "已支付", tone: "success" },
  FAILED: { label: "支付失败", tone: "danger" },
  REFUNDED: { label: "已退款", tone: "muted" },
};

// 结算单状态：全站同色（待确认=warning / 已确认=default / 已打款=success）
const STL_STATUS: Record<SettlementStatus, { label: string; tone: "success" | "warning" | "default" }> = {
  DRAFT: { label: "待确认", tone: "warning" },
  CONFIRMED: { label: "已确认", tone: "default" },
  PAID: { label: "已打款", tone: "success" },
};
const PAYEE_TYPE_LABEL = { VENUE: "场地方", AGENT: "代理商" } as const;
/** multiselect + csv 的值是逗号分隔业务号串。 */
const csvArr = (v: unknown) => String(v ?? "").split(",").map((s) => s.trim()).filter(Boolean);

const RULE_FIELDS: FieldDef[] = [
  { key: "ruleNo", label: "规则号", readOnlyOnEdit: true, placeholder: "新增留空自动生成" },
  { key: "dimension", label: "维度", type: "select", options: [{ value: "VENUE", label: "场地方" }, { value: "AGENT", label: "代理商" }] },
  { key: "payeeName", label: "分成方", placeholder: "如 XX 商场" },
  { key: "mode", label: "模式", type: "select", options: [{ value: "CHANNEL_SPLIT", label: "渠道分账" }, { value: "LEDGER", label: "平台记账" }] },
  { key: "rate", label: "比例（0~1，如 0.3）", type: "number" },
  { key: "priority", label: "优先级", type: "number" },
];
// —— 对账差错（S2）——
// 跑批结果与处置进度是两列：status 是机器算的事实，handleStatus 是人推的进度，不混为一谈。
const RECON_HANDLE_STATUS: Record<ReconHandleStatus, { label: string; tone: "success" | "warning" | "danger" | "muted" }> = {
  OPEN: { label: "待处理", tone: "danger" },
  HANDLING: { label: "处理中", tone: "warning" },
  RESOLVED: { label: "已结案", tone: "success" },
  IGNORED: { label: "已忽略", tone: "muted" },
};
const RECON_RESULT_LABEL: Record<string, string> = {
  VERIFIED_OK: "核对无误", PLATFORM_ERROR: "平台侧差错", CHANNEL_ERROR: "渠道侧差错", COMPENSATED: "已补差",
};
/** 差错方向由 diff 的正负推出（不是新造的分类字段，就是同一个数的解读）。 */
const diffSideLabel = (diff: number) =>
  diff === 0 ? "已平" : diff > 0 ? "渠道多 / 账务少记" : "账务多记 / 渠道少到账";

// —— 发票（S2）——
const INV_STATUS: Record<InvoiceStatus, { label: string; tone: "success" | "danger" | "muted" }> = {
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
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "rules");
  const [page, setPage] = useState(1);
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
  const [invoiceForm, setInvoiceForm] = useState<Partial<Invoice> | null>(null);
  // 对账差错处置（S2）：处理走抽屉——结论必填，与提现审批同一套「审批类抽屉」范式
  const [reconHandle, setReconHandle] = useState<Reconcile | null>(null);
  const [reconAction, setReconAction] = useState<ReconAction>("verify");
  const [reconNote, setReconNote] = useState("");
  const [reconStatusFilter, setReconStatusFilter] = useState("");
  // 发票（S2）：详情下钻（含来源结算单核对）+ 作废抽屉（原因必填 + 二次确认）
  const [invDetail, setInvDetail] = useState<Invoice | null>(null);
  const [invVoid, setInvVoid] = useState<Invoice | null>(null);
  const [invVoidReason, setInvVoidReason] = useState("");
  const [invStatusFilter, setInvStatusFilter] = useState("");
  // 提现审批：走抽屉而非行内按钮——驳回必须留原因，是资金审批的留痕底线
  const [wdAudit, setWdAudit] = useState<Withdrawal | null>(null);
  const [wdApprove, setWdApprove] = useState("1");
  const [wdReject, setWdReject] = useState("");
  const username = useAuth((s) => s.username);
  // 分润统计：维度 / 周期 / 排序（排序受控，实际排序在 mock·后端做，翻页后仍成立）
  const [sumDim, setSumDim] = useState("VENUE");
  const [sumPeriod, setSumPeriod] = useState(SUMMARY_PERIODS[0]);
  const [sumSortKey, setSumSortKey] = useState("shareAmount");
  const [sumSortDir, setSumSortDir] = useState<SortDir>("desc");
  // 充值订单：状态 + 日期范围（按下单时间）
  const [rcStatus, setRcStatus] = useState("");
  const [rcFrom, setRcFrom] = useState("");
  const [rcTo, setRcTo] = useState("");
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);
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

  const q = useQuery<PageResult<ShareRule | Settlement | Withdrawal | LedgerEntry | ShareRecord | Reconcile | Invoice | ShareSummary | RechargeOrder>>({
    queryKey: ["fin", tab, page, keyword, sumDim, sumPeriod, sumSortKey, sumSortDir, rcStatus, rcFrom, rcTo, stlStatus, reconStatusFilter, invStatusFilter],
    queryFn: () =>
      tab === "rules" ? api.listShareRules({ page, size: SIZE, keyword })
      : tab === "ledger" ? api.listLedger({ page, size: SIZE, keyword })
      : tab === "settlements" ? api.listSettlements({ page, size: SIZE, keyword, status: stlStatus || undefined })
      : tab === "records" ? api.listShareRecords({ page, size: SIZE, keyword })
      : tab === "summary" ? api.listShareSummaries({ page, size: SIZE, keyword, dimension: sumDim, period: sumPeriod, sortKey: sumSortKey, sortDir: sumSortDir })
      : tab === "recharges" ? api.listRechargeOrders({ page, size: SIZE, keyword, status: rcStatus || undefined, from: rcFrom || undefined, to: rcTo || undefined })
      : tab === "reconcile" ? api.listReconciles({ page, size: SIZE, keyword, handleStatus: reconStatusFilter || undefined })
      : tab === "invoices" ? api.listInvoices({ page, size: SIZE, keyword, status: invStatusFilter || undefined })
      : api.listWithdrawals({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
  });

  const canAuditWithdrawal = allow("finance:withdrawal:audit");
  const audit = useMutation({
    mutationFn: (v: { no: string; approve: boolean; rejectReason?: string }) =>
      api.auditWithdrawal(v.no, v.approve, v.rejectReason, username || undefined),
    onSuccess: (_r, v) => {
      notify.success(v.approve ? "提现已通过，转打款中" : "提现已驳回");
      qc.invalidateQueries({ queryKey: ["fin"] });
      setWdAudit(null);
    },
  });

  // —— 结算单闭环（S1）——
  // 生成：金额从该周期的分润明细汇总而来；确认：DRAFT → CONFIRMED。
  // 幂等冲突 / 无明细 / 非法状态迁移都由服务端（mock db 层）拒绝并给出可读原因，页面不重复兜底。
  const canGenSettlement = allow("finance:settlement:generate");
  const canConfirmSettlement = allow("finance:settlement:confirm");
  // 结算对象候选：场地方 / 代理商各自的主数据，抽屉打开才拉
  const venuesQ = useQuery({
    queryKey: ["stl-venues"],
    queryFn: () => api.listVenues({ page: 1, size: 200 }),
    enabled: !!genForm && genForm.payeeType === "VENUE",
  });
  const agentsQ = useQuery({
    queryKey: ["stl-agents"],
    queryFn: () => api.listAgents({ page: 1, size: 200 }),
    enabled: !!genForm && genForm.payeeType === "AGENT",
  });
  // 结算单构成明细：这张单的钱是哪几笔分润凑出来的
  const stlRecordsQ = useQuery({
    queryKey: ["stl-records", stlDetail?.settleNo ?? ""],
    queryFn: () => api.listSettlementRecords(stlDetail!.settleNo, { page: 1, size: 100 }),
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
  const handleRecon = useMutation({
    mutationFn: (v: { batchNo: string; action: ReconAction; note: string }) =>
      api.handleRecon(v.batchNo, v.action, v.note, username || undefined),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["fin"] });
      notify.success(`差错 ${r.batchNo} 已${RECON_HANDLE_STATUS[r.handleStatus!].label}（${RECON_TRANSITIONS[reconAction].label}）`);
      setReconHandle(null);
    },
  });
  /** 当前差错允许的动作：状态机说了算，终态返回空数组（页面因此不出处理按钮）。 */
  const reconActionsFor = (r: Reconcile | null): ReconAction[] =>
    !r || r.handleStatus === null ? []
      : (Object.keys(RECON_TRANSITIONS) as ReconAction[]).filter((a) => canReconTransition(r.handleStatus!, a));
  function openReconHandle(r: Reconcile) {
    setReconHandle(r);
    setReconAction(reconActionsFor(r)[0] ?? "verify");
    setReconNote("");
  }

  // —— S2 发票开具 / 作废 ——
  // 开票只能挂在**已确认**的结算单上（草稿单还可能重算），金额由该单带出，页面不让手输一个对不上的数。
  const invSettlementsQ = useQuery({
    queryKey: ["inv-settlements"],
    queryFn: () => api.listSettlements({ page: 1, size: 200 }),
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

  const ruleCols: Column<ShareRule>[] = [
    { header: "规则号", cell: (r) => <span className="font-medium">{r.ruleNo}</span> },
    { header: "维度", cell: (r) => r.dimension === "VENUE" ? "场地方" : "代理商" },
    { header: "分成方", cell: (r) => r.payeeName },
    { header: "模式", cell: (r) => <Badge tone="outline">{r.mode === "CHANNEL_SPLIT" ? "渠道分账" : "平台记账"}</Badge> },
    { header: "比例", cell: (r) => `${(r.rate * 100).toFixed(0)}%` },
    { header: "优先级", cell: (r) => <span className="tabular-nums">{r.priority}</span> },
    { header: t("common.actions"), cell: (r) => canEditRule ? <Button size="sm" variant="outline" onClick={() => setRuleForm(r)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];
  const stlCols: Column<Settlement>[] = [
    {
      header: "结算单号",
      cell: (s) => (
        <button type="button" className="font-medium tabular-nums underline-offset-4 hover:underline" onClick={() => setStlDetail(s)}>
          {s.settleNo}
        </button>
      ),
    },
    { header: "对象", cell: (s) => <span>{s.payeeName} <span className="text-muted-foreground tabular-nums">{s.payeeNo}</span>（{PAYEE_TYPE_LABEL[s.payeeType]}）</span> },
    { header: "周期", cell: (s) => <span className="tabular-nums">{s.period}</span> },
    { header: "金额", cell: (s) => <span className="tabular-nums">{money(s.totalAmount, s.currency)}</span> },
    // 明细笔数：金额是这几笔分润加出来的，点单号可逐笔核对
    { header: "明细笔数", cell: (s) => <span className="tabular-nums text-muted-foreground">{s.recordCount}</span> },
    { header: "状态", cell: (s) => <Badge tone={STL_STATUS[s.status].tone}>{STL_STATUS[s.status].label}</Badge> },
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
    { header: "提现号", cell: (w) => <span className="font-medium">{w.withdrawNo}</span> },
    { header: "对象", cell: (w) => w.payeeName },
    { header: "金额", cell: (w) => <span className="tabular-nums">{money(w.amount, w.currency)}</span> },
    // 手续费与实际到账同屏：审批人不必心算，避免按毛额放款
    { header: "手续费", cell: (w) => <span className="tabular-nums">{money(w.fee, w.currency)}</span> },
    { header: "实际到账", cell: (w) => <span className="tabular-nums">{money(w.amount - w.fee, w.currency)}</span> },
    { header: "状态", cell: (w) => <Badge tone={w.status === "PAID" || w.status === "PAYING" ? "success" : w.status === "FAILED" ? "danger" : "warning"}>{w.status}</Badge> },
    { header: "申请时间", cell: (w) => <span className="text-muted-foreground">{fmtTime(w.appliedAt)}</span> },
    // 审批留痕三列：谁批的 / 何时批的 / 驳回为什么
    { header: "审批人", cell: (w) => w.auditorName ?? <span className="text-muted-foreground">未审批</span> },
    { header: "审批时间", cell: (w) => <span className="text-muted-foreground">{w.auditedAt ? fmtTime(w.auditedAt) : "-"}</span> },
    { header: "驳回原因", cell: (w) => <span className="text-muted-foreground">{w.rejectReason ?? "-"}</span> },
    {
      header: t("common.actions"),
      cell: (w) => (w.status === "AUDIT" || w.status === "APPLY") && canAuditWithdrawal ? (
        <Button size="sm" variant="outline" onClick={() => { setWdAudit(w); setWdApprove("1"); setWdReject(""); }}>审批</Button>
      ) : <span className="text-muted-foreground">-</span>,
    },
  ];

  const ledgerCols: Column<LedgerEntry>[] = [
    { header: "分录号", cell: (l) => <span className="font-medium">{l.entryNo}</span> },
    { header: "凭证", cell: (l) => <span className="text-muted-foreground">{l.voucherNo}</span> },
    { header: "订单", cell: (l) => <span className="text-muted-foreground">{l.orderNo ?? "-"}</span> },
    { header: "账户", cell: (l) => l.account },
    { header: "方向", cell: (l) => l.direction === "DEBIT" ? <Badge tone="outline">借</Badge> : <Badge tone="muted">贷</Badge> },
    { header: "金额", cell: (l) => <span className="tabular-nums">{money(l.amount, l.currency)}</span> },
    { header: "摘要", cell: (l) => <span className="text-muted-foreground">{l.summary}</span> },
    { header: "时间", cell: (l) => <span className="text-muted-foreground">{fmtTime(l.createdAt)}</span> },
  ];

  const recordCols: Column<ShareRecord>[] = [
    { header: "明细号", cell: (r) => <span className="font-medium">{r.recordNo}</span> },
    { header: "订单", cell: (r) => <span className="text-muted-foreground">{r.orderNo}</span> },
    { header: "维度", cell: (r) => PAYEE_TYPE_LABEL[r.dimension] },
    { header: "分成方", cell: (r) => <span>{r.payeeName} <span className="text-muted-foreground tabular-nums">{r.payeeNo}</span></span> },
    { header: "金额", cell: (r) => <span className="tabular-nums">{money(r.amount, r.currency)}</span> },
    { header: "比例", cell: (r) => `${(r.rate * 100).toFixed(0)}%` },
    // 周期是结算单的汇总键：明细上直接看得到它归哪一期，才对得上结算单
    { header: "周期", cell: (r) => <span className="tabular-nums">{r.period}</span> },
    { header: "时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
  ];

  // 分润统计：分成方点进去 = 深链到分润明细并带 payee（跨模块跳转一律 <Link> + 完整 href）
  const summaryCols: Column<ShareSummary>[] = [
    {
      header: "分成方",
      cell: (s) => (
        <Link href={`/finance?tab=records&payee=${encodeURIComponent(s.payeeName)}`} className="font-medium underline-offset-4 hover:underline">
          {s.payeeName}
        </Link>
      ),
    },
    { header: "编号", cell: (s) => <span className="text-muted-foreground tabular-nums">{s.payeeNo}</span> },
    { header: "统计周期", cell: (s) => <span className="tabular-nums">{s.period}</span> },
    { header: "订单数", cell: (s) => <span className="tabular-nums">{s.orderCount}</span>, sortKey: "orderCount" },
    { header: "交易额", cell: (s) => <span className="tabular-nums">{money(s.gmv, s.currency)}</span>, sortKey: "gmv" },
    { header: "分润额", cell: (s) => <span className="tabular-nums">{money(s.shareAmount, s.currency)}</span>, sortKey: "shareAmount" },
    { header: "已结算", cell: (s) => <span className="tabular-nums text-muted-foreground">{money(s.settledAmount, s.currency)}</span> },
    // 待结算 = 分润 − 已结算：财务最关心的数，未结清高亮，结清则弱化
    {
      header: "待结算",
      cell: (s) => (
        <span className={s.pendingAmount > 0 ? "font-medium tabular-nums text-[var(--destructive)]" : "tabular-nums text-muted-foreground"}>
          {money(s.pendingAmount, s.currency)}
        </span>
      ),
      sortKey: "pendingAmount",
    },
  ];

  const rechargeCols: Column<RechargeOrder>[] = [
    { header: "充值单号", cell: (r) => <span className="font-medium tabular-nums">{r.rechargeNo}</span> },
    { header: "用户", cell: (r) => <span>{r.nickname} <span className="text-muted-foreground tabular-nums">{r.userNo}</span></span> },
    { header: "套餐", cell: (r) => r.packageNo ? <span className="tabular-nums">{r.packageNo}</span> : <Badge tone="outline">自定义金额</Badge> },
    { header: "实付", cell: (r) => <span className="tabular-nums">{money(r.payAmount, r.currency)}</span> },
    { header: "赠送", cell: (r) => <span className="tabular-nums text-muted-foreground">{money(r.giftAmount, r.currency)}</span> },
    { header: "到账", cell: (r) => <span className="font-medium tabular-nums">{money(r.creditAmount, r.currency)}</span> },
    // 渠道码与 系统设置·支付渠道（/system?tab=payment）同一套 channelCode
    { header: "支付渠道", cell: (r) => <Badge tone="outline">{r.channelCode}</Badge> },
    { header: "状态", cell: (r) => <Badge tone={RECHARGE_STATUS[r.status].tone}>{RECHARGE_STATUS[r.status].label}</Badge> },
    { header: "支付时间", cell: (r) => <span className="text-muted-foreground">{r.paidAt ? fmtTime(r.paidAt) : "-"}</span> },
    { header: "网关流水号", cell: (r) => <span className="text-muted-foreground tabular-nums">{r.psgTxnNo ?? "-"}</span> },
  ];

  const reconcileCols: Column<Reconcile>[] = [
    { header: "批次号", cell: (r) => <span className="font-medium">{r.batchNo}</span> },
    { header: "周期", cell: (r) => r.period },
    { header: "nearpay 汇总", cell: (r) => <span className="tabular-nums">{money(r.nearpayTotal, r.currency)}</span> },
    { header: "账务汇总", cell: (r) => <span className="tabular-nums">{money(r.ledgerTotal, r.currency)}</span> },
    // 差额同时给「多少钱」和「往哪边偏」——方向就是 diff 的正负，不是另造的分类
    {
      header: "差额",
      cell: (r) => (
        <div>
          <span className={r.diff === 0 ? "tabular-nums text-muted-foreground" : "font-medium tabular-nums text-[var(--destructive)]"}>
            {money(r.diff, r.currency)}
          </span>
          {r.diff !== 0 && <div className="text-xs text-muted-foreground">{diffSideLabel(r.diff)}</div>}
        </div>
      ),
    },
    { header: "跑批结果", cell: (r) => <Badge tone={r.status === "MATCHED" ? "success" : "danger"}>{r.status === "MATCHED" ? "已平" : "有差异"}</Badge> },
    // 处置进度是另一列：把「已核对无误」写回跑批结果会篡改事实，日后无从审计
    {
      header: "处置进度",
      cell: (r) => r.handleStatus
        ? <Badge tone={RECON_HANDLE_STATUS[r.handleStatus].tone}>{RECON_HANDLE_STATUS[r.handleStatus].label}</Badge>
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
      // 终态（已结案/已忽略）与已平批次都不出按钮——状态机说了不能动，页面就不该给入口
      cell: (r) => r.handleStatus && !RECON_TERMINAL.includes(r.handleStatus) && canHandleRecon
        ? <Button size="sm" variant="outline" onClick={() => openReconHandle(r)}>处理差错</Button>
        : <span className="text-muted-foreground">-</span>,
    },
  ];

  const invoiceCols: Column<Invoice>[] = [
    {
      header: "发票号",
      cell: (i) => (
        <button type="button" className="font-medium tabular-nums underline-offset-4 hover:underline" onClick={() => setInvDetail(i)}>
          {i.invoiceNo}
        </button>
      ),
    },
    { header: "抬头", cell: (i) => i.payeeName },
    { header: "金额", cell: (i) => <span className="tabular-nums">{money(i.amount, i.currency)}</span> },
    // 来源单号：这张票的钱是哪张结算单来的（开具时校验两者金额必须一致）
    { header: "来源结算单", cell: (i) => <span className="text-muted-foreground tabular-nums">{i.sourceNo}</span> },
    { header: "发票代码 / 号码", cell: (i) => <span className="text-muted-foreground tabular-nums">{i.invoiceCode ? `${i.invoiceCode} / ${i.invoiceNumber}` : "未开具"}</span> },
    { header: "VAT TRN", cell: (i) => <span className="text-muted-foreground tabular-nums">{i.vatTrn}</span> },
    { header: "状态", cell: (i) => <Badge tone={INV_STATUS[i.status].tone}>{INV_STATUS[i.status].label}</Badge> },
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

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); }} />
      {tab === "rules" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索分成方"
          onAdd={canEditRule ? () => setRuleForm({ dimension: "VENUE", mode: "CHANNEL_SPLIT", rate: 0.3, priority: 1 }) : undefined}
          addLabel="新增分润规则"
          onExport={() => exportCsv<ShareRule>("分润规则", [
            { header: "规则号", value: (r) => r.ruleNo },
            { header: "维度", value: (r) => (r.dimension === "VENUE" ? "场地方" : "代理商") },
            { header: "分成方", value: (r) => r.payeeName },
            { header: "模式", value: (r) => (r.mode === "CHANNEL_SPLIT" ? "渠道分账" : "平台记账") },
            { header: "比例", value: (r) => r.rate },
            { header: "优先级", value: (r) => r.priority },
          ], (q.data?.list ?? []) as ShareRule[])}
        />
      )}
      {tab === "ledger" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
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
        />
      )}
      {tab === "settlements" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
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
          <Select value={stlStatus} onChange={(e) => { setStlStatus(e.target.value); setPage(1); }}>
            <option value="">全部状态</option>
            {Object.entries(STL_STATUS).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
          </Select>
        </Toolbar>
      )}
      {tab === "withdrawals" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索提现号/对象/审批人"
          onExport={() => exportCsv<Withdrawal>("提现", [
            { header: "提现号", value: (w) => w.withdrawNo },
            { header: "对象", value: (w) => w.payeeName },
            { header: "金额", value: (w) => w.amount },
            { header: "手续费", value: (w) => w.fee },
            { header: "实际到账", value: (w) => w.amount - w.fee },
            { header: "币种", value: (w) => w.currency },
            { header: "状态", value: (w) => w.status },
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
          onSearch={(v) => { setKeyword(v); setPage(1); }}
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
          <Tabs tabs={SUMMARY_DIMS} value={sumDim} onChange={(k) => { setSumDim(k); setPage(1); }} />
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
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
            <Select value={sumPeriod} onChange={(e) => { setSumPeriod(e.target.value); setPage(1); }}>
              {SUMMARY_PERIODS.map((p) => <option key={p} value={p}>{p}</option>)}
            </Select>
          </Toolbar>
        </>
      )}
      {tab === "recharges" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
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
          <Select value={rcStatus} onChange={(e) => { setRcStatus(e.target.value); setPage(1); }}>
            <option value="">全部状态</option>
            {Object.entries(RECHARGE_STATUS).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
          </Select>
          {/* 日期范围按下单时间：待支付/失败单没有支付时间，用支付时间会把它们全筛掉 */}
          <DateInput className="w-40" aria-label="下单时间起" value={rcFrom} onChange={(e) => { setRcFrom(e.target.value); setPage(1); }} />
          <span className="text-muted-foreground">~</span>
          <DateInput className="w-40" aria-label="下单时间止" value={rcTo} onChange={(e) => { setRcTo(e.target.value); setPage(1); }} />
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
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索批次号/周期/处理人/结论"
            onExport={() => exportCsv<Reconcile>("对账", [
              { header: "批次号", value: (r) => r.batchNo },
              { header: "周期", value: (r) => r.period },
              { header: "nearpay 汇总", value: (r) => r.nearpayTotal },
              { header: "账务汇总", value: (r) => r.ledgerTotal },
              { header: "差额", value: (r) => r.diff },
              { header: "差错方向", value: (r) => diffSideLabel(r.diff) },
              { header: "币种", value: (r) => r.currency },
              { header: "跑批结果", value: (r) => (r.status === "MATCHED" ? "已平" : "有差异") },
              { header: "处置进度", value: (r) => (r.handleStatus ? RECON_HANDLE_STATUS[r.handleStatus].label : "无需处理") },
              { header: "定责", value: (r) => (r.handleResult ? RECON_RESULT_LABEL[r.handleResult] : "") },
              { header: "处理结论", value: (r) => r.handleNote },
              { header: "处理人", value: (r) => r.handledBy },
              { header: "处理时间", value: (r) => r.handledAt },
              { header: "跑批时间", value: (r) => r.createdAt },
            ], (q.data?.list ?? []) as Reconcile[])}
          >
            <Select value={reconStatusFilter} onChange={(e) => { setReconStatusFilter(e.target.value); setPage(1); }}>
              <option value="">全部处置进度</option>
              {Object.entries(RECON_HANDLE_STATUS).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
            </Select>
          </Toolbar>
        </>
      )}
      {tab === "invoices" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
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
          <Select value={invStatusFilter} onChange={(e) => { setInvStatusFilter(e.target.value); setPage(1); }}>
            <option value="">全部状态</option>
            {Object.entries(INV_STATUS).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
          </Select>
        </Toolbar>
      )}
      {tab === "rules" && <DataTable rowKey={(r: ShareRule) => r.ruleNo} columns={ruleCols} rows={q.data?.list as ShareRule[]} loading={q.isLoading} empty="暂无分润规则——点右上「新增分润规则」为场地方/代理商配置分成比例，否则订单收入全归平台" />}
      {tab === "ledger" && <DataTable rowKey={(l: LedgerEntry) => l.entryNo} columns={ledgerCols} rows={q.data?.list as LedgerEntry[]} loading={q.isLoading} empty="暂无账务分录——订单结算与分账完成后自动记账，也可放宽搜索条件再查" />}
      {tab === "settlements" && !canGenSettlement && !canConfirmSettlement && (
        <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">
          仅可查看：当前角色无结算单生成/确认权限（finance:settlement:generate / :confirm）
        </div>
      )}
      {tab === "settlements" && <DataTable rowKey={(s: Settlement) => s.settleNo} columns={stlCols} rows={q.data?.list as Settlement[]} loading={q.isLoading} empty="暂无结算单——点右上「生成结算单」按周期出账（金额取该周期分润明细汇总），或放宽筛选条件" />}
      {tab === "withdrawals" && !canAuditWithdrawal && <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">仅可查看：当前角色无提现审批权限（finance:withdrawal:audit）</div>}
      {tab === "withdrawals" && <DataTable rowKey={(w: Withdrawal) => w.withdrawNo} columns={wdCols} rows={q.data?.list as Withdrawal[]} loading={q.isLoading} empty="暂无提现申请——场地方/代理商发起提现后在此审批，通过才会进入打款队列" />}
      {tab === "records" && <DataTable rowKey={(r: ShareRecord) => r.recordNo} columns={recordCols} rows={q.data?.list as ShareRecord[]} loading={q.isLoading} empty="暂无分润明细——订单结算时按「分润规则」逐笔生成，先确认规则已配置" />}
      {tab === "summary" && (
        <DataTable
          rowKey={(s: ShareSummary) => `${s.dimension}-${s.payeeNo}-${s.period}`}
          columns={summaryCols}
          rows={q.data?.list as ShareSummary[]}
          loading={q.isLoading}
          empty={`${sumPeriod} 该维度暂无分润统计 —— 换个周期，或确认该周期已有已结算订单`}
          sortKey={sumSortKey}
          sortDir={sumSortDir}
          onSortChange={(k, d) => { setSumSortKey(k); setSumSortDir(d); setPage(1); }}
        />
      )}
      {tab === "recharges" && (
        <DataTable
          rowKey={(r: RechargeOrder) => r.rechargeNo}
          columns={rechargeCols}
          rows={q.data?.list as RechargeOrder[]}
          loading={q.isLoading}
          empty="暂无充值订单 —— 该筛选条件下没有记录，或用户尚未使用钱包充值"
        />
      )}
      {tab === "reconcile" && !canHandleRecon && (
        <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">
          仅可查看：当前角色无对账差错处理权限（finance:recon:handle）
        </div>
      )}
      {tab === "reconcile" && <DataTable rowKey={(r: Reconcile) => r.batchNo} columns={reconcileCols} rows={q.data?.list as Reconcile[]} loading={q.isLoading} empty="暂无对账批次——每日与 nearpay 流水自动跑批比对，本周期尚未生成批次；也可能是「处置进度」筛窄了" />}
      {tab === "invoices" && !canEditInvoice && !canVoidInvoice && (
        <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">
          仅可查看：当前角色无发票开具/作废权限（finance:invoice:issue / :void）
        </div>
      )}
      {tab === "invoices" && <DataTable rowKey={(i: Invoice) => i.invoiceNo} columns={invoiceCols} rows={q.data?.list as Invoice[]} loading={q.isLoading} empty="暂无发票——商户提出开票需求后点右上「登记发票草稿」挂到对应结算单，再开具" />}
      {q.data && <Pagination page={page} size={SIZE} total={q.data.total} onPage={setPage} />}

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
            <Field label="申请金额">{money(wdAudit.amount, wdAudit.currency)}</Field>
            <Field label="手续费">{money(wdAudit.fee, wdAudit.currency)}</Field>
            <Field label="实际到账">{money(wdAudit.amount - wdAudit.fee, wdAudit.currency)}</Field>
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

      {/* 生成结算单：类型 + 周期 + 多选对象；金额不在这里填——由服务端按分润明细汇总 */}
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
            <Field label="状态"><Badge tone={STL_STATUS[stlDetail.status].tone}>{STL_STATUS[stlDetail.status].label}</Badge></Field>
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

      {/* 对账差错处理：动作按状态机过滤，结论必填——「审批类抽屉」范式（同提现审批） */}
      <Drawer
        open={!!reconHandle}
        onOpenChange={(o) => !o && setReconHandle(null)}
        title={`处理对账差错 ${reconHandle?.batchNo ?? ""}`}
        desc="差错是真金白银对不上：处置需定责并留下结论，处理人与时间将留痕不可改"
        width="w-[560px]"
        footer={
          reconHandle && canHandleRecon && (
            <Button
              disabled={handleRecon.isPending || !reconNote.trim()}
              variant={reconAction === "compensate" ? "destructive" : "default"}
              onClick={() => handleRecon.mutate({ batchNo: reconHandle.batchNo, action: reconAction, note: reconNote })}
            >提交处理</Button>
          )
        }
      >
        {reconHandle && (
          <>
            <Field label="对账周期">{reconHandle.period}</Field>
            <Field label="nearpay 汇总 / 账务汇总">
              {money(reconHandle.nearpayTotal, reconHandle.currency)} / {money(reconHandle.ledgerTotal, reconHandle.currency)}
            </Field>
            <Field label="差额">
              <span className="font-medium tabular-nums text-[var(--destructive)]">{money(reconHandle.diff, reconHandle.currency)}</span>
              <span className="ml-2 text-muted-foreground">{diffSideLabel(reconHandle.diff)}</span>
            </Field>
            <Field label="当前处置进度">
              <Badge tone={RECON_HANDLE_STATUS[reconHandle.handleStatus!].tone}>{RECON_HANDLE_STATUS[reconHandle.handleStatus!].label}</Badge>
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
            <Field label="状态"><Badge tone={INV_STATUS[invDetail.status].tone}>{INV_STATUS[invDetail.status].label}</Badge></Field>
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
            {invDetail.status !== "DRAFT" && (
              <div className="rounded-lg bg-muted px-3.5 py-2 text-xs text-muted-foreground">
                {invDetail.status === "ISSUED"
                  ? "已开具：抬头与金额已进税务口径，不可再修改——如需更正请作废后重新登记草稿再开具。"
                  : "已作废：本票为历史记录，不可修改也不可再开具。"}
              </div>
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
