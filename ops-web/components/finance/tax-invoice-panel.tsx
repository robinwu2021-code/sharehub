"use client";

// 租赁发票（C 端税务票据，V181，运营方原始需求 C01 / RQ-INV）。
// 与同页「B 端发票」不同：这里的票是系统在收款时自动开出、退款批准时自动冲红的，运营不能新建或改。
// 唯一动作是重试 —— 开票主体（系统设置 › 税率与发票 的法定名称 / 地址 / TRN）缺项时票被挡在 BLOCKED，
// 补齐后在这里重开。是否开票、何时开由系统参数 invoice.b2c.* 控制。
import * as React from "react";
import { useMutation, useQuery, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PagedTable } from "@/components/ui/paged-table";
import { type Column } from "@/components/ui/data-table";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Drawer, Field } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { DateInput } from "@/components/ui/date-input";
import { Notice } from "@/components/ui/notice";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { StateActions } from "@/components/state-actions";
import { useCan } from "@/lib/hooks/use-can";
import { usePaging } from "@/lib/hooks/use-paging";
import { notify } from "@/lib/notify";
import { money, fmtTime } from "@/lib/utils";
import type { TaxInvoice, TaxInvoiceKind, TaxInvoiceStatus } from "@/lib/types";

export const TAX_INV_STATUS: StatusMap<TaxInvoiceStatus> = {
  ISSUED: { label: "已开出", tone: "success" },
  BLOCKED: { label: "主体缺项未开", tone: "danger" },
};
const TAX_INV_KIND: StatusMap<TaxInvoiceKind> = {
  INVOICE: { label: "发票", tone: "default" },
  CREDIT_NOTE: { label: "贷项通知单", tone: "warning" },
};
const MISSING_LABEL: Record<string, string> = {
  issuerLegalName: "法定名称", issuerAddress: "注册地址", trn: "税号 TRN", taxSetting: "该国税务设置行",
};

/** ISSUER_MISSING:trn,issuerAddress → 「缺 税号 TRN、注册地址」；其它原因原样给出。 */
function reasonText(r: string | null) {
  if (!r) return "-";
  const m = /^ISSUER_MISSING:(.+)$/.exec(r);
  return m ? `开票主体缺 ${m[1].split(",").map((k) => MISSING_LABEL[k] ?? k).join("、")}` : r;
}

