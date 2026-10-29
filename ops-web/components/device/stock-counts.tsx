"use client";

// 盘点（设备管理 › 仓储与出库，S4 / V199）：开单 → 扫码 → 提交 → 复核 → 过账。只在独立进销存引擎下可用。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import type { StockCount, StockCountLine, StockCountStatus } from "@/lib/types";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Notice } from "@/components/ui/notice";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { ReadOnlyNotice } from "@/components/read-only-notice";

const STATUS: StatusMap<StockCountStatus> = {
  OPEN: { label: "盘点中", tone: "warning" }, SUBMITTED: { label: "待复核", tone: "info" }, APPROVED: { label: "待过账", tone: "info" },
  POSTED: { label: "已过账", tone: "success" }, CANCELLED: { label: "已作废", tone: "muted" }, UNKNOWN: { label: "未知", tone: "outline" },
};
const ITEM_LABEL: Record<string, string> = { CABINET: "机柜", POWERBANK: "充电宝" };

export function StockCountsTab() {
  const qc = useQueryClient();
  const canWrite = useCan()("device:inventory:stocktake");
  const [warehouseNo, setWarehouseNo] = useState("");
  const [status, setStatus] = useState("");
  const [openFor, setOpenFor] = useState<string | null>(null);
  const [countNo, setCountNo] = useState<string | null>(null);
  const engineQ = useQuery({ queryKey: ["inventory-engine"], queryFn: () => api.getInventoryEngine() });
  const service = engineQ.data?.engine === "SERVICE";
  const whQ = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses() });
  const whOptions = (whQ.data ?? []).map((w) => ({ value: w.warehouseNo, label: w.name }));
  const whName = (no: string | null) => (no ? whQ.data?.find((w) => w.warehouseNo === no)?.name ?? no : "-");
  const q = useQuery({
    queryKey: ["stock-counts", warehouseNo, status],
    queryFn: () => api.listStockCounts({ warehouseNo: warehouseNo || undefined, status: status || undefined }),
    enabled: service,
  });
  const open = useMutation({
    mutationFn: (wh: string) => api.openStockCount(wh),
    onSuccess: (d) => {
      notify.success(`已开盘点单 ${d.count.countNo}`);
      qc.invalidateQueries({ queryKey: ["stock-counts"] });
      setOpenFor(null);
      setCountNo(d.count.countNo);
    },
  });

  const cols: Column<StockCount>[] = [
    { header: "单号", cell: (c) => <button type="button" className="font-mono text-primary hover:underline" onClick={() => setCountNo(c.countNo)}>{c.countNo}</button> },
    { header: "仓库", cell: (c) => whName(c.warehouseNo) },
    { header: "开单", className: "whitespace-nowrap", cell: (c) => <span className="text-muted-foreground">{c.openedAt ? fmtTime(c.openedAt) : "-"}</span> },
    { header: "状态", className: "whitespace-nowrap", cell: (c) => <StatusBadge map={STATUS} value={c.status} /> },
  ];

  if (engineQ.data && !service) return <Notice>盘点需切到独立进销存引擎（系统参数 inv.engine = SERVICE）</Notice>;

  return (
    <div>
      {!canWrite && <ReadOnlyNotice what="盘点" perm="device:inventory:stocktake" className="mb-3" />}
      <Toolbar onAdd={() => setOpenFor(whQ.data?.find((w) => w.isDefault)?.warehouseNo ?? "")} addLabel="开盘点" canAdd={canWrite}>
        <FilterSelect aria-label="仓库" value={warehouseNo} onChange={setWarehouseNo} options={whOptions} allLabel="全部仓库" />
        <FilterSelect aria-label="状态" value={status} onChange={setStatus} options={STATUS} allLabel="全部状态" />
      </Toolbar>
      <DataTable rowKey={(c: StockCount) => c.countNo} columns={cols} rows={q.data} loading={q.isLoading} error={q.error}
        onRetry={q.refetch} empty={warehouseNo || status ? "没有匹配的盘点单" : "暂无盘点单"} />
      <Drawer open={openFor !== null} onOpenChange={(o) => !o && setOpenFor(null)} title="开盘点"
        footer={<Button disabled={!openFor || open.isPending} onClick={() => openFor && open.mutate(openFor)}>开单</Button>}>
        <Field label="仓库">
          <Select aria-label="仓库" value={openFor ?? ""} onChange={(e) => setOpenFor(e.target.value)}>
            <option value="">请选择仓库</option>
            {whOptions.map((w) => <option key={w.value} value={w.value}>{w.label}</option>)}
          </Select>
        </Field>
      </Drawer>
      <CountDrawer countNo={countNo} onClose={() => setCountNo(null)} />
    </div>
  );
}

