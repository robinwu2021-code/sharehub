"use client";

// 供应商（设备管理 › 入库与质检，V201）：到货登记的来源、不合格「退供应商」的去向。
// 读 device:inventory:read，写 device:inventory:warehouse。契约禁止 delete：归档 / 恢复。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil } from "lucide-react";
import { api } from "@/lib/api";
import type { Supplier, SupplierReq } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { PagedTable } from "@/components/ui/paged-table";
import { usePaging } from "@/lib/hooks/use-paging";
import { Toolbar } from "@/components/ui/toolbar";
import { type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { ShowArchivedToggle, archivedRowClass, ArchiveActions, archiveConfirm, unarchiveConfirm } from "@/components/archive";
import { exportCsv } from "@/lib/export-csv";

type Form = SupplierReq & { supplierNo?: string };

/**
 * @form POST /api/ops/inventory/suppliers
 * @form POST /api/ops/inventory/suppliers/{supplierNo}
 */
const fieldsOf = (vendors: { value: string; label: string }[]): FieldDef[] => [
  { key: "name", label: "供应商名称", required: true, maxLength: 128 },
  { key: "vendorCode", label: "对应设备厂商", type: "select", options: vendors, help: "供的是哪家厂商的设备（厂商接入）；贸易商可不选" },
  { key: "contactName", label: "联系人", maxLength: 64 },
  { key: "phone", label: "电话", maxLength: 32 },
  { key: "email", label: "邮箱", maxLength: 128 },
  { key: "address", label: "地址", maxLength: 256 },
  { key: "note", label: "备注", type: "textarea", maxLength: 512, help: "供货品类、账期等" },
];

export function SuppliersTab() {
  const qc = useQueryClient();
  const canWrite = useCan()("device:inventory:warehouse");
  const { confirm, dialog } = useConfirm();
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState<Form | null>(null);

  const q = useQuery({
    queryKey: ["suppliers", paging.page, paging.size, keyword, showArchived],
    queryFn: () => api.listSuppliers({ page: paging.page, size: paging.size, keyword, showArchived }),
  });
  const vendorsQ = useQuery({ queryKey: ["vendors"], queryFn: () => api.listVendors() });
  const vendorOptions = (vendorsQ.data ?? []).map((v) => ({ value: v.vendorCode, label: v.name ?? v.vendorCode }));
  const statsQ = useQuery({ queryKey: ["suppliers", "stats"], queryFn: () => api.listSupplierStats() });
  const statOf = new Map((statsQ.data ?? []).map((s) => [s.supplierNo, s]));
  const refresh = () => qc.invalidateQueries({ queryKey: ["suppliers"] });
  const save = useMutation({
    mutationFn: ({ supplierNo, ...x }: Form) => (supplierNo ? api.updateSupplier(supplierNo, x) : api.createSupplier(x)),
    onSuccess: () => { refresh(); notify.success("已保存"); setForm(null); },
  });
  const archive = useMutation({ mutationFn: (no: string) => api.archiveSupplier(no), onSuccess: () => { refresh(); notify.success("已归档"); } });
  const unarchive = useMutation({ mutationFn: (no: string) => api.unarchiveSupplier(no), onSuccess: () => { refresh(); notify.success("已恢复"); } });

  const muted = (v: string | null) => v || <span className="text-muted-foreground">-</span>;
  const cols: Column<Supplier>[] = [
    { header: "供应商", cell: (s) => (
      <div className="min-w-0">
        <div className="truncate">{s.name}</div>
        <div className="truncate font-mono txt-caption text-muted-foreground">{s.supplierNo}</div>
      </div>
    ) },
    { header: "厂商", className: "whitespace-nowrap", cell: (s) => muted(s.vendorCode) },
    { header: "到货", className: "text-right tabular-nums whitespace-nowrap", cell: (s) => statOf.get(s.supplierNo)?.received ?? 0 },
    { header: "合格率", className: "text-right tabular-nums whitespace-nowrap", cell: (s) => {
      const r = statOf.get(s.supplierNo)?.passRate;
      return r == null ? <span className="text-muted-foreground">-</span> : `${(r * 100).toFixed(1)}%`;
    } },
    { header: "退货", className: "text-right tabular-nums whitespace-nowrap", cell: (s) => statOf.get(s.supplierNo)?.returned ?? 0 },
    { header: "联系人", className: "whitespace-nowrap", cell: (s) => muted(s.contactName) },
    { header: "电话", className: "whitespace-nowrap", cell: (s) => muted(s.phone) },
    { header: "邮箱", cell: (s) => muted(s.email) },
    { header: "备注", className: "min-w-40", cell: (s) => <span className="txt-caption">{muted(s.note)}</span> },
    {
      header: "操作",
      cell: (s) => (
        <div className="flex w-max items-center gap-2">
          <ArchiveActions
            archived={!!s.archivedAt}
            canWrite={canWrite}
            onArchive={async () => { if (await confirm(archiveConfirm("供应商", s.name))) archive.mutate(s.supplierNo); }}
            onUnarchive={async () => { if (await confirm(unarchiveConfirm("供应商", s.name))) unarchive.mutate(s.supplierNo); }}
            actions={<Button size="sm" variant="outline" onClick={() => setForm({ ...s })}><Pencil className="size-4" /> 编辑</Button>}
          />
        </div>
      ),
    },
  ];

  const onExport = () => exportCsv<Supplier>("供应商", [
    { header: "供应商号", value: (s) => s.supplierNo },
    { header: "名称", value: (s) => s.name },
    { header: "厂商", value: (s) => s.vendorCode ?? "" },
    { header: "到货", value: (s) => statOf.get(s.supplierNo)?.received ?? 0 },
    { header: "合格率", value: (s) => { const r = statOf.get(s.supplierNo)?.passRate; return r == null ? "" : `${(r * 100).toFixed(1)}%`; } },
    { header: "退货", value: (s) => statOf.get(s.supplierNo)?.returned ?? 0 },
    { header: "联系人", value: (s) => s.contactName ?? "" },
    { header: "电话", value: (s) => s.phone ?? "" },
    { header: "邮箱", value: (s) => s.email ?? "" },
    { header: "地址", value: (s) => s.address ?? "" },
    { header: "备注", value: (s) => s.note ?? "" },
  ], q.data?.list ?? []);

  return (
    <div>
      {!canWrite && <ReadOnlyNotice what="供应商维护" perm="device:inventory:warehouse" note="不能新建、编辑或归档" className="mb-3" />}
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); paging.reset(); }} searchPlaceholder="名称 / 编号 / 联系人 / 电话"
        onExport={q.data?.list?.length ? onExport : undefined}
        onAdd={() => setForm({ name: "" })} addLabel="新建供应商" canAdd={canWrite}>
        <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); paging.reset(); }} />
      </Toolbar>
      <PagedTable query={q} paging={paging} rowKey={(s: Supplier) => s.supplierNo} columns={cols} rowClassName={archivedRowClass}
        empty={keyword ? "没有匹配的供应商。" : "还没有供应商。到货登记时选供应商，不合格设备才知道退给谁。"} />
      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新建供应商"
        titleEdit={`编辑供应商 ${form?.supplierNo ?? ""}`}
        isEdit={!!form?.supplierNo}
        fields={fieldsOf(vendorOptions)}
        value={(form ?? {}) as Record<string, unknown>}
        onChange={(v) => setForm(v as unknown as Form)}
        onSubmit={() => form && save.mutate(form)}
        submitting={save.isPending}
      />
      {dialog}
    </div>
  );
}
