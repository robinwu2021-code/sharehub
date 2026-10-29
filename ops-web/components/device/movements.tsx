"use client";

// 设备时间轴（V196 履历 + 质检记录，A4 / PB-F2）：位置与归属的每一次变化与每次质检，按时间合并，新的在前。
// 仓内段（独立进销存流水）接入后在这里再合一路。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fmtTime } from "@/lib/utils";
import type { DeviceMovement, MovementEvent, QcRecord } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";

const EVENT_LABEL: Record<MovementEvent, string> = {
  ISSUE: "出库", CLAIM: "签收", REDIRECT: "出库调整", POOL_RETURN: "出库池退回", BIND: "绑定", UNBIND: "解绑",
  SHIP: "调拨发货", RECEIVE: "调拨签收", UNDEPLOY: "撤机", RETURN_INTAKE: "回仓待检", INBOUND: "入库",
  REASSIGN: "换商家", RETIRE: "报废", LOST: "遗失",
};

const PREFIX: Record<string, string> = { WH: "仓", AGENT: "商家", SITE: "站点", LOC: "点位", CAB: "机柜" };

/** 「WH:WH001」→「仓 WH001」；TRANSIT / OUT 单独翻译。 */
function place(ref: string | null): string {
  if (!ref) return "-";
  if (ref === "TRANSIT") return "在途";
  if (ref === "OUT") return "已离开";
  const i = ref.indexOf(":");
  return i < 0 ? ref : `${PREFIX[ref.slice(0, i)] ?? ref.slice(0, i)} ${ref.slice(i + 1)}`;
}

/** 时间轴一行：履历行原样；质检记录折成同一形状（动作 = 质检结论，单号 = 质检记录号）。 */
type Row = DeviceMovement & { qcResult?: QcRecord["result"] };

const fromQc = (q: QcRecord): Row => ({
  id: -1, itemType: q.itemType, itemNo: q.itemNo, event: "INBOUND", fromRef: null, toRef: null, agentBefore: null, agentAfter: null,
  reason: q.note, sourceRef: q.qcNo, operatorNo: q.inspectedBy, occurredAt: q.inspectedAt, qcResult: q.result,
});

const cols: Column<Row>[] = [
  { header: "时间", className: "whitespace-nowrap", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.occurredAt)}</span> },
  { header: "动作", className: "whitespace-nowrap", cell: (r) => r.qcResult ? `质检${r.qcResult === "PASSED" ? "合格" : "不合格"}` : (EVENT_LABEL[r.event] ?? r.event) },
  { header: "从", cell: (r) => <span className="tabular-nums">{place(r.fromRef)}</span> },
  { header: "到", cell: (r) => <span className="tabular-nums">{place(r.toRef)}</span> },
  { header: "商家", cell: (r) => <span className="tabular-nums">{r.agentBefore === r.agentAfter ? (r.agentAfter ?? "-") : `${r.agentBefore ?? "-"} → ${r.agentAfter ?? "-"}`}</span> },
  { header: "单号", cell: (r) => <span className="tabular-nums">{r.sourceRef ?? "-"}</span> },
  { header: "原因", cell: (r) => <span className="txt-caption">{r.reason ?? "-"}</span> },
  { header: "经办", cell: (r) => <span className="text-muted-foreground">{r.operatorNo}</span> },
];

export function MovementTable({ itemNo }: { itemNo: string }) {
  const mv = useQuery({ queryKey: ["device-movements", itemNo], queryFn: () => api.listDeviceMovements(itemNo), enabled: !!itemNo });
  const qc = useQuery({ queryKey: ["qc-records", itemNo], queryFn: () => api.listQcRecords(itemNo), enabled: !!itemNo });
  const rows: Row[] | undefined = mv.data && qc.data
    ? [...mv.data, ...qc.data.map(fromQc)].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
    : undefined;
  return (
    <DataTable rowKey={(r: Row) => (r.qcResult ? `qc-${r.sourceRef}` : `mv-${r.id}`)} columns={cols} rows={rows}
      loading={mv.isLoading || qc.isLoading} error={mv.error ?? qc.error} onRetry={() => { mv.refetch(); qc.refetch(); }}
      empty="暂无记录（V196 上线前的位置变化不在这里）" />
  );
}
