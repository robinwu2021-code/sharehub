"use client";

// 跨期应付（运营方原始需求 SHR-05，第 6 批）：收款方 × 期间 的 应得 / 已出结算单 / 已付 / 未付。
// 回答「这个商家我们还欠多少、欠在哪几个月」—— 分润统计只看单期，看不出跨期累积的欠款。
import * as React from "react";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PagedTable } from "@/components/ui/paged-table";
import { type Column } from "@/components/ui/data-table";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input } from "@/components/ui/input";
import { usePaging } from "@/lib/hooks/use-paging";
import { money } from "@/lib/utils";
import type { PayableRow } from "@/lib/types";

const PAYEE_TYPES = [{ value: "AGENT", label: "商家" }, { value: "VENUE", label: "场地方" }];
const SCOPE = [{ value: "OUTSTANDING", label: "只看未付" }];

export function PayablesPanel() {
  const paging = usePaging();
  const [payeeNo, setPayeeNo] = React.useState("");
  const [payeeType, setPayeeType] = React.useState("");
  const [fromPeriod, setFromPeriod] = React.useState("");
  const [toPeriod, setToPeriod] = React.useState("");
  const [scope, setScope] = React.useState("OUTSTANDING");
  const q = useQuery({
    queryKey: ["payables", paging.page, paging.size, payeeNo, payeeType, fromPeriod, toPeriod, scope],
    queryFn: () => api.listPayables({
      page: paging.page, size: paging.size, payeeNo: payeeNo || undefined, payeeType: payeeType || undefined,
      fromPeriod: fromPeriod || undefined, toPeriod: toPeriod || undefined, onlyOutstanding: scope === "OUTSTANDING",
    }),
    placeholderData: keepPreviousData,
  });
  const cols: Column<PayableRow>[] = [
    { header: "期间", cell: (r) => <span className="tabular-nums">{r.period}</span> },
    { header: "收款方", cell: (r) => <span>{r.payeeName ?? r.payeeNo}<span className="ms-1 text-muted-foreground tabular-nums">{r.payeeNo}</span></span> },
    { header: "应得", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.earned, r.currency)}</span> },
    { header: "已出结算单", className: "text-right", cell: (r) => <span className="tabular-nums text-muted-foreground">{money(r.settled, r.currency)}</span> },
    { header: "已付", className: "text-right", cell: (r) => <span className="tabular-nums text-muted-foreground">{money(r.paid, r.currency)}</span> },
    { header: "未付", className: "text-right", cell: (r) => <span className="txt-strong tabular-nums">{money(r.outstanding, r.currency)}</span> },
  ];
  return (
    <section className="mt-8">
      <h2 className="txt-title mb-3">跨期应付</h2>
      <Toolbar search={payeeNo} onSearch={(v) => { setPayeeNo(v); paging.reset(); }} searchPlaceholder="收款方编号（精确）">
        <FilterSelect value={payeeType} onChange={(v) => { setPayeeType(v); paging.reset(); }} allLabel="全部收款方" options={PAYEE_TYPES} />
        <FilterSelect value={scope} onChange={(v) => { setScope(v); paging.reset(); }} allLabel="含已结清" options={SCOPE} />
        <Input aria-label="起始期间" className="w-28" placeholder="2026-01" value={fromPeriod} onChange={(e) => { setFromPeriod(e.target.value); paging.reset(); }} />
        <Input aria-label="截止期间" className="w-28" placeholder="2026-12" value={toPeriod} onChange={(e) => { setToPeriod(e.target.value); paging.reset(); }} />
      </Toolbar>
      <PagedTable
        rowKey={(r: PayableRow) => `${r.payeeType}-${r.payeeNo}-${r.period}`}
        columns={cols}
        query={q}
        paging={paging}
        empty="没有未付的应付——各期分润都已出结算单并付清；切到「含已结清」可看全部期间"
      />
    </section>
  );
}
