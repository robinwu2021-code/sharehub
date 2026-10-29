"use client";

// 物料库存（库存管理 › 库存账，V209）：仓 × 物料（备件、耗材、其他整机）的在手与安全库存。
// 机柜、充电宝的余额在「库存余额」（设备流程的账）。下限 0 = 不预警；写码 device:inventory:warehouse。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PAGE_SIZE } from "@/lib/constants";
import type { MaterialBalance, MaterialCategory, MaterialDoc } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import { exportCsv } from "@/lib/export-csv";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Input, Select } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { CATEGORY, PURPOSE_LABEL } from "@/components/inventory/material-maps";
import { Notice } from "@/components/ui/notice";

/** 抽屉里「最近单据」的条数。 */
const RECENT_DOCS = PAGE_SIZE * 2;
const LOW: StatusMap<"LOW"> = { LOW: { label: "低于安全库存", tone: "warning" } };
const keyOf = (b: MaterialBalance) => `${b.warehouseNo}:${b.materialNo}`;

export function MaterialStockTab() {
  const qc = useQueryClient();
  const canWrite = useCan()("device:inventory:warehouse");
  const [warehouseNo, setWarehouseNo] = useState("");
  const [category, setCategory] = useState("");
  const [lowOnly, setLowOnly] = useState(false);
  const [keyword, setKeyword] = useState("");
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [open, setOpen] = useState<MaterialBalance | null>(null);

  const whQ = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses() });
  const engineQ = useQuery({ queryKey: ["inventory-engine"], queryFn: () => api.getInventoryEngine() });
  const service = engineQ.data?.engine === "SERVICE";
  const q = useQuery({
    queryKey: ["material-balances", warehouseNo, category, lowOnly],
    enabled: service,
    queryFn: () => api.listMaterialBalances({ warehouseNo: warehouseNo || undefined, lowOnly: lowOnly || undefined,
      category: (category || undefined) as MaterialCategory | undefined }),
  });
  const save = useMutation({
    mutationFn: (b: MaterialBalance) => api.saveMaterialMinQty({ warehouseNo: b.warehouseNo, materialNo: b.materialNo, minQty: Number(draft[keyOf(b)]) }),
    onSuccess: (b) => {
      qc.invalidateQueries({ queryKey: ["material-balances"] });
      setDraft((d) => { const n = { ...d }; delete n[keyOf(b)]; return n; });
      notify.success("已保存");
    },
  });

  const kw = keyword.trim().toLowerCase();
  const rows = (q.data ?? []).filter((b) => !kw || b.materialNo.toLowerCase().includes(kw) || b.materialName.toLowerCase().includes(kw));
  const cols: Column<MaterialBalance>[] = [
    { header: "仓库", className: "whitespace-nowrap", cell: (b) => b.warehouseName },
    { header: "物料", cell: (b) => (
      <button type="button" className="min-w-0 text-left hover:underline" onClick={() => setOpen(b)}>
        <div className="truncate">{b.materialName}</div>
        <div className="truncate font-mono txt-caption text-muted-foreground">{b.materialNo}</div>
      </button>
    ) },
    { header: "大类", className: "whitespace-nowrap", cell: (b) => <StatusBadge map={CATEGORY} value={b.category} /> },
    { header: "在手", className: "text-right tabular-nums whitespace-nowrap", cell: (b) => `${b.onHand} ${b.uom}` },
    { header: "安全库存", className: "w-32", cell: (b) => (canWrite
      ? <Input type="number" min={0} aria-label={`${b.materialName}安全库存`} value={draft[keyOf(b)] ?? String(b.minQty)}
          onChange={(e) => setDraft({ ...draft, [keyOf(b)]: e.target.value })} />
      : <span className="tabular-nums">{b.minQty || "不预警"}</span>) },
    { header: "", className: "whitespace-nowrap", cell: (b) => (
      <div className="flex items-center gap-2">
        {b.low && <StatusBadge map={LOW} value="LOW" />}
        {canWrite && draft[keyOf(b)] != null && draft[keyOf(b)] !== String(b.minQty) && (
          <Button size="sm" variant="outline" disabled={save.isPending} onClick={() => save.mutate(b)}>保存</Button>
        )}
      </div>
    ) },
  ];

  const onExport = () => exportCsv<MaterialBalance>("物料库存", [
    { header: "仓库", value: (b) => b.warehouseName },
    { header: "物料号", value: (b) => b.materialNo },
    { header: "物料", value: (b) => b.materialName },
    { header: "大类", value: (b) => CATEGORY[b.category].label },
    { header: "在手", value: (b) => b.onHand },
    { header: "单位", value: (b) => b.uom },
    { header: "安全库存", value: (b) => b.minQty },
    { header: "序列号", value: (b) => b.serialNos.join(" ") },
  ], rows);

  if (engineQ.data && !service) return <Notice>物料的账记在独立进销存（库存服务）：切到独立进销存引擎（库存设置 › 库存引擎 = SERVICE）后可用。物料档案不受影响，可先建档。</Notice>;

  return (
    <div>
      <Toolbar search={keyword} onSearch={setKeyword} searchPlaceholder="物料号 / 名称" onExport={rows.length ? onExport : undefined}>
        <Select aria-label="仓库" className="w-40" value={warehouseNo} onChange={(e) => setWarehouseNo(e.target.value)}>
          <option value="">全部仓库</option>
          {(whQ.data ?? []).filter((w) => !w.archivedAt).map((w) => <option key={w.warehouseNo} value={w.warehouseNo}>{w.name}</option>)}
        </Select>
        <FilterSelect aria-label="大类" value={category} onChange={setCategory} options={CATEGORY} allLabel="全部大类" />
        <label className="inline-flex h-9 cursor-pointer select-none items-center gap-2 txt-body">
          <input type="checkbox" className="size-4 accent-[var(--primary)]" checked={lowOnly} onChange={(e) => setLowOnly(e.target.checked)} />
          只看低于安全库存
        </label>
      </Toolbar>
      <DataTable rowKey={keyOf} columns={cols} rows={q.data ? rows : undefined} loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty={lowOnly ? "没有低于安全库存的物料。" : "还没有物料库存。先在「物料档案」建档，再用「物料单据」入库。"} />
      <BalanceDrawer balance={open} onClose={() => setOpen(null)} />
    </div>
  );
}

