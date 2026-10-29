"use client";

// 仓库（设备管理 › 仓储与出库）。V182 种入四个仓；V201 起可新建 / 编辑 / 归档 / 恢复（device:inventory:warehouse），
// 默认回仓仓经系统参数 inv.return_warehouse 设置（system:param:update）。
// 归档前须：不是默认回仓仓、仓里没有设备、没有未结待检单 —— 后端一次列全原因。
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil } from "lucide-react";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import type { InventoryMigrationResult, MigrationCandidate, MigrationFailure, Warehouse, WarehouseReq } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { Drawer, Field } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { ShowArchivedToggle, archivedRowClass, ArchiveActions, archiveConfirm, unarchiveConfirm } from "@/components/archive";
import { exportCsv } from "@/lib/export-csv";

const DEFAULT_MARK: StatusMap<"DEFAULT"> = { DEFAULT: { label: "默认回仓", tone: "info" } };

/** 选仓下拉只给启用中的仓（已归档的仓不能收货 / 退回 / 盘点）。 */
export const activeWarehouses = (ws: Warehouse[] | undefined) => (ws ?? []).filter((w) => !w.archivedAt);

/**
 * @form POST /api/ops/inventory/warehouses
 * @form POST /api/ops/inventory/warehouses/{warehouseNo}
 */
const fieldsOf = (regions: { value: string; label: string }[]): FieldDef[] => [
  { key: "name", label: "仓库名称", required: true, maxLength: 128, placeholder: "迪拜仓" },
  { key: "regionId", label: "区域", type: "select", options: regions },
  { key: "address", label: "地址", maxLength: 256 },
];

export function WarehousesTab() {
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const [keyword, setKeyword] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState<(WarehouseReq & { warehouseNo?: string }) | null>(null);
  const canWrite = allow("device:inventory:warehouse");
  const canSetDefault = allow("system:param:update");
  const canMigrate = canSetDefault;
  const [migrating, setMigrating] = useState(false);
  const q = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses() });
  const regionsQ = useQuery({ queryKey: ["regions", "all"], queryFn: () => api.listRegions({ page: 1, size: UNPAGED_SIZE }) });
  const regionName = useMemo(() => {
    const m = new Map((regionsQ.data?.list ?? []).map((r) => [r.regionId, r.name]));
    return (id: string | null) => (id ? m.get(id) ?? id : "-");
  }, [regionsQ.data]);
  const regionOptions = (regionsQ.data?.list ?? []).map((r) => ({ value: r.regionId, label: r.name }));

  const refresh = () => qc.invalidateQueries({ queryKey: ["warehouses"] });
  const save = useMutation({
    mutationFn: ({ warehouseNo, ...x }: WarehouseReq & { warehouseNo?: string }) =>
      warehouseNo ? api.updateWarehouse(warehouseNo, x) : api.createWarehouse(x),
    onSuccess: () => { refresh(); notify.success("已保存"); setForm(null); },
  });
  const archive = useMutation({ mutationFn: (no: string) => api.archiveWarehouse(no), onSuccess: () => { refresh(); notify.success("已归档"); } });
  const unarchive = useMutation({ mutationFn: (no: string) => api.unarchiveWarehouse(no), onSuccess: () => { refresh(); notify.success("已恢复"); } });
  const setDefault = useMutation({
    mutationFn: (no: string) => api.saveSysParam({ paramKey: "inv.return_warehouse", value: no }),
    onSuccess: () => { refresh(); notify.success("已设为默认回仓仓"); },
  });

  const kw = keyword.trim().toLowerCase();
  const rows = (q.data ?? []).filter((w) => (showArchived || !w.archivedAt)
    && (!kw || w.warehouseNo.toLowerCase().includes(kw) || w.name.toLowerCase().includes(kw)));

  const cols: Column<Warehouse>[] = [
    { header: "仓号", cell: (w) => <span className="font-mono">{w.warehouseNo}</span> },
    { header: "名称", cell: (w) => w.name },
    { header: "区域", cell: (w) => regionName(w.regionId) },
    { header: "地址", cell: (w) => w.address ?? <span className="text-muted-foreground">-</span> },
    { header: "", className: "whitespace-nowrap", cell: (w) => (w.isDefault ? <StatusBadge map={DEFAULT_MARK} value="DEFAULT" /> : null) },
    {
      header: "操作",
      cell: (w) => (
        <div className="flex w-max items-center gap-2">
          <ArchiveActions
            archived={!!w.archivedAt}
            canWrite={canWrite}
            canArchive={!w.isDefault}
            archiveHint="默认回仓仓不能归档：先把别的仓设为默认"
            onArchive={async () => { if (await confirm(archiveConfirm("仓库", w.name))) archive.mutate(w.warehouseNo); }}
            onUnarchive={async () => { if (await confirm(unarchiveConfirm("仓库", w.name))) unarchive.mutate(w.warehouseNo); }}
            actions={<>
              <Button size="sm" variant="outline" onClick={() => setForm({ warehouseNo: w.warehouseNo, name: w.name, regionId: w.regionId, address: w.address })}>
                <Pencil className="size-4" /> 编辑
              </Button>
              {canSetDefault && !w.isDefault && (
                <Button size="sm" variant="outline" disabled={setDefault.isPending} onClick={() => setDefault.mutate(w.warehouseNo)}>设为默认回仓</Button>
              )}
            </>}
          />
        </div>
      ),
    },
  ];

  const onExport = () => exportCsv<Warehouse>("仓库", [
    { header: "仓号", value: (w) => w.warehouseNo },
    { header: "名称", value: (w) => w.name },
    { header: "区域", value: (w) => regionName(w.regionId) },
    { header: "地址", value: (w) => w.address ?? "" },
    { header: "默认回仓", value: (w) => (w.isDefault ? "是" : "") },
    { header: "已归档", value: (w) => (w.archivedAt ? "是" : "") },
  ], rows);

  return (
    <div>
      {!canWrite && <ReadOnlyNotice what="仓库维护" perm="device:inventory:warehouse" note="不能新建、编辑或归档" className="mb-3" />}
      <Toolbar search={keyword} onSearch={setKeyword} searchPlaceholder="仓号 / 名称"
        onExport={rows.length ? onExport : undefined}
        onAdd={() => setForm({ name: "", regionId: null, address: "" })} addLabel="新建仓库" canAdd={canWrite}>
        <ShowArchivedToggle checked={showArchived} onChange={setShowArchived} />
        {canMigrate && <Button size="sm" variant="outline" onClick={() => setMigrating(true)}>迁移到独立进销存</Button>}
      </Toolbar>
      <DataTable rowKey={(w: Warehouse) => w.warehouseNo} columns={cols} rows={q.data ? rows : undefined}
        rowClassName={archivedRowClass}
        loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty={kw ? "没有匹配的仓库" : "还没有仓库。到货登记、撤机回仓都要落到一个仓，先新建一个。"} />
      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新建仓库"
        titleEdit={`编辑仓库 ${form?.warehouseNo ?? ""}`}
        isEdit={!!form?.warehouseNo}
        fields={fieldsOf(regionOptions)}
        value={(form ?? {}) as Record<string, unknown>}
        onChange={(v) => setForm(v as unknown as WarehouseReq & { warehouseNo?: string })}
        onSubmit={() => form && save.mutate(form)}
        submitting={save.isPending}
      />
      <MigrationDrawer open={migrating} onClose={() => setMigrating(false)} />
      {dialog}
    </div>
  );
}

