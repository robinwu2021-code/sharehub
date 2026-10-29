"use client";

// 物料档案（库存管理 › 主数据，V209）：设备之外，备件、耗材、其他整机也在这里建档，同一套仓库与单据管账。
// 机柜、充电宝是内置物料（只读）：它们的账走设备流程（入库单、出库池、调拨），台账在设备管理。
// 跟踪方式：逐件（序列号）/ 按数量 —— 有出入库流水后不可改（对齐 ai-shop 库存服务）。写码 device:inventory:warehouse。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { Pencil } from "lucide-react";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import type { Material, MaterialCategory, MaterialReq } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { usePaging } from "@/lib/hooks/use-paging";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { PagedTable } from "@/components/ui/paged-table";
import { type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { ShowArchivedToggle, archivedRowClass, ArchiveActions, archiveConfirm, unarchiveConfirm } from "@/components/archive";
import { CATEGORY, TRACKING_LABEL } from "@/components/inventory/material-maps";

type Form = MaterialReq & { materialNo?: string; hasMovements?: boolean };
const BUILTIN: StatusMap<"BUILTIN"> = { BUILTIN: { label: "内置", tone: "muted" } };

/**
 * @form POST /api/ops/inventory/materials
 * @form POST /api/ops/inventory/materials/{materialNo}
 */
const fieldsOf = (locked: boolean, vendors: { value: string; label: string }[], suppliers: { value: string; label: string }[]): FieldDef[] => [
  { key: "name", label: "物料名称", required: true, maxLength: 128 },
  { key: "category", label: "大类", type: "select", required: true,
    options: Object.entries(CATEGORY).map(([value, v]) => ({ value, label: v.label })) },
  { key: "trackingMode", label: "跟踪方式", type: "select", required: true, readOnlyOnEdit: locked,
    options: Object.entries(TRACKING_LABEL).map(([value, label]) => ({ value, label })),
    help: locked ? "已有出入库流水，跟踪方式不能再改" : "整机一般逐件（录序列号）；备件、耗材按数量。有流水后不可改" },
  { key: "uom", label: "计量单位", required: true, maxLength: 16, placeholder: "个 / 条 / 套 / 台" },
  { key: "spec", label: "规格型号", maxLength: 128 },
  { key: "vendorCode", label: "设备厂商", type: "select", options: vendors },
  { key: "defaultSupplierNo", label: "默认供应商", type: "select", options: suppliers, help: "采购入库时预填" },
];

export function MaterialsTab() {
  const qc = useQueryClient();
  const canWrite = useCan()("device:inventory:warehouse");
  const { confirm, dialog } = useConfirm();
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [category, setCategory] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState<Form | null>(null);

  const q = useQuery({
    queryKey: ["materials", paging.page, paging.size, keyword, category, showArchived],
    queryFn: () => api.listMaterials({ page: paging.page, size: paging.size, keyword, showArchived,
      category: (category || undefined) as MaterialCategory | undefined }),
    placeholderData: keepPreviousData,
  });
  const vendorsQ = useQuery({ queryKey: ["vendors"], queryFn: () => api.listVendors() });
  const spQ = useQuery({ queryKey: ["suppliers", "active"], queryFn: () => api.listSuppliers({ page: 1, size: UNPAGED_SIZE }) });
  const vendorOptions = (vendorsQ.data ?? []).map((v) => ({ value: v.vendorCode, label: v.name ?? v.vendorCode }));
  const supplierOptions = (spQ.data?.list ?? []).map((s) => ({ value: s.supplierNo, label: s.name }));

  const refresh = () => qc.invalidateQueries({ queryKey: ["materials"] });
  const save = useMutation({
    mutationFn: ({ materialNo, hasMovements: _, ...x }: Form) => (materialNo ? api.updateMaterial(materialNo, x) : api.createMaterial(x)),
    onSuccess: () => { refresh(); notify.success("已保存"); setForm(null); },
  });
  const archive = useMutation({ mutationFn: (no: string) => api.archiveMaterial(no), onSuccess: () => { refresh(); notify.success("已归档"); } });
  const unarchive = useMutation({ mutationFn: (no: string) => api.unarchiveMaterial(no), onSuccess: () => { refresh(); notify.success("已恢复"); } });

  const muted = (v: string | null) => v || <span className="text-muted-foreground">-</span>;
  const cols: Column<Material>[] = [
    { header: "物料", cell: (m) => (
      <div className="min-w-0">
        <div className="truncate">{m.name}</div>
        <div className="truncate font-mono txt-caption text-muted-foreground">{m.materialNo}</div>
      </div>
    ) },
    { header: "大类", className: "whitespace-nowrap", cell: (m) => <StatusBadge map={CATEGORY} value={m.category} /> },
    { header: "跟踪方式", className: "whitespace-nowrap", cell: (m) => TRACKING_LABEL[m.trackingMode] },
    { header: "单位", className: "whitespace-nowrap", cell: (m) => m.uom },
    { header: "规格", cell: (m) => muted(m.spec) },
    { header: "厂商", className: "whitespace-nowrap", cell: (m) => muted(m.vendorCode) },
    { header: "操作", cell: (m) => (m.builtin
      ? <div className="flex items-center gap-2"><StatusBadge map={BUILTIN} value="BUILTIN" /><span className="txt-caption text-muted-foreground">在设备台账维护</span></div>
      : (
        <div className="flex w-max items-center gap-2">
          <ArchiveActions
            archived={!!m.archivedAt}
            canWrite={canWrite}
            onArchive={async () => { if (await confirm(archiveConfirm("物料", m.name))) archive.mutate(m.materialNo); }}
            onUnarchive={async () => { if (await confirm(unarchiveConfirm("物料", m.name))) unarchive.mutate(m.materialNo); }}
            actions={<Button size="sm" variant="outline" onClick={() => setForm({ ...m })}><Pencil className="size-4" /> 编辑</Button>}
          />
        </div>
      )) },
  ];

  const onExport = () => exportCsv<Material>("物料档案", [
    { header: "物料号", value: (m) => m.materialNo },
    { header: "名称", value: (m) => m.name },
    { header: "大类", value: (m) => CATEGORY[m.category].label },
    { header: "跟踪方式", value: (m) => TRACKING_LABEL[m.trackingMode] },
    { header: "单位", value: (m) => m.uom },
    { header: "规格", value: (m) => m.spec ?? "" },
    { header: "厂商", value: (m) => m.vendorCode ?? "" },
  ], q.data?.list ?? []);

  return (
    <div>
      {!canWrite && <ReadOnlyNotice what="物料档案维护" perm="device:inventory:warehouse" note="不能新建、编辑或归档" className="mb-3" />}
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); paging.reset(); }} searchPlaceholder="物料号 / 名称 / 规格"
        onExport={q.data?.list?.length ? onExport : undefined}
        onAdd={() => setForm({ name: "", category: "SPARE_PART", trackingMode: "QUANTITY", uom: "个" })} addLabel="新建物料" canAdd={canWrite}>
        <FilterSelect aria-label="大类" value={category} onChange={(v) => { setCategory(v); paging.reset(); }} options={CATEGORY} allLabel="全部大类" />
        <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); paging.reset(); }} />
      </Toolbar>
      <PagedTable query={q} paging={paging} rowKey={(m: Material) => m.materialNo} columns={cols} rowClassName={archivedRowClass}
        empty={keyword || category ? "没有符合条件的物料。" : "还没有物料。备件、耗材、其他整机先在这里建档，才能入库、领用和盘点。"} />
      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新建物料"
        titleEdit={`编辑物料 ${form?.materialNo ?? ""}`}
        isEdit={!!form?.materialNo}
        fields={fieldsOf(!!form?.hasMovements, vendorOptions, supplierOptions)}
        value={(form ?? {}) as Record<string, unknown>}
        onChange={(v) => setForm(v as unknown as Form)}
        onSubmit={() => form && save.mutate(form)}
        submitting={save.isPending}
      />
      {dialog}
    </div>
  );
}
