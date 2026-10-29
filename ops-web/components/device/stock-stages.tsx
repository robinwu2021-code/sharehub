"use client";

// 统一库存视图（S6）：按生命周期阶段的台数 —— 从到仓待检到离开，一张表看清设备都在哪一步。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { StockStage, StockStageCode } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";

const STAGE_LABEL: Record<StockStageCode, string> = {
  INTAKE: "到仓待检", IN_STOCK: "在仓", IN_TRANSIT: "调拨在途", DISPATCHED: "已发货 · 待签收", ISSUED: "已交付 · 待绑定",
  BOUND: "已绑定 · 待上线", LIVE: "投放中（宝：在柜）", RENTED: "借出", FAULT: "故障", LOST: "遗失", RETIRED: "已离开（报废 / 售出）",
};

export function StockStagesTable() {
  const q = useQuery({ queryKey: ["stock-stages"], queryFn: () => api.getStockStages() });
  const cols: Column<StockStage>[] = [
    { header: "阶段", cell: (r) => STAGE_LABEL[r.stage] ?? r.stage },
    { header: "柜机", className: "text-right tabular-nums", cell: (r) => (r.cabinets ? r.cabinets : <span className="text-muted-foreground">0</span>) },
    { header: "充电宝", className: "text-right tabular-nums", cell: (r) => (r.powerbanks ? r.powerbanks : <span className="text-muted-foreground">0</span>) },
  ];
  return <DataTable rowKey={(r: StockStage) => r.stage} columns={cols} rows={q.data} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="暂无数据" />;
}
