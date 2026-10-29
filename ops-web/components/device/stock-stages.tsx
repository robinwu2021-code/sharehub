"use client";

// 统一库存视图（S6）：按生命周期阶段的台数 —— 从到仓待检到离开，一张表看清设备都在哪一步。
// 下钻（P2 #6）：点台数 → 按「所在 × 型号」分组 → 点分组看设备清单。清单与台数同一份筛选条件（后端 stageItems）。
import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { IntakeItemType, StageItem, StockStage, StockStageCode } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { exportCsv } from "@/lib/export-csv";

export const STAGE_LABEL: Record<StockStageCode, string> = {
  INTAKE: "到仓待检", IN_STOCK: "在仓", IN_TRANSIT: "调拨在途", DISPATCHED: "已发货 · 待签收", ISSUED: "已交付 · 待绑定",
  BOUND: "已绑定 · 待上线", LIVE: "投放中（宝：在柜）", RENTED: "借出", FAULT: "故障", LOST: "遗失", RETIRED: "已离开（报废 / 售出）",
};
const TYPE_LABEL = { CABINET: "机柜", POWERBANK: "充电宝" } as const;

/** 设备「所在」：在仓的看仓，出库 / 绑定的看商家，宝在柜看柜，待检看待检单。 */
const whereOf = (i: StageItem) => i.warehouseNo ?? i.agentNo ?? (i.locationRef ? `柜 ${i.locationRef}` : null) ?? (i.intakeNo ? `待检单 ${i.intakeNo}` : "-");

export function StockStagesTable() {
  const q = useQuery({ queryKey: ["stock-stages"], queryFn: () => api.getStockStages() });
  const [drill, setDrill] = useState<{ stage: StockStageCode; itemType: IntakeItemType } | null>(null);
  const num = (r: StockStage, itemType: IntakeItemType) => {
    const n = itemType === "CABINET" ? r.cabinets : r.powerbanks;
    return n
      ? <button type="button" className="tabular-nums text-primary hover:underline" onClick={() => setDrill({ stage: r.stage, itemType })}>{n}</button>
      : <span className="text-muted-foreground">0</span>;
  };
  const cols: Column<StockStage>[] = [
    { header: "阶段", cell: (r) => STAGE_LABEL[r.stage] ?? r.stage },
    { header: "柜机", className: "text-right tabular-nums", cell: (r) => num(r, "CABINET") },
    { header: "充电宝", className: "text-right tabular-nums", cell: (r) => num(r, "POWERBANK") },
  ];
  return (
    <>
      <DataTable rowKey={(r: StockStage) => r.stage} columns={cols} rows={q.data} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="暂无数据" />
      <StageDrawer target={drill} onClose={() => setDrill(null)} />
    </>
  );
}

interface Group { key: string; where: string; model: string; count: number }

function StageDrawer({ target, onClose }: { target: { stage: StockStageCode; itemType: IntakeItemType } | null; onClose: () => void }) {
  const [group, setGroup] = useState<string | null>(null);
  const q = useQuery({
    queryKey: ["stage-items", target?.stage, target?.itemType], enabled: !!target,
    queryFn: () => api.getStageItems(target!.stage, target!.itemType),
  });
  const items = useMemo(() => q.data?.items ?? [], [q.data]);
  const groups = useMemo(() => {
    const m = new Map<string, Group>();
    for (const i of items) {
      const where = whereOf(i);
      const model = i.model ?? "-";
      const key = `${where}|${model}`;
      const g = m.get(key) ?? { key, where, model, count: 0 };
      g.count++;
      m.set(key, g);
    }
    return [...m.values()].sort((a, b) => b.count - a.count);
  }, [items]);
  const shown = group ? items.filter((i) => `${whereOf(i)}|${i.model ?? "-"}` === group) : items;

  const groupCols: Column<Group>[] = [
    { header: "所在", cell: (g) => <span className="font-mono">{g.where}</span> },
    { header: target?.itemType === "POWERBANK" ? "厂商" : "型号", cell: (g) => g.model },
    { header: "台数", className: "text-right tabular-nums", cell: (g) => (
      <button type="button" className="text-primary hover:underline" onClick={() => setGroup(group === g.key ? null : g.key)}>{g.count}</button>
    ) },
  ];
  const itemCols: Column<StageItem>[] = [
    { header: "设备", cell: (i) => <span className="font-mono">{i.itemNo}</span> },
    { header: target?.itemType === "POWERBANK" ? "厂商" : "型号", cell: (i) => i.model ?? "-" },
    { header: "所在", cell: (i) => <span className="font-mono">{whereOf(i)}</span> },
  ];
  const onExport = () => exportCsv<StageItem>(`${target ? STAGE_LABEL[target.stage] : ""}_${target ? TYPE_LABEL[target.itemType] : ""}`, [
    { header: "设备", value: (i) => i.itemNo },
    { header: "型号/厂商", value: (i) => i.model ?? "" },
    { header: "仓", value: (i) => i.warehouseNo ?? "" },
    { header: "商家", value: (i) => i.agentNo ?? "" },
    { header: "站点", value: (i) => i.siteNo ?? "" },
    { header: "点位/所在柜", value: (i) => i.locationRef ?? "" },
    { header: "待检单", value: (i) => i.intakeNo ?? "" },
  ], shown);

  const truncated = q.data && q.data.total > q.data.items.length;
  return (
    <Drawer open={!!target} onOpenChange={(o) => { if (!o) { setGroup(null); onClose(); } }} width="w-[720px]"
      title={target ? `${STAGE_LABEL[target.stage]} · ${TYPE_LABEL[target.itemType]}${q.data ? `（${q.data.total} 台）` : ""}` : ""}
      footer={shown.length ? <Button variant="outline" onClick={onExport}>导出清单</Button> : undefined}>
      <div className="space-y-4">
        {truncated && <div className="txt-caption text-muted-foreground">只列出前 {q.data!.items.length} 台；分组按已列出的统计。</div>}
        <Field label="按所在 × 型号">
          <DataTable rowKey={(g: Group) => g.key} columns={groupCols} rows={q.data ? groups : undefined}
            rowClassName={(g: Group) => (group === g.key ? "bg-muted" : undefined)}
            loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="无" />
        </Field>
        <Field label={group ? "设备（已按分组筛选，再点一次台数取消）" : "设备"}>
          <DataTable rowKey={(i: StageItem) => i.itemNo} columns={itemCols} rows={q.data ? shown : undefined}
            loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="无" />
        </Field>
      </div>
    </Drawer>
  );
}
