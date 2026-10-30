"use client";

// 仓库（设备管理 › 仓储与出库）。V182 种入四个仓；V201 起可新建 / 编辑 / 归档 / 恢复（device:inventory:warehouse），
// 默认回仓仓经系统参数 inv.return_warehouse 设置（system:param:update）。
// 归档前须：不是默认回仓仓、仓里没有设备、没有未结待检单 —— 后端一次列全原因。
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil } from "lucide-react";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import type { InventoryEngine, InventoryMigrationResult, MigrationCandidate, MigrationFailure, StockThreshold, SysParam, Warehouse, WarehouseReq } from "@/lib/types";
import { Select } from "@/components/ui/input";
import { Input } from "@/components/ui/input";
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
const LOW_MARK: StatusMap<"LOW"> = { LOW: { label: "低于安全库存", tone: "warning" } };
const TYPE_LABEL = { CABINET: "机柜", POWERBANK: "充电宝" } as const;

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
  const [thresholdOf, setThresholdOf] = useState<Warehouse | null>(null);
  const q = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses() });
  const thQ = useQuery({ queryKey: ["stock-thresholds"], queryFn: () => api.listStockThresholds() });
  const lowWh = new Set((thQ.data ?? []).filter((t) => t.low).map((t) => t.warehouseNo));
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
    { header: "", className: "whitespace-nowrap", cell: (w) => (
      <div className="flex gap-1">
        {w.isDefault && <StatusBadge map={DEFAULT_MARK} value="DEFAULT" />}
        {lowWh.has(w.warehouseNo) && <StatusBadge map={LOW_MARK} value="LOW" />}
      </div>
    ) },
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
              <Button size="sm" variant="outline" onClick={() => setThresholdOf(w)}>安全库存</Button>
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
      <ThresholdDrawer warehouse={thresholdOf} canWrite={canWrite} onClose={() => setThresholdOf(null)} />
      {dialog}
    </div>
  );
}

const ENGINE_REFRESH_MS = 30_000;
const ENGINE_KIND: StatusMap<"LOCAL" | "SERVICE"> = { LOCAL: { label: "本地设备表", tone: "muted" }, SERVICE: { label: "独立进销存", tone: "info" } };
const LINK: StatusMap<"OK" | "DOWN" | "UNCONFIGURED"> = {
  OK: { label: "连接正常", tone: "success" }, DOWN: { label: "不可达", tone: "danger" }, UNCONFIGURED: { label: "未配置", tone: "warning" },
};
const MODE_LABEL = { STATIC: "固定地址", DISCOVERY: "服务发现" } as const;
const PENDING: StatusMap<"PENDING"> = { PENDING: { label: "待查回", tone: "warning" } };

/** 库存引擎与库存服务连通性（M0 0.3）：LOCAL 下不探测。 */
function EngineStatus({ engine }: { engine: InventoryEngine | undefined }) {
  if (!engine) return null;
  const link = engine.engine !== "SERVICE" ? null : !engine.configured ? "UNCONFIGURED" : engine.reachable ? "OK" : "DOWN";
  return (
    <div className="mb-3 flex flex-wrap items-center gap-2 txt-body">
      <span className="text-muted-foreground">库存引擎</span>
      <StatusBadge map={ENGINE_KIND} value={engine.engine} />
      {link && (
        <>
          <span className="text-muted-foreground">库存服务</span>
          <StatusBadge map={LINK} value={link} />
          {engine.endpointMode && <span className="txt-caption text-muted-foreground">寻址：{MODE_LABEL[engine.endpointMode]}</span>}
          {link === "DOWN" && engine.failure && <span className="font-mono txt-caption text-destructive-ink">{engine.failure}</span>}
        </>
      )}
      {!!engine.pendingOperations && (
        <>
          <span className="text-muted-foreground">未完成操作</span>
          <StatusBadge map={PENDING} value="PENDING" />
          <span className="tabular-nums">{engine.pendingOperations}</span>
          <span className="txt-caption text-muted-foreground">处理完才能切换引擎</span>
        </>
      )}
    </div>
  );
}

/**
 * 库存设置（库存管理 › 设置，V207 参数 / V209 独立成页签）：系统参数「库存」组的统一入口，
 * 控件按字典渲染（枚举 → 下拉、整数 → 数字），默认回仓仓给仓库下拉。
 * 改动走系统参数接口（system:param:update），后端按字典校验。跟踪方式在物料档案上按物料定，不设全局参数。
 */