/** 一行的明细：逐件物料列序列号；最近单据（本仓、本物料）。 */
function BalanceDrawer({ balance, onClose }: { balance: MaterialBalance | null; onClose: () => void }) {
  const q = useQuery({
    queryKey: ["material-docs", "of", balance?.warehouseNo, balance?.materialNo], enabled: !!balance,
    queryFn: () => api.listMaterialDocs({ page: 1, size: RECENT_DOCS, warehouseNo: balance!.warehouseNo, materialNo: balance!.materialNo }),
  });
  const cols: Column<MaterialDoc>[] = [
    { header: "单号", cell: (d) => <span className="font-mono">{d.docNo}</span> },
    { header: "用途", className: "whitespace-nowrap", cell: (d) => PURPOSE_LABEL[d.purpose] },
    { header: "数量", className: "text-right tabular-nums", cell: (d) => d.lines.filter((l) => l.materialNo === balance?.materialNo).reduce((a, l) => a + l.qty, 0) },
    { header: "时间", className: "whitespace-nowrap", cell: (d) => (d.createdAt ? fmtTime(d.createdAt) : "-") },
  ];
  return (
    <Drawer open={!!balance} onOpenChange={(o) => !o && onClose()} width="w-[640px]"
      title={balance ? `${balance.materialName} · ${balance.warehouseName}` : ""} desc={balance ? `在手 ${balance.onHand} ${balance.uom}` : undefined}>
      {balance?.trackingMode === "SERIAL" && (
        <Field label={`序列号（${balance.serialNos.length}）`}>
          {balance.serialNos.length
            ? <div className="font-mono txt-caption">{balance.serialNos.join("、")}</div>
            : <span className="text-muted-foreground">无</span>}
        </Field>
      )}
      <Field label="最近单据">
        <DataTable rowKey={(d: MaterialDoc) => d.docNo} columns={cols} rows={q.data?.list} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="无" />
      </Field>
    </Drawer>
  );
}
