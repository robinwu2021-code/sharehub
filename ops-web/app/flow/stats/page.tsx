"use client";

// 流程统计（F7 的流程那一半；后端 O2-10 POST /process-stats）：各流程在所选时间段内发起的实例 ——
// 总量、未结、状态分布、平均用时（只算已结）、审批结果。口径与流程实例列表一致（同一段筛选）。
// 工单那一半（按受理方的完成数、达标率、被接管数）随后端 M3 的 ticket-stats。
import { NeedPerm } from "@/components/flow/need-perm";
import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { useFlowT } from "@/lib/flow/i18n";
import { approvalStatusMap } from "@/lib/flow/labels";
import { useProcessOptions } from "@/lib/flow/options";
import type { ApprovalStatus, ProcessStats, StatusCategory } from "@/lib/flow/types";
import { PageTitle } from "@/components/ui/misc";
import { Card } from "@/components/ui/card";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { FilterChips } from "@/components/ui/filter-chips";
import { DataTable, type Column } from "@/components/ui/data-table";
import { SummaryCard } from "@/components/ui/summary-card";
import { StatusBadge } from "@/components/ui/status-badge";

const DAY = 86_400_000;
const MIN = 60_000;
const PERIODS = ["d7", "d30", "d90", "all"] as const;
const DAYS: Record<(typeof PERIODS)[number], number | null> = { d7: 7, d30: 30, d90: 90, all: null };
const CATS: StatusCategory[] = ["NEW", "IN_PROGRESS", "WAITING", "DONE", "CANCELLED"];
const APPR: ApprovalStatus[] = ["PENDING", "APPROVED", "REJECTED", "WITHDRAWN"];

function FlowStats() {
  const { t, tc, fmt } = useFlowT("stats");
  const processOptions = useProcessOptions().map((p) => ({ value: p.code, label: p.name }));
  const [processCode, setProcessCode] = useState("");
  const [period, setPeriod] = useState<(typeof PERIODS)[number]>("d30");
  // 时间段的起点取到分钟，避免每次渲染都换查询键
  const fromMs = useMemo(() => (DAYS[period] ? Math.floor((Date.now() - DAYS[period]! * DAY) / MIN) * MIN : undefined), [period]);
  const q = useQuery({
    queryKey: [...FLOW_KEY, "process-stats", processCode, fromMs],
    queryFn: () => flow.processStats({ ...(processCode ? { processCode } : {}), ...(fromMs ? { fromMs } : {}) }),
  });

  const rows = q.data ?? [];
  const sum = rows.reduce((a, s) => ({ total: a.total + s.total, open: a.open + s.open }), { total: 0, open: 0 });
  // 平均用时按已结实例数加权（每个流程的平均 × 该流程已结数），不是简单平均各流程的平均
  const closedOf = (s: ProcessStats) => s.total - s.open;
  const weighted = rows.filter((s) => s.avgCycleMs && closedOf(s) > 0);
  const closed = weighted.reduce((a, s) => a + closedOf(s), 0);
  const avg = closed ? weighted.reduce((a, s) => a + s.avgCycleMs! * closedOf(s), 0) / closed : undefined;

  const columns: Column<ProcessStats>[] = [
    { header: t("col.process"), cell: (s) => <span className="txt-strong">{s.process.name}</span> },
    { header: t("col.total"), className: "text-end", cell: (s) => <span className="tabular-nums">{s.total}</span> },
    { header: t("col.open"), className: "text-end", cell: (s) => <span className="tabular-nums">{s.open}</span> },
    {
      header: t("col.byCategory"),
      cell: (s) => <span className="txt-caption text-muted-foreground">
        {fmt.join(...CATS.filter((c) => s.byCategory[c]).map((c) => `${tc(`category.${c}`)} ${s.byCategory[c]}`))}
      </span>,
    },
    { header: t("col.avg"), cell: (s) => (s.avgCycleMs ? fmt.dur(s.avgCycleMs / MIN) : <span className="text-muted-foreground">{t("noAvg")}</span>) },
    {
      header: t("col.approvals"),
      cell: (s) => (
        <span className="inline-flex flex-wrap gap-1.5">
          {APPR.filter((a) => s.approvals[a]).map((a) => (
            <span key={a} className="inline-flex items-center gap-1">
              <StatusBadge map={approvalStatusMap(tc)} value={a} /><span className="tabular-nums">{s.approvals[a]}</span>
            </span>
          ))}
        </span>
      ),
    },
  ];

  return (
    <div>
      <PageTitle title={tc("page.stats")} desc={t("desc")} />
      <Toolbar>
        <FilterSelect aria-label={t("filter.process")} value={processCode} onChange={setProcessCode} options={processOptions} allLabel={t("filter.processAll")} />
        <FilterChips aria-label={t("filter.period")} value={period} onChange={(v) => setPeriod(v as (typeof PERIODS)[number])}
          options={PERIODS.map((p) => ({ value: p, label: t(`filter.${p}`) }))} />
      </Toolbar>
      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <SummaryCard label={t("card.total")} value={q.isLoading ? "–" : sum.total} />
        <SummaryCard label={t("card.open")} value={q.isLoading ? "–" : sum.open} />
        <SummaryCard label={t("card.closed")} value={q.isLoading ? "–" : sum.total - sum.open} />
        <SummaryCard label={t("card.avg")} value={q.isLoading ? "–" : avg ? fmt.dur(avg / MIN) : t("noAvg")} />
      </div>
      <Card className="p-0">
        <DataTable columns={columns} rows={q.data} loading={q.isLoading} error={q.error} onRetry={() => q.refetch()}
          rowKey={(s) => s.process.code} empty={t("empty")} />
      </Card>
    </div>
  );
}

export default function FlowStatsPage() {
  return <NeedPerm code="flow:process:manage" page="stats"><FlowStats /></NeedPerm>;
}
