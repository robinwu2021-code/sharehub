"use client";

// 工单成本汇总（后端 costs，G4）：完工工单的配件 + 人工金额，按承担方聚合。
// 承担方由后端在完工时判：**代理运维的单记代理**（代理自己的运维成本），其余记站点（站点效益要扣掉它）。
//
// 不分页：一行是一个承担方（站点 / 代理），行数以站点数为上界，一屏看完才比得出谁花得多。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge } from "@/components/ui/status-badge";
import { DateInput } from "@/components/ui/date-input";
import { RefLink } from "@/components/ref-link";
import { exportCsv } from "@/lib/export-csv";
import { money } from "@/lib/utils";
import type { CostRow } from "@/lib/types";
import { BEARER } from "./wo-meta";

export function WoCostsView() {
  const [from, setFrom] = React.useState("");
  const [to, setTo] = React.useState("");
  const [bearerType, setBearerType] = React.useState("");
  const badRange = !!from && !!to && from >= to;
  const q = useQuery({
    queryKey: ["wo-costs", from, to, bearerType],
    queryFn: () => api.listWoCosts({ from: from || undefined, to: to || undefined, bearerType: bearerType || undefined }),
    enabled: !badRange,
  });
  const rows = q.data ?? [];
  const sum = rows.reduce((n, r) => n + (r.total ?? 0), 0);
  const orders = rows.reduce((n, r) => n + r.orders, 0);
  const currency = rows.find((r) => r.currency)?.currency ?? undefined;

  const cols: Column<CostRow>[] = [
    { header: "承担方", cell: (r) => <StatusBadge map={BEARER} value={r.bearerType} /> },
    {
      header: "站点 / 代理",
      cell: (r) => r.bearerNo
        ? <RefLink kind={r.bearerType === "AGENT" ? "agent" : "site"} no={r.bearerNo} />
        : <span className="text-muted-foreground" title="完工时工单没挂站点（手工开单未选机柜）">未归属</span>,
    },
    { header: "工单数", className: "text-end", cell: (r) => <span className="tabular-nums">{r.orders}</span> },
    { header: "成本合计", className: "text-end", cell: (r) => <span className="tabular-nums">{money(r.total, r.currency ?? undefined)}</span> },
    {
      header: "单均", className: "text-end",
      cell: (r) => <span className="tabular-nums text-muted-foreground">{r.orders ? money(r.total / r.orders, r.currency ?? undefined) : "-"}</span>,
    },
  ];

  return (
    <>
      <Toolbar
        onExport={() => exportCsv<CostRow>("工单成本汇总", [
          { header: "承担方", value: (r) => BEARER[r.bearerType]?.label ?? r.bearerType },
          { header: "站点/代理", value: (r) => r.bearerNo ?? "" },
          { header: "工单数", value: (r) => r.orders },
          { header: "成本合计", value: (r) => r.total },
          { header: "币种", value: (r) => r.currency ?? "" },
        ], rows)}
      >
        <label className="flex items-center gap-1.5 txt-caption text-muted-foreground">
          开单自
          <DateInput className="w-40" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="开单日期起" />
        </label>
        <label className="flex items-center gap-1.5 txt-caption text-muted-foreground">
          至（不含）
          <DateInput className="w-40" value={to} onChange={(e) => setTo(e.target.value)} aria-label="开单日期止（不含）" />
        </label>
        <FilterSelect value={bearerType} onChange={setBearerType} allLabel="全部承担方" options={BEARER} aria-label="按承担方筛选" />
      </Toolbar>
      {badRange && <p className="mb-3 txt-caption text-destructive-ink">结束日期要晚于开始日期（区间左闭右开）。</p>}
      <DataTable
        rowKey={(r: CostRow) => `${r.bearerType}|${r.bearerNo ?? "-"}`}
        columns={cols}
        rows={badRange ? [] : q.data}
        loading={q.isLoading && !badRange}
        error={q.error}
        onRetry={() => q.refetch()}
        empty="这个区间没有记了成本的完工单——成本在完工时填（配件 / 人工金额），没填的单不进汇总；换个日期区间试试。"
      />
      {rows.length > 0 && (
        <p className="mt-3 txt-caption text-muted-foreground tabular-nums">
          合计 {orders} 张工单 · {money(sum, currency)}。只统计已完工（待验收 / 已关单）的单；按开单日期落区间。
        </p>
      )}
    </>
  );
}
