"use client";

// 处置出库（设备管理 › 仓储与出库，V203）：在库设备报废 / 退供应商，建单即过账。
// 待检单上的不合格设备仍在待检单里处置；这里管的是已入库、后来坏了或要整批退回的。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import { activeWarehouses } from "@/components/device/warehouses";
import { parseLines } from "@/components/device/intakes";
import { useCan } from "@/lib/hooks/use-can";
import { usePaging } from "@/lib/hooks/use-paging";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import type { Disposal, DisposalItem, DisposalKind, DisposalReason } from "@/lib/types";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { PagedTable } from "@/components/ui/paged-table";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { ReadOnlyNotice } from "@/components/read-only-notice";

const KIND: StatusMap<DisposalKind> = { SCRAP: { label: "报废", tone: "danger" }, RETURN_SUPPLIER: { label: "退供应商", tone: "warning" } };
const REASON: Record<DisposalReason, string> = { BROKEN: "损坏", OBSOLETE: "淘汰", OTHER: "其他" };
const TYPE_LABEL = { CABINET: "机柜", POWERBANK: "充电宝" } as const;

export function DisposalsTab() {
  const qc = useQueryClient();
  const canWrite = useCan()("device:inventory:transfer");
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [kind, setKind] = useState("");
  const [form, setForm] = useState<Record<string, unknown> | null>(null);
  const [openNo, setOpenNo] = useState<string | null>(null);

  const whQ = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses() });
  const whOptions = activeWarehouses(whQ.data).map((w) => ({ value: w.warehouseNo, label: w.name }));
  const spQ = useQuery({ queryKey: ["suppliers", "active"], queryFn: () => api.listSuppliers({ page: 1, size: UNPAGED_SIZE }) });
  const spOptions = (spQ.data?.list ?? []).map((s) => ({ value: s.supplierNo, label: s.name }));
  const q = useQuery({
    queryKey: ["disposals", paging.page, paging.size, keyword, kind],
    queryFn: () => api.listDisposals({ page: paging.page, size: paging.size, keyword, kind: (kind || undefined) as DisposalKind | undefined }),
    placeholderData: keepPreviousData,
  });
  const detailQ = useQuery({ queryKey: ["disposal", openNo], queryFn: () => api.getDisposal(openNo!), enabled: !!openNo });

  const create = useMutation({
    mutationFn: (v: Record<string, unknown>) => {
      const k = v.kind as DisposalKind;
      return api.createDisposal({
        kind: k, warehouseNo: String(v.warehouseNo ?? ""), note: String(v.note ?? ""),
        supplierNo: k === "RETURN_SUPPLIER" ? String(v.supplierNo ?? "") || null : null,
        reasonCode: k === "SCRAP" ? (String(v.reasonCode ?? "") || null) as DisposalReason | null : null,
        cabinetNos: parseLines(v.cabinetNos).nos, powerbankNos: parseLines(v.powerbankNos).nos,
      });
    },
    onSuccess: (d) => {
      notify.success(`已出库 ${d.disposal.disposalNo}（${d.disposal.itemCount} 台）`);
      qc.invalidateQueries({ queryKey: ["disposals"] });
      setForm(null);
      setOpenNo(d.disposal.disposalNo);
    },
  });

  const scrap = form?.kind !== "RETURN_SUPPLIER";
  /**
   * @form POST /api/ops/inventory/disposals
   */
  const FIELDS: FieldDef[] = [
    { key: "kind", label: "处置类型", type: "select", required: true, options: [{ value: "SCRAP", label: "报废" }, { value: "RETURN_SUPPLIER", label: "退供应商" }] },
    { key: "warehouseNo", label: "出库仓", type: "select", required: true, options: whOptions },
    scrap
      ? { key: "reasonCode", label: "报废原因", type: "select", required: true, options: Object.entries(REASON).map(([value, label]) => ({ value, label })) }
      : { key: "supplierNo", label: "退给供应商", type: "select", required: true, options: spOptions },
    { key: "cabinetNos", label: "机柜编号", type: "textarea", rows: 4, placeholder: "每行一个，可扫码" },
    { key: "powerbankNos", label: "充电宝编号", type: "textarea", rows: 4, placeholder: "每行一个，可扫码" },
    { key: "note", label: "说明", type: "textarea", required: true, rows: 2, maxLength: 512, help: "提交即出库，设备落为报废，不可撤回" },
  ];

  const cols: Column<Disposal>[] = [
    { header: "单号", cell: (d) => <button type="button" className="font-mono text-primary hover:underline" onClick={() => setOpenNo(d.disposalNo)}>{d.disposalNo}</button> },
    { header: "类型", className: "whitespace-nowrap", cell: (d) => <StatusBadge map={KIND} value={d.kind} /> },
    { header: "仓库", className: "whitespace-nowrap", cell: (d) => d.warehouseName ?? d.warehouseNo },
    { header: "去向 / 原因", cell: (d) => (d.kind === "RETURN_SUPPLIER" ? d.supplierName : d.reasonCode ? REASON[d.reasonCode] : "-") },
    { header: "台数", className: "text-right tabular-nums whitespace-nowrap", cell: (d) => d.itemCount },
    { header: "说明", className: "min-w-40", cell: (d) => <span className="txt-caption">{d.note}</span> },
    { header: "经办", className: "whitespace-nowrap", cell: (d) => d.createdBy ?? "-" },
    { header: "时间", className: "whitespace-nowrap", cell: (d) => (d.createdAt ? fmtTime(d.createdAt) : "-") },
  ];
  const itemCols: Column<DisposalItem>[] = [
    { header: "类型", className: "whitespace-nowrap", cell: (i) => TYPE_LABEL[i.itemType] },
    { header: "设备", cell: (i) => <span className="font-mono">{i.itemNo}</span> },
  ];

  return (
    <div>
      {!canWrite && <ReadOnlyNotice what="处置出库" perm="device:inventory:transfer" note="不能新建报废 / 退供应商单" className="mb-3" />}
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); paging.reset(); }} searchPlaceholder="单号 / 设备号 / 说明"
        onAdd={() => setForm({ kind: "SCRAP", warehouseNo: whQ.data?.find((w) => w.isDefault)?.warehouseNo ?? "" })} addLabel="新建处置单" canAdd={canWrite}>
        <FilterSelect aria-label="类型" value={kind} onChange={(v) => { setKind(v); paging.reset(); }} options={KIND} allLabel="全部类型" />
      </Toolbar>
      <PagedTable query={q} paging={paging} rowKey={(d: Disposal) => d.disposalNo} columns={cols}
        empty={keyword || kind ? "没有符合条件的处置单。" : "还没有处置单。在库设备坏了要报废、整批要退回供应商时在这里出库。"} />
      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新建处置单"
        titleEdit=""
        isEdit={false}
        fields={FIELDS}
        value={form ?? {}}
        onChange={(v) => setForm(v as Record<string, unknown>)}
        onSubmit={() => form && create.mutate(form)}
        submitting={create.isPending}
      />
      <Drawer open={!!openNo} onOpenChange={(o) => { if (!o) setOpenNo(null); }}
        title={detailQ.data ? `${detailQ.data.disposal.disposalNo} · ${KIND[detailQ.data.disposal.kind].label}` : openNo ?? ""}>
        <DataTable rowKey={(i: DisposalItem) => `${i.itemType}:${i.itemNo}`} columns={itemCols} rows={detailQ.data?.items}
          loading={detailQ.isLoading} error={detailQ.error} onRetry={detailQ.refetch} empty="无明细" />
      </Drawer>
    </div>
  );
}
