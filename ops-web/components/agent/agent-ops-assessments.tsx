"use client";

// 运维月度考核（运营核心流程 F5）：每月一行，达成率 → 下月运维分成系数。
//
// 口径写在表头下方而不是藏在文档里：「被接管算没达成」这一条运营第一次看会觉得不公平，
// 不说清楚理由，他们会去改工单状态来「刷」达成率。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { AgentOpsAssessment } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { fmtTime } from "@/lib/utils";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Notice } from "@/components/ui/notice";

const pct = (v: number | null) => (v == null ? "—" : `${(v * 100).toFixed(1)}%`);

/** 系数档：满档不打折 / 中档 / 低档。按系数值归档，不按达成率 —— 档线是系统参数，可能被调。 */
type CoefBand = "FULL" | "MID" | "LOW";
const COEF_BAND: StatusMap<CoefBand> = {
  FULL: { label: "不打折", tone: "success" },
  MID: { label: "打折", tone: "warning" },
  LOW: { label: "低档", tone: "danger" },
};
const bandOf = (c: number): CoefBand => (c >= 1 ? "FULL" : c >= 0.9 ? "MID" : "LOW");

const COLS: Column<AgentOpsAssessment>[] = [
  { header: "考核月", cell: (r) => <span className="txt-strong tabular-nums">{r.period}</span> },
  {
    header: "工单达成",
    className: "text-right",
    cell: (r) => <span className="tabular-nums">{r.woInSla} / {r.woTotal}</span>,
  },
  { header: "SLA 达成率", className: "text-right", cell: (r) => <span className="tabular-nums">{pct(r.slaRate)}</span> },
  {
    header: "被接管",
    className: "text-right",
    cell: (r) => <span className={r.takenOver > 0 ? "tabular-nums text-warning-ink" : "tabular-nums text-muted-foreground"}>{r.takenOver}</span>,
  },
  { header: "在线率", className: "text-right", cell: (r) => <span className="tabular-nums">{pct(r.onlineRate)}</span> },
  { header: "客诉", className: "text-right", cell: (r) => <span className="tabular-nums">{r.complaints}</span> },
  {
    header: "运维分成系数",
    cell: (r) => (
      <span className="inline-flex items-center gap-1.5">
        <span className="txt-strong tabular-nums">× {r.coefficient.toFixed(2)}</span>
        <StatusBadge map={COEF_BAND} value={bandOf(r.coefficient)} />
      </span>
    ),
  },
  { header: "生效月", cell: (r) => <span className="tabular-nums text-muted-foreground">{r.applyPeriod}</span> },
  { header: "计算时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.computedAt)}</span> },
];

export function AgentOpsAssessments({ agentNo }: { agentNo: string }) {
  const allow = useCan();
  const canRead = allow("agent:performance:read");
  const q = useQuery({
    queryKey: ["agent-ops-assessments", agentNo],
    queryFn: () => api.listAgentOpsAssessments(agentNo),
    enabled: canRead,
  });
  if (!canRead) {
    return <Notice>当前角色无代理绩效查看权限（agent:performance:read），看不到运维考核。</Notice>;
  }
  return (
    <div className="space-y-3">
      <p className="txt-caption text-muted-foreground">
        达成率 = 完结且未超时的工单 ÷（完结的 + 被平台接管的）。被接管算未达成 —— 否则超时不管、等平台接走，反而不影响考核。
        系数用于下一个月的运维分成，不逐单扣分润；没有工单的月份无从考核，系数为 1。
      </p>
      <DataTable
        rowKey={(r) => `${r.agentNo}-${r.period}`}
        columns={COLS}
        rows={q.data}
        loading={q.isLoading}
        error={q.error}
        onRetry={q.refetch}
        empty="还没有考核记录 —— 考核每月初由定时任务对上月跑一次，新签约或上月没有运维工单的代理在跑批后才会出现"
      />
    </div>
  );
}