export function TaxInvoicePanel() {
  const qc = useQueryClient();
  const allow = useCan();
  const canRetry = allow("finance:invoice:issue");
  const paging = usePaging();
  const [orderNo, setOrderNo] = React.useState("");
  const [kind, setKind] = React.useState("");
  const [status, setStatus] = React.useState("");
  const [detail, setDetail] = React.useState<TaxInvoice | null>(null);
  // 导出区间（业务日，含两端）只作用于导出，不筛列表：列表按订单号/票种/状态看，导出按期间交给会计
  const [from, setFrom] = React.useState("");
  const [to, setTo] = React.useState("");
  // 导出与下载走后端（K8 / RQ-INV-06~08）；mutation 只为接上全局错误弹窗与处理中态
  const exportFile = useMutation({
    mutationFn: (format: "xlsx" | "pdf" | "zip") => api.exportTaxInvoices({
      format, orderNo: orderNo || undefined, kind: kind || undefined, status: status || undefined,
      from: from || undefined, to: to || undefined,
    }),
  });
  const pdf = useMutation({ mutationFn: (no: string) => api.downloadTaxInvoicePdf(no) });
  const q = useQuery({
    queryKey: ["tax-invoices", paging.page, paging.size, orderNo, kind, status],
    queryFn: () => api.listTaxInvoices({ page: paging.page, size: paging.size, orderNo: orderNo || undefined, kind: kind || undefined, status: status || undefined }),
    placeholderData: keepPreviousData,
  });
  const retry = useMutation({
    mutationFn: (no: string) => api.retryTaxInvoice(no),
    onSuccess: (t) => {
      if (t.status === "ISSUED") notify.success(`${t.invoiceNo} 已开出`);
      else notify.error(`${t.invoiceNo} 仍未开出：${reasonText(t.failReason)}。请先到 系统设置 › 税率与发票 补齐`);
      setDetail((cur) => (cur && cur.invoiceNo === t.invoiceNo ? t : cur));
      qc.invalidateQueries({ queryKey: ["tax-invoices"] });
    },
  });

  const cols: Column<TaxInvoice>[] = [
    {
      header: "票号",
      cell: (t) => (
        <button type="button" className="txt-strong tabular-nums underline-offset-2 hover:underline" onClick={() => setDetail(t)}>{t.invoiceNo}</button>
      ),
    },
    { header: "票种", cell: (t) => <StatusBadge map={TAX_INV_KIND} value={t.kind} className="whitespace-nowrap" /> },
    { header: "订单号", cell: (t) => <span className="tabular-nums text-muted-foreground">{t.orderNo}</span> },
    { header: "含税总额", cell: (t) => <span className="tabular-nums">{money(t.grossAmount, t.currency)}</span> },
    { header: "税额", cell: (t) => <span className="tabular-nums text-muted-foreground">{money(t.taxAmount, t.currency)}（{t.taxRatePercent}%）</span> },
    { header: "开票主体", cell: (t) => <span className="text-muted-foreground">{t.issuerLegalName ?? "-"}</span> },
    { header: "状态", cell: (t) => <StatusBadge map={TAX_INV_STATUS} value={t.status} className="whitespace-nowrap" /> },
    { header: "开出时间", cell: (t) => <span className="tabular-nums text-muted-foreground">{t.issuedAt ? fmtTime(t.issuedAt) : reasonText(t.failReason)}</span> },
    {
      header: "操作",
      cell: (t) => (
        <div className="flex items-center gap-1.5">
          {t.status === "BLOCKED" && (
            <StateActions actions={[{
              key: "retry", label: "重试开票", primary: true, perm: "finance:invoice:issue",
              blockedReason: retry.isPending ? "处理中…" : null,
              onRun: () => retry.mutate(t.invoiceNo),
            }]} />
          )}
          {t.status === "ISSUED" && (
            <Button size="sm" variant="outline" disabled={pdf.isPending} onClick={() => pdf.mutate(t.invoiceNo)}>PDF</Button>
          )}
          <Button size="sm" variant="outline" onClick={() => setDetail(t)}>详情</Button>
        </div>
      ),
    },
  ];

  return (
    <>
      <Notice>
        租赁发票由系统在收款时自动开出、退款批准时自动开贷项通知单冲红；开票主体与税率随票定格，改税务设置只影响之后的票。
        是否开票、何时开见系统参数「发票」组（invoice.b2c.*）。
      </Notice>
      {!canRetry && <ReadOnlyNotice what="租赁发票重试" perm="finance:invoice:issue" />}
      <Toolbar search={orderNo} onSearch={(v) => { setOrderNo(v); paging.reset(); }} searchPlaceholder="按订单号精确查">
        <FilterSelect value={kind} onChange={(v) => { setKind(v); paging.reset(); }} allLabel="全部票种" options={TAX_INV_KIND} />
        <FilterSelect value={status} onChange={(v) => { setStatus(v); paging.reset(); }} allLabel="全部状态" options={TAX_INV_STATUS} />
      </Toolbar>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <span className="txt-body text-muted-foreground">导出开票期间</span>
        <DateInput aria-label="导出起始日" value={from} onChange={(e) => setFrom(e.target.value)} className="w-40" />
        <span className="text-muted-foreground">~</span>
        <DateInput aria-label="导出截止日" value={to} onChange={(e) => setTo(e.target.value)} className="w-40" />
        <Button variant="outline" disabled={exportFile.isPending} onClick={() => exportFile.mutate("xlsx")}>登记表 Excel</Button>
        <Button variant="outline" disabled={exportFile.isPending} onClick={() => exportFile.mutate("pdf")}>登记表 PDF</Button>
        <Button variant="outline" disabled={exportFile.isPending} onClick={() => exportFile.mutate("zip")}>批量下载 PDF</Button>
        {exportFile.isPending && <span className="txt-body text-muted-foreground">生成中…</span>}
      </div>
      <PagedTable
        rowKey={(t: TaxInvoice) => t.invoiceNo}
        columns={cols}
        query={q}
        paging={paging}
        empty="暂无租赁发票——开关 invoice.b2c.enabled 关着时系统不开票；打开后每笔收款会自动出现在这里"
      />
      <Drawer open={!!detail} onOpenChange={(o) => !o && setDetail(null)} title={detail ? `${TAX_INV_KIND[detail.kind].label} ${detail.invoiceNo}` : ""}>
        {detail && (
          <>
            <Field label="状态"><StatusBadge map={TAX_INV_STATUS} value={detail.status} />{detail.status === "BLOCKED" && <span className="ms-2 text-destructive">{reasonText(detail.failReason)}</span>}</Field>
            {detail.refInvoiceNo && <Field label="冲红原票">{detail.refInvoiceNo}</Field>}
            <Field label="订单 / 收款 / 退款">{[detail.orderNo, detail.payNo, detail.refundNo].filter(Boolean).join(" · ")}</Field>
            <Field label="开票主体">{detail.issuerLegalName ?? "-"}<div className="text-muted-foreground">{detail.issuerAddress ?? "-"}</div></Field>
            <Field label="税号 TRN">{detail.issuerTrn ?? "-"}</Field>
            <Field label="金额">
              <span className="tabular-nums">
                净额 {money(detail.netAmount, detail.currency)} + 税 {money(detail.taxAmount, detail.currency)}（{detail.taxRatePercent}%，{detail.taxIncluded ? "价内税" : "价外税"}）= {money(detail.grossAmount, detail.currency)}
              </span>
            </Field>
            <Field label="票面描述">{detail.lineDesc ?? "-"}</Field>
            <Field label="开出时间">{detail.issuedAt ? fmtTime(detail.issuedAt) : "-"}</Field>
          </>
        )}
      </Drawer>
    </>
  );
}
