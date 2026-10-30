"use client";

// 库存对账（对接明细 F07）：本地业务 vs 库存服务。只报差异与处置建议，不改任何一边 —— 哪边对要人判断。
// 点了才跑（全量核对要逐仓读库存服务）；选了仓库只核这个仓的在仓部分。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { InventoryReconDiff, InventoryReconDiffKind } from "@/lib/types";
import { fmtTime } from "@/lib/utils";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/input";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { activeWarehouses } from "./warehouses";

const KIND: StatusMap<InventoryReconDiffKind> = {
  MISSING_REMOTE: { label: "库存服务没有", tone: "danger" }, WRONG_LOCATION: { label: "仓不一致", tone: "warning" },
  EXTRA_REMOTE: { label: "库存服务多出", tone: "warning" }, POOL_MISSING: { label: "出库池没有", tone: "danger" },
  POOL_EXTRA: { label: "出库池多出", tone: "warning" }, POOL_MERCHANT: { label: "商家不一致", tone: "warning" },
  CLAIM_STATE: { label: "签收状态不同", tone: "info" },
};

export function InventoryRecon() {
  const [warehouseNo, setWarehouseNo] = useState("");
  const [run, setRun] = useState(0);
  const whQ = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses() });
  const q = useQuery({
    queryKey: ["inventory-recon", warehouseNo, run], enabled: run > 0,
    queryFn: () => api.runInventoryReconciliation({ warehouseNo: warehouseNo || undefined }),
  });
  const cols: Column<InventoryReconDiff>[] = [
    { header: "差异", cell: (d) => <StatusBadge map={KIND} value={d.kind} /> },
    { header: "设备", cell: (d) => <span className="font-mono">{d.itemNo}</span> },
    { header: "本地", cell: (d) => d.local },
    { header: "库存服务", cell: (d) => d.remote },
    { header: "处置建议", cell: (d) => <span className="txt-caption">{d.hint}</span> },
  ];
  return (
    <div className="mt-6">
      <div className="mb-1 txt-strong">对账</div>
      <p className="mb-3 txt-caption text-muted-foreground">
        核对本地与库存服务：在仓的设备、已出库给商家的柜。只报差异，不改任何一边。
      </p>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Select aria-label="对账仓库" value={warehouseNo} onChange={(e) => setWarehouseNo(e.target.value)} className="w-56">
          <option value="">全部（在仓 + 出库池）</option>
          {activeWarehouses(whQ.data).map((w) => <option key={w.warehouseNo} value={w.warehouseNo}>{w.name}（只核在仓）</option>)}
        </Select>
        <Button size="sm" disabled={q.isFetching} onClick={() => setRun((n) => n + 1)}>运行对账</Button>
        {q.data && (
          <span className="txt-caption text-muted-foreground">
            {fmtTime(q.data.asOf)} · 在仓核对 {q.data.inWarehouseChecked} 台 · 出库池核对 {q.data.poolChecked} 台 · 差异 {q.data.total} 条
            {q.data.total > q.data.diffs.length && `（只列前 ${q.data.diffs.length} 条，请按仓库分别核）`}
          </span>
        )}
      </div>
      {run > 0 && (
        <DataTable rowKey={(d: InventoryReconDiff) => d.kind + d.itemNo} columns={cols} rows={q.data?.diffs} loading={q.isFetching && !q.data}
          error={q.error} onRetry={q.refetch} empty="两边一致" />
      )}
    </div>
  );
}
