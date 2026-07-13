"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Pagination } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import type { ShareRule, Settlement, Withdrawal, LedgerEntry, ShareRecord, Reconcile, Invoice, PageResult } from "@/lib/types";

const SIZE = 10;
const TABS = [{ key: "rules", label: "分润规则" }, { key: "records", label: "分润明细" }, { key: "settlements", label: "结算单" }, { key: "ledger", label: "账务分录", phase: 2 as const }, { key: "withdrawals", label: "提现", phase: 2 as const }, { key: "reconcile", label: "对账", phase: 3 as const }, { key: "invoices", label: "发票", phase: 3 as const }];

const RULE_FIELDS: FieldDef[] = [
  { key: "ruleNo", label: "规则号", readOnlyOnEdit: true, placeholder: "新增留空自动生成" },
  { key: "dimension", label: "维度", type: "select", options: [{ value: "VENUE", label: "场地方" }, { value: "AGENT", label: "代理商" }] },
  { key: "payeeName", label: "分成方", placeholder: "如 XX 商场" },
  { key: "mode", label: "模式", type: "select", options: [{ value: "CHANNEL_SPLIT", label: "渠道分账" }, { value: "LEDGER", label: "平台记账" }] },
  { key: "rate", label: "比例（0~1，如 0.3）", type: "number" },
  { key: "priority", label: "优先级", type: "number" },
];
const INVOICE_FIELDS: FieldDef[] = [
  { key: "invoiceNo", label: "发票号", readOnlyOnEdit: true, placeholder: "新增留空自动生成" },
  { key: "payeeName", label: "抬头", placeholder: "开票抬头" },
  { key: "amount", label: "金额", type: "number" },
  { key: "vatTrn", label: "VAT TRN", placeholder: "税号" },
  { key: "currency", label: "币种", placeholder: "AED" },
  { key: "status", label: "状态", type: "select", options: [{ value: "DRAFT", label: "草稿" }, { value: "ISSUED", label: "已开具" }, { value: "VOID", label: "已作废" }] },
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
  const [ruleForm, setRuleForm] = useState<Partial<ShareRule> | null>(null);
  const [invoiceForm, setInvoiceForm] = useState<Partial<Invoice> | null>(null);
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);
  useEffect(() => { setKeyword(""); }, [tab]);

  const canEditRule = allow("finance:share_rule:config");
  const canEditInvoice = allow("finance:invoice:issue");

  const q = useQuery<PageResult<ShareRule | Settlement | Withdrawal | LedgerEntry | ShareRecord | Reconcile | Invoice>>({
    queryKey: ["fin", tab, page, keyword],
    queryFn: () =>
      tab === "rules" ? api.listShareRules({ page, size: SIZE, keyword })
      : tab === "ledger" ? api.listLedger({ page, size: SIZE, keyword })
      : tab === "settlements" ? api.listSettlements({ page, size: SIZE, keyword })
      : tab === "records" ? api.listShareRecords({ page, size: SIZE, keyword })
      : tab === "reconcile" ? api.listReconciles({ page, size: SIZE, keyword })
      : tab === "invoices" ? api.listInvoices({ page, size: SIZE, keyword })
      : api.listWithdrawals({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
  });

  const audit = useMutation({
    mutationFn: (v: { no: string; approve: boolean }) => api.auditWithdrawal(v.no, v.approve),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["fin", "withdrawals"] }),
  });

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
    { header: "结算单号", cell: (s) => <span className="font-medium">{s.settleNo}</span> },
    { header: "对象", cell: (s) => `${s.payeeName}（${s.payeeType === "VENUE" ? "场地方" : "代理"}）` },
    { header: "周期", cell: (s) => s.period },
    { header: "金额", cell: (s) => <span className="tabular-nums">{money(s.totalAmount, s.currency)}</span> },
    { header: "状态", cell: (s) => <Badge tone={s.status === "PAID" ? "success" : s.status === "CONFIRMED" ? "default" : "muted"}>{s.status === "PAID" ? "已打款" : s.status === "CONFIRMED" ? "已确认" : "已生成"}</Badge> },
  ];
  const wdCols: Column<Withdrawal>[] = [
    { header: "提现号", cell: (w) => <span className="font-medium">{w.withdrawNo}</span> },
    { header: "对象", cell: (w) => w.payeeName },
    { header: "金额", cell: (w) => <span className="tabular-nums">{money(w.amount, w.currency)}</span> },
    { header: "状态", cell: (w) => <Badge tone={w.status === "PAID" || w.status === "PAYING" ? "success" : w.status === "FAILED" ? "danger" : "warning"}>{w.status}</Badge> },
    { header: "申请时间", cell: (w) => <span className="text-muted-foreground">{fmtTime(w.appliedAt)}</span> },
    {
      header: "操作",
      cell: (w) => (w.status === "AUDIT" || w.status === "APPLY") && allow("finance:withdrawal:audit") ? (
        <div className="flex gap-2">
          <Button size="sm" onClick={() => audit.mutate({ no: w.withdrawNo, approve: true })} disabled={audit.isPending}>通过</Button>
          <Button size="sm" variant="outline" onClick={() => audit.mutate({ no: w.withdrawNo, approve: false })} disabled={audit.isPending}>拒绝</Button>
        </div>
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
    { header: "维度", cell: (r) => r.dimension === "VENUE" ? "场地方" : "代理商" },
    { header: "分成方", cell: (r) => r.payeeName },
    { header: "金额", cell: (r) => <span className="tabular-nums">{money(r.amount, r.currency)}</span> },
    { header: "比例", cell: (r) => `${(r.rate * 100).toFixed(0)}%` },
    { header: "时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
  ];

  const reconcileCols: Column<Reconcile>[] = [
    { header: "批次号", cell: (r) => <span className="font-medium">{r.batchNo}</span> },
    { header: "周期", cell: (r) => r.period },
    { header: "nearpay 汇总", cell: (r) => <span className="tabular-nums">{money(r.nearpayTotal, r.currency)}</span> },
    { header: "账务汇总", cell: (r) => <span className="tabular-nums">{money(r.ledgerTotal, r.currency)}</span> },
    { header: "差额", cell: (r) => <span className="tabular-nums">{money(r.diff, r.currency)}</span> },
    { header: "状态", cell: (r) => <Badge tone={r.status === "MATCHED" ? "success" : "danger"}>{r.status === "MATCHED" ? "已平" : "有差异"}</Badge> },
    { header: "时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
  ];

  const invoiceCols: Column<Invoice>[] = [
    { header: "发票号", cell: (i) => <span className="font-medium">{i.invoiceNo}</span> },
    { header: "抬头", cell: (i) => i.payeeName },
    { header: "金额", cell: (i) => <span className="tabular-nums">{money(i.amount, i.currency)}</span> },
    { header: "VAT TRN", cell: (i) => <span className="text-muted-foreground tabular-nums">{i.vatTrn}</span> },
    { header: "状态", cell: (i) => <Badge tone={i.status === "ISSUED" ? "success" : i.status === "VOID" ? "danger" : "muted"}>{i.status === "ISSUED" ? "已开具" : i.status === "VOID" ? "已作废" : "草稿"}</Badge> },
    { header: "开具时间", cell: (i) => <span className="text-muted-foreground">{i.status === "DRAFT" ? "-" : fmtTime(i.issuedAt)}</span> },
    { header: t("common.actions"), cell: (i) => canEditInvoice ? <Button size="sm" variant="outline" onClick={() => setInvoiceForm(i)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
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
        />
      )}
      {tab === "ledger" && <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索账户/订单/凭证" />}
      {tab === "settlements" && <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索结算单号/对象" />}
      {tab === "withdrawals" && <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索提现号/对象" />}
      {tab === "records" && <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索明细号/订单/分成方" />}
      {tab === "reconcile" && <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索批次号/周期" />}
      {tab === "invoices" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索发票号/抬头/税号"
          onAdd={canEditInvoice ? () => setInvoiceForm({ currency: "AED", status: "DRAFT", amount: 0 }) : undefined}
          addLabel="新增发票"
        />
      )}
      {tab === "rules" && <DataTable rowKey={(r: ShareRule) => r.ruleNo} columns={ruleCols} rows={q.data?.list as ShareRule[]} loading={q.isLoading} />}
      {tab === "ledger" && <DataTable rowKey={(l: LedgerEntry) => l.entryNo} columns={ledgerCols} rows={q.data?.list as LedgerEntry[]} loading={q.isLoading} />}
      {tab === "settlements" && <DataTable rowKey={(s: Settlement) => s.settleNo} columns={stlCols} rows={q.data?.list as Settlement[]} loading={q.isLoading} />}
      {tab === "withdrawals" && <DataTable rowKey={(w: Withdrawal) => w.withdrawNo} columns={wdCols} rows={q.data?.list as Withdrawal[]} loading={q.isLoading} />}
      {tab === "records" && <DataTable rowKey={(r: ShareRecord) => r.recordNo} columns={recordCols} rows={q.data?.list as ShareRecord[]} loading={q.isLoading} />}
      {tab === "reconcile" && <DataTable rowKey={(r: Reconcile) => r.batchNo} columns={reconcileCols} rows={q.data?.list as Reconcile[]} loading={q.isLoading} />}
      {tab === "invoices" && <DataTable rowKey={(i: Invoice) => i.invoiceNo} columns={invoiceCols} rows={q.data?.list as Invoice[]} loading={q.isLoading} />}
      {q.data && <Pagination page={page} size={SIZE} total={q.data.total} onPage={setPage} />}

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
        titleNew="新增发票"
        titleEdit={`编辑发票 ${invoiceForm?.invoiceNo ?? ""}`}
        isEdit={!!invoiceForm?.invoiceNo}
        fields={INVOICE_FIELDS}
        value={(invoiceForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setInvoiceForm(v as Partial<Invoice>)}
        onSubmit={() => invoiceForm && saveInvoice.mutate(invoiceForm)}
        submitting={saveInvoice.isPending}
      />
    </div>
  );
}

export default function FinancePage() {
  return <Suspense fallback={null}><FinanceInner /></Suspense>;
}