function CountDrawer({ countNo, onClose }: { countNo: string | null; onClose: () => void }) {
  const qc = useQueryClient();
  const canWrite = useCan()("device:inventory:stocktake");
  const [scan, setScan] = useState("");
  const [note, setNote] = useState("");
  const q = useQuery({ queryKey: ["stock-count", countNo], queryFn: () => api.getStockCount(countNo!), enabled: !!countNo });
  const d = q.data;
  const s = d?.count.status;
  const act = useMutation({
    mutationFn: (step: "scan" | "submit" | "approve" | "reject" | "post" | "cancel") => {
      const no = countNo!;
      switch (step) {
        case "scan": return api.scanStockCount(no, scan.split(/[\s,，]+/).map((x) => x.trim()).filter(Boolean));
        case "submit": return api.submitStockCount(no);
        case "approve": return api.approveStockCount(no, note || undefined);
        case "reject": return api.rejectStockCount(no, note);
        case "post": return api.postStockCount(no);
        default: return api.cancelStockCount(no);
      }
    },
    onSuccess: (r) => {
      qc.setQueryData(["stock-count", countNo], r);
      qc.invalidateQueries({ queryKey: ["stock-counts"] });
      setScan("");
      setNote("");
    },
  });

  const lineCols: Column<StockCountLine>[] = [
    { header: "类型", cell: (l) => (l.itemType ? ITEM_LABEL[l.itemType] ?? l.itemType : "-") },
    { header: "账面", className: "text-right tabular-nums", cell: (l) => l.bookQty },
    { header: "实盘", className: "text-right tabular-nums", cell: (l) => l.countedQty ?? "-" },
    { header: "差异", className: "text-right tabular-nums", cell: (l) => l.diffQty ?? "-" },
  ];

  return (
    <Drawer open={!!countNo} onOpenChange={(o) => !o && onClose()} title={`盘点 ${countNo ?? ""}`}
      footer={canWrite && d && (
        <span className="inline-flex flex-wrap gap-1.5">
          {s === "OPEN" && <Button disabled={act.isPending} onClick={() => act.mutate("submit")}>提交</Button>}
          {s === "SUBMITTED" && <Button disabled={act.isPending} onClick={() => act.mutate("approve")}>复核通过</Button>}
          {s === "SUBMITTED" && <Button variant="outline" disabled={!note.trim() || act.isPending} onClick={() => act.mutate("reject")}>退回</Button>}
          {s === "APPROVED" && <Button disabled={act.isPending} onClick={() => act.mutate("post")}>过账</Button>}
          {(s === "OPEN" || s === "SUBMITTED" || s === "APPROVED") && (
            <Button variant="ghost" disabled={act.isPending} onClick={() => act.mutate("cancel")}>作废</Button>
          )}
        </span>
      )}>
      {d && (
        <div className="space-y-4">
          <StatusBadge map={STATUS} value={d.count.status} />
          <DataTable rowKey={(l: StockCountLine) => l.itemType ?? "-"} columns={lineCols} rows={d.lines}
            loading={q.isFetching && !d} error={q.error} onRetry={q.refetch} empty="无明细" />
          {canWrite && s === "OPEN" && (
            <Field label="扫码">
              <Textarea value={scan} onChange={setScan} placeholder="每行一个设备号" />
              <Button size="sm" className="mt-2" disabled={!scan.trim() || act.isPending} onClick={() => act.mutate("scan")}>记入</Button>
            </Field>
          )}
          {canWrite && s === "SUBMITTED" && (
            <Field label="复核意见（退回必填）"><Textarea value={note} onChange={setNote} /></Field>
          )}
          {d.missingSerials.length > 0 && (
            <Field label={`未盘到（${d.missingSerials.length}）`}>
              <div className="txt-caption font-mono">{d.missingSerials.join("、")}</div>
            </Field>
          )}
          {d.exceptions.length > 0 && (
            <Field label={`账外（${d.exceptions.length}）`}>
              <div className="txt-caption font-mono">{d.exceptions.map((x) => x.serialNo).join("、")}</div>
            </Field>
          )}
        </div>
      )}
    </Drawer>
  );
}
