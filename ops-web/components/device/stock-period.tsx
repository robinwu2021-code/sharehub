"use client";

// 期间报表（S6）：按仓与设备类型的期初 / 入 / 出 / 期末。本地引擎由当前在仓数与履历倒推（履历自 V196 起）。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { StockPeriodRow } from "@/lib/types";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input } from "@/components/ui/input";
import { DataTable, type Column } from "@/components/ui/data-table";

const ITEM_LABEL: Record<string, string> = { CABINET: "机柜", POWERBANK: "充电宝" };
const iso = (d: Date) => d.toISOString().slice(0, 10);
const reasons = (m: Record<string, number>) => Object.entries(m).map(([k, v]) => `${k} ${v}`).join(" · ") || "-";

export function StockPeriodTable() {
  const today = new Date();
  const [from, setFrom] = useState(iso(new Date(today.getFullYear(), today.getMonth(), 1)));
  const [to, setTo] = useState(iso(today));
  const [warehouseNo, setWarehouseNo] = useState("");
  const whQ = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses() });
  const whOptions = (whQ.data ?? []).map((w) => ({ value: w.warehouseNo, label: w.name }));
  const whName = (no: string | null) => (no ? whQ.data?.find((w) => w.warehouseNo === no)?.name ?? no : "-");
  const q = useQuery({
    queryKey: ["stock-period", from, to, warehouseNo],
    queryFn: () => api.getStockPeriodSummary({ from, to, warehouseNo: warehouseNo || undefined }),
    enabled: !!from && !!to && from <= to,
  });
  const cols: Column<StockPeriodRow>[] = [
    { header: "仓库", cell: (r) => whName(r.warehouseNo) },
    { header: "类型", cell: (r) => (r.itemType ? ITEM_LABEL[r.itemType] ?? r.itemType : "-") },
    { header: "期初", className: "text-right tabular-nums", cell: (r) => r.opening },
    { header: "入", className: "text-right tabular-nums", cell: (r) => r.inQty },
    { header: "出", className: "text-right tabular-nums", cell: (r) => r.outQty },
    { header: "期末", className: "text-right tabular-nums", cell: (r) => <span className="txt-strong">{r.closing}</span> },
    { header: "入（按原因）", cell: (r) => <span className="txt-caption text-muted-foreground">{reasons(r.inByReason)}</span> },
    { header: "出（按原因）", cell: (r) => <span className="txt-caption text-muted-foreground">{reasons(r.outByReason)}</span> },
  ];
  return (
    <div>
      <Toolbar>
        <Input type="date" aria-label="开始日期" className="w-40" value={from} onChange={(e) => setFrom(e.target.value)} />
        <Input type="date" aria-label="结束日期" className="w-40" value={to} onChange={(e) => setTo(e.target.value)} />
        <FilterSelect aria-label="仓库" value={warehouseNo} onChange={setWarehouseNo} options={whOptions} allLabel="全部仓库" />
      </Toolbar>
      <DataTable rowKey={(r: StockPeriodRow) => `${r.warehouseNo}|${r.itemType}`} columns={cols} rows={q.data}
        loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="该期间没有进出" />
    </div>
  );
}
