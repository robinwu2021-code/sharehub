"use client";

// 仓库（设备管理 › 仓储与出库，V190）。只读：仓库由迁移种入（V182），增改停用随独立库存服务（S4）。
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import type { InventoryMigrationResult, MigrationCandidate, MigrationFailure, Warehouse } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { Drawer, Field } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { exportCsv } from "@/lib/export-csv";

const DEFAULT_MARK: StatusMap<"DEFAULT"> = { DEFAULT: { label: "默认回仓", tone: "info" } };

export function WarehousesTab() {
  const [keyword, setKeyword] = useState("");
  const canMigrate = useCan()("system:param:update");
  const [migrating, setMigrating] = useState(false);
  const q = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses() });
  const regionsQ = useQuery({ queryKey: ["regions", "all"], queryFn: () => api.listRegions({ page: 1, size: UNPAGED_SIZE }) });
  const regionName = useMemo(() => {
    const m = new Map((regionsQ.data?.list ?? []).map((r) => [r.regionId, r.name]));
    return (id: string | null) => (id ? m.get(id) ?? id : "-");
  }, [regionsQ.data]);

  const kw = keyword.trim().toLowerCase();
  const rows = (q.data ?? []).filter((w) => !kw || w.warehouseNo.toLowerCase().includes(kw) || w.name.toLowerCase().includes(kw));

  const cols: Column<Warehouse>[] = [
    { header: "仓号", cell: (w) => <span className="font-mono">{w.warehouseNo}</span> },
    { header: "名称", cell: (w) => w.name },
    { header: "区域", cell: (w) => regionName(w.regionId) },
    { header: "地址", cell: (w) => w.address ?? <span className="text-muted-foreground">-</span> },
    { header: "", className: "whitespace-nowrap", cell: (w) => (w.isDefault ? <StatusBadge map={DEFAULT_MARK} value="DEFAULT" /> : null) },
  ];

  const onExport = () => exportCsv<Warehouse>("仓库", [
    { header: "仓号", value: (w) => w.warehouseNo },
    { header: "名称", value: (w) => w.name },
    { header: "区域", value: (w) => regionName(w.regionId) },
    { header: "地址", value: (w) => w.address ?? "" },
    { header: "默认回仓", value: (w) => (w.isDefault ? "是" : "") },
  ], rows);

  return (
    <div>
      <Toolbar search={keyword} onSearch={setKeyword} searchPlaceholder="仓号 / 名称"
        onExport={rows.length ? onExport : undefined}>
        {canMigrate && <Button size="sm" variant="outline" onClick={() => setMigrating(true)}>迁移到独立进销存</Button>}
      </Toolbar>
      <DataTable rowKey={(w: Warehouse) => w.warehouseNo} columns={cols} rows={q.data ? rows : undefined}
        loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty={kw ? "没有匹配的仓库" : "暂无仓库"} />
      <MigrationDrawer open={migrating} onClose={() => setMigrating(false)} />
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
