"use client";

// 流程与工单的两个徽标。文案一律用服务给的 name（按 Accept-Language 选好的），
// 色调按**跨租户可比**的量取：状态按大类、优先级按序数 —— 不按代号写死（21/06 §七）。
// 映射表只能按单拼一项，再交给 StatusBadge（不在页面内联写 Badge 的 tone，design-tokens 棘轮）。
import { StatusBadge } from "@/components/ui/status-badge";
import { CATEGORY_TONE, priorityTone } from "@/lib/flow/labels";
import type { Priority, TicketRow } from "@/lib/flow/types";

export function TicketStatusBadge({ t }: { t: Pick<TicketRow, "status" | "statusCategory"> }) {
  return <StatusBadge map={{ [t.status.code]: { label: t.status.name, tone: CATEGORY_TONE[t.statusCategory] } }} value={t.status.code} />;
}

/** 流程任务可能不带优先级（后端 9-28）：缺省显示「—」 */
export function PriorityBadge({ p }: { p?: Priority }) {
  if (!p) return <span className="text-muted-foreground">—</span>;
  return <StatusBadge map={{ [p.code]: { label: p.name, tone: priorityTone(p.ordinal) } }} value={p.code} />;
}