export function InventorySettingsTab() {
  const qc = useQueryClient();
  const canWrite = useCan()("system:param:update");
  const whQ = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses() });
  const warehouses = activeWarehouses(whQ.data);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const q = useQuery({ queryKey: ["sys-params", "库存"],
    queryFn: () => api.listSysParams({ groupName: "库存", page: 1, size: UNPAGED_SIZE }) });
  // 连通性：30 秒重探一次，库存服务恢复后自动变回「正常」
  const engineQ = useQuery({ queryKey: ["inventory-engine"], queryFn: () => api.getInventoryEngine(), refetchInterval: ENGINE_REFRESH_MS });
  const save = useMutation({
    mutationFn: (p: SysParam) => api.saveSysParam({ paramKey: p.paramKey, value: draft[p.paramKey] ?? p.value }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["sys-params"] });
      qc.invalidateQueries({ queryKey: ["warehouses"] });
      qc.invalidateQueries({ queryKey: ["inventory-engine"] });
      notify.success("已保存");
    },
  });
  const valueOf = (p: SysParam) => draft[p.paramKey] ?? p.value;
  const control = (p: SysParam) => {
    const set = (v: string) => setDraft({ ...draft, [p.paramKey]: v });
    if (!canWrite) return <span>{p.options?.find((o) => o.value === p.value)?.label ?? (p.value || "-")}</span>;
    if (p.paramKey === "inv.return_warehouse")
      return (
        <Select aria-label={p.label} value={valueOf(p)} onChange={(e) => set(e.target.value)}>
          <option value="">（第一个仓库）</option>
          {warehouses.map((w) => <option key={w.warehouseNo} value={w.warehouseNo}>{w.name}</option>)}
        </Select>
      );
    if (p.valueType === "ENUM")
      return (
        <Select aria-label={p.label} value={valueOf(p)} onChange={(e) => set(e.target.value)}>
          {(p.options ?? []).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </Select>
      );
    return <Input aria-label={p.label} type={p.valueType === "INT" ? "number" : "text"} value={valueOf(p)} onChange={(e) => set(e.target.value)} />;
  };
  const cols: Column<SysParam>[] = [
    { header: "参数", cell: (p) => (
      <div className="min-w-0">
        <div>{p.label}</div>
        {p.hint && <div className="txt-caption text-muted-foreground">{p.hint}</div>}
      </div>
    ) },
    { header: "取值", className: "w-48", cell: control },
    { header: "", className: "whitespace-nowrap", cell: (p) => (canWrite
      ? <Button size="sm" variant="outline" disabled={save.isPending || valueOf(p) === p.value} onClick={() => save.mutate(p)}>保存</Button>
      : null) },
  ];
  return (
    <div>
      <EngineStatus engine={engineQ.data} />
      {!canWrite && <ReadOnlyNotice what="库存设置" perm="system:param:update" className="mb-3" />}
      <DataTable rowKey={(p: SysParam) => p.paramKey} columns={cols} rows={q.data?.list}
        loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="没有库存参数" />
    </div>
  );
}

/** 安全库存（V204）：本仓柜 / 宝各一个下限；低于下限由告警 LOW_STOCK 给仓管待办。0 = 不预警。 */
function ThresholdDrawer({ warehouse, canWrite, onClose }: { warehouse: Warehouse | null; canWrite: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const [draft, setDraft] = useState<Record<string, string>>({});
  const q = useQuery({
    queryKey: ["stock-thresholds", warehouse?.warehouseNo], enabled: !!warehouse,
    queryFn: () => api.listStockThresholds(warehouse!.warehouseNo),
  });
  const save = useMutation({
    mutationFn: (t: StockThreshold) => api.saveStockThreshold({ warehouseNo: t.warehouseNo, itemType: t.itemType,
      minQty: Number(draft[t.itemType] ?? t.minQty) }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["stock-thresholds"] }); notify.success("已保存"); },
  });
  const cols: Column<StockThreshold>[] = [
    { header: "设备", className: "whitespace-nowrap", cell: (t) => TYPE_LABEL[t.itemType] },
    { header: "可用", className: "text-right tabular-nums whitespace-nowrap", cell: (t) => t.available ?? "-" },
    { header: "下限", className: "w-32", cell: (t) => (canWrite
      ? <Input type="number" min={0} aria-label={`${TYPE_LABEL[t.itemType]}下限`} value={draft[t.itemType] ?? String(t.minQty)}
          onChange={(e) => setDraft({ ...draft, [t.itemType]: e.target.value })} />
      : <span className="tabular-nums">{t.minQty || "不预警"}</span>) },
    { header: "", className: "whitespace-nowrap", cell: (t) => (t.low ? <StatusBadge map={LOW_MARK} value="LOW" /> : null) },
    { header: "", className: "whitespace-nowrap", cell: (t) => (canWrite
      ? <Button size="sm" variant="outline" disabled={save.isPending || (draft[t.itemType] ?? String(t.minQty)) === String(t.minQty)}
          onClick={() => save.mutate(t)}>保存</Button>
      : null) },
  ];
  return (
    <Drawer open={!!warehouse} onOpenChange={(o) => { if (!o) { setDraft({}); onClose(); } }} title={`安全库存 · ${warehouse?.name ?? ""}`}>
      <div className="mb-3 txt-caption text-muted-foreground">可用库存：本仓未出库、未绑定点位的设备。低于下限给仓管发待办，补到下限自动消除；0 = 不预警。</div>
      <DataTable rowKey={(t: StockThreshold) => t.itemType} columns={cols} rows={q.data}
        loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="无" />
    </Drawer>
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
