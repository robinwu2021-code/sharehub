"use client";

// 仓库（设备管理 › 仓储与出库，V190）。只读：仓库由迁移种入（V182），增改停用随独立库存服务（S4）。
import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import type { Warehouse } from "@/lib/types";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { exportCsv } from "@/lib/export-csv";

const DEFAULT_MARK: StatusMap<"DEFAULT"> = { DEFAULT: { label: "默认回仓", tone: "info" } };

export function WarehousesTab() {
  const [keyword, setKeyword] = useState("");
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
        onExport={rows.length ? onExport : undefined} />
      <DataTable rowKey={(w: Warehouse) => w.warehouseNo} columns={cols} rows={q.data ? rows : undefined}
        loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty={kw ? "没有匹配的仓库" : "暂无仓库"} />
    </div>
  );
}
