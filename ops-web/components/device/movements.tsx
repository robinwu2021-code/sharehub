"use client";

// 设备履历（V196，仓外段）：位置与归属的每一次变化，新的在前。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fmtTime } from "@/lib/utils";
import type { DeviceMovement, MovementEvent } from "@/lib/types";
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

const cols: Column<DeviceMovement>[] = [
  { header: "时间", className: "whitespace-nowrap", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.occurredAt)}</span> },
  { header: "动作", className: "whitespace-nowrap", cell: (r) => EVENT_LABEL[r.event] ?? r.event },
  { header: "从", cell: (r) => <span className="tabular-nums">{place(r.fromRef)}</span> },
  { header: "到", cell: (r) => <span className="tabular-nums">{place(r.toRef)}</span> },
  { header: "商家", cell: (r) => <span className="tabular-nums">{r.agentBefore === r.agentAfter ? (r.agentAfter ?? "-") : `${r.agentBefore ?? "-"} → ${r.agentAfter ?? "-"}`}</span> },
  { header: "单号", cell: (r) => <span className="tabular-nums">{r.sourceRef ?? "-"}</span> },
  { header: "原因", cell: (r) => <span className="txt-caption">{r.reason ?? "-"}</span> },
  { header: "经办", cell: (r) => <span className="text-muted-foreground">{r.operatorNo}</span> },
];

export function MovementTable({ itemNo }: { itemNo: string }) {
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["device-movements", itemNo],
    queryFn: () => api.listDeviceMovements(itemNo),
    enabled: !!itemNo,
  });
  return (
    <DataTable rowKey={(r: DeviceMovement) => String(r.id)} columns={cols} rows={data}
      loading={isLoading} error={error} onRetry={refetch} empty="暂无履历（V196 上线前的变化不在这里）" />
  );
}