/** 存量迁移（S7）：先看试跑，再分批执行；每台独立、可重跑。切换引擎在系统参数 inv.engine。 */
function MigrationDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const [result, setResult] = useState<InventoryMigrationResult | null>(null);
  const plan = useQuery({ queryKey: ["inventory-migration"], queryFn: () => api.getInventoryMigrationPlan(), enabled: open });
  const run = useMutation({
    mutationFn: () => api.runInventoryMigration(200),
    onSuccess: (r) => {
      setResult(r);
      qc.invalidateQueries({ queryKey: ["inventory-migration"] });
      notify.success(`本批迁移 ${r.processed} 台${r.failed ? `，失败 ${r.failed} 台` : ""}`);
    },
  });
  const p = plan.data;
  const skipCols: Column<MigrationCandidate>[] = [
    { header: "设备号", cell: (c) => <span className="font-mono">{c.itemNo}</span> },
    { header: "原因", cell: (c) => <span className="txt-caption">{c.reason ?? "-"}</span> },
  ];
  const failCols: Column<MigrationFailure>[] = [
    { header: "设备号", cell: (f) => <span className="font-mono">{f.itemNo}</span> },
    { header: "错误码", cell: (f) => <span className="font-mono txt-caption">{f.code}</span> },
    { header: "说明", cell: (f) => <span className="txt-caption">{f.detail || "-"}</span> },
  ];
  return (
    <Drawer open={open} onOpenChange={(o) => { if (!o) { setResult(null); onClose(); } }} title="迁移到独立进销存"
      footer={<Button disabled={!p?.configured || !(p.toInbound + p.toIssue) || run.isPending} onClick={() => run.mutate()}>执行一批（200 台）</Button>}>
      {p && (
        <div className="space-y-4">
          {!p.configured && <div className="txt-caption text-destructive-ink">库存服务未配置（sharehub.inventory.*）</div>}
          <div className="grid grid-cols-2 gap-2 txt-body tabular-nums">
            <span>待入库：{p.toInbound}</span><span>待出库给商家：{p.toIssue}</span>
            <span>已完成：{p.done}</span><span>跳过：{p.skipped}</span>
          </div>
          {result && result.failures.length > 0 && (
            <Field label={`本批失败（${result.failed}）`}>
              <DataTable rowKey={(f: MigrationFailure) => f.itemNo} columns={failCols} rows={result.failures}
                error={run.error} onRetry={() => run.mutate()} empty="无" />
            </Field>
          )}
          {p.skippedItems.length > 0 && (
            <Field label="跳过">
              <DataTable rowKey={(c: MigrationCandidate) => c.itemNo} columns={skipCols} rows={p.skippedItems}
                loading={plan.isFetching && !p} error={plan.error} onRetry={plan.refetch} empty="无" />
            </Field>
          )}
        </div>
      )}
    </Drawer>
  );
}
