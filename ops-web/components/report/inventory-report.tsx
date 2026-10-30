"use client";

// 数据报表 › 库存报表（V206，report:inventory:read）：现算，期间默认本月。
// 一页四块：阶段分布 / 各仓 × 类型期间进出与安全库存 / 期间处置 / 期间采购金额。口径同库存模块各视图，不另起。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { InventoryPurchaseRow, InventoryReportRow, StockStage } from "@/lib/types";
import { Field } from "@/components/ui/drawer";
import { Input, Select } from "@/components/ui/input";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { STAGE_LABEL } from "@/components/inventory/stock-stages";
import { exportCsv } from "@/lib/export-csv";

const TYPE_LABEL = { CABINET: "机柜", POWERBANK: "充电宝" } as const;
const LOW_MARK: StatusMap<"LOW"> = { LOW: { label: "低于安全库存", tone: "warning" } };
const DISPOSAL_LABEL: Record<string, string> = {
  "SCRAP:BROKEN": "报废 · 损坏", "SCRAP:OBSOLETE": "报废 · 淘汰", "SCRAP:OTHER": "报废 · 其他", RETURN_SUPPLIER: "退供应商",
};
const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

export function InventoryReportTab() {
  const today = iso(new Date());
  const [from, setFrom] = useState(`${today.slice(0, 8)}01`);
  const [to, setTo] = useState(today);
  const [warehouseNo, setWarehouseNo] = useState("");
  const whQ = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses() });
  const q = useQuery({
    queryKey: ["report-inventory", from, to, warehouseNo],
    queryFn: () => api.getInventoryReport({ from, to, warehouseNo: warehouseNo || undefined }),
    enabled: !!from && !!to && from <= to,
  });
  const r = q.data;

  const stageCols: Column<StockStage>[] = [
    { header: "阶段", cell: (s) => STAGE_LABEL[s.stage] ?? s.stage },
    { header: "机柜", className: "text-right tabular-nums", cell: (s) => s.cabinets },
    { header: "充电宝", className: "text-right tabular-nums", cell: (s) => s.powerbanks },
  ];
  const rowCols: Column<InventoryReportRow>[] = [
    { header: "仓库", className: "whitespace-nowrap", cell: (x) => x.warehouseName },
    { header: "设备", className: "whitespace-nowrap", cell: (x) => TYPE_LABEL[x.itemType] },
    { header: "期初", className: "text-right tabular-nums", cell: (x) => x.opening },
    { header: "入", className: "text-right tabular-nums", cell: (x) => x.inQty },
    { header: "出", className: "text-right tabular-nums", cell: (x) => x.outQty },
    { header: "期末", className: "text-right tabular-nums", cell: (x) => x.closing ?? "-" },
    { header: "当前可用", className: "text-right tabular-nums", cell: (x) => x.available ?? "-" },
    { header: "安全库存", className: "text-right tabular-nums", cell: (x) => x.minQty || "-" },
    { header: "", className: "whitespace-nowrap", cell: (x) => (x.low ? <StatusBadge map={LOW_MARK} value="LOW" /> : null) },
  ];
  const purchaseCols: Column<InventoryPurchaseRow>[] = [
    { header: "币种", cell: (p) => p.currency },
    { header: "台数", className: "text-right tabular-nums", cell: (p) => p.qty },
    { header: "金额", className: "text-right tabular-nums", cell: (p) => p.amount.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) },
  ];
  const disposalRows = Object.entries(r?.disposals ?? {}).map(([k, n]) => ({ k, n }));

  const onExport = () => exportCsv<InventoryReportRow>(`库存报表_${from}_${to}`, [
    { header: "仓库", value: (x) => x.warehouseName },
    { header: "设备", value: (x) => TYPE_LABEL[x.itemType] },
    { header: "期初", value: (x) => x.opening },
    { header: "入", value: (x) => x.inQty },
    { header: "出", value: (x) => x.outQty },
    { header: "期末", value: (x) => x.closing ?? "" },
    { header: "当前可用", value: (x) => x.available ?? "" },
    { header: "安全库存", value: (x) => x.minQty },
  ], r?.rows ?? []);

  return (
    <div className="space-y-5">
      <Toolbar onExport={r?.rows.length ? onExport : undefined}>
        <Input type="date" aria-label="开始日期" className="w-40" value={from} onChange={(e) => setFrom(e.target.value)} />
        <Input type="date" aria-label="结束日期" className="w-40" value={to} onChange={(e) => setTo(e.target.value)} />
        <Select aria-label="仓库" className="w-40" value={warehouseNo} onChange={(e) => setWarehouseNo(e.target.value)}>
          <option value="">全部仓库</option>
          {(whQ.data ?? []).map((w) => <option key={w.warehouseNo} value={w.warehouseNo}>{w.name}</option>)}
        </Select>
      </Toolbar>
      <Field label="各仓进出（期初 + 入 − 出 = 期末）">
        <DataTable rowKey={(x: InventoryReportRow) => `${x.warehouseNo}:${x.itemType}`} columns={rowCols} rows={r?.rows}
          loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="期间内没有仓库数据" />
      </Field>
      <div className="grid gap-5 md:grid-cols-3">
        <Field label="阶段分布（当前）">
          <DataTable rowKey={(s: StockStage) => s.stage} columns={stageCols} rows={r?.stages}
            loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="无" />
        </Field>
        <Field label="期间处置出库">
          <DataTable rowKey={(d: { k: string }) => d.k} rows={r ? disposalRows : undefined}
            columns={[{ header: "类型", cell: (d) => DISPOSAL_LABEL[d.k] ?? d.k }, { header: "台数", className: "text-right tabular-nums", cell: (d) => d.n }]}
            loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="期间内没有处置" />
        </Field>
        <Field label="期间采购金额（入库单单价合计）">
          <DataTable rowKey={(p: InventoryPurchaseRow) => p.currency} columns={purchaseCols} rows={r?.purchases}
            loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="期间内没有带单价的入库单" />
        </Field>
      </div>
    </div>
  );
}
