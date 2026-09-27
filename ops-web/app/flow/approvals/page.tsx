"use client";

// 审批列表（原型 P2a）：全部审批单（不只「待我审」），按数据范围过滤。
// 每个筛选控件对应 approvals/query 的一个参数（21/06 §四：status[] · processCode · requestedBy · from · keyword）。
import { NeedPerm } from "@/components/flow/need-perm";
import { useMemo, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { approvalStatusMap } from "@/lib/flow/labels";
import { useFlowT } from "@/lib/flow/i18n";
import type { ApprovalRow, ApprovalStatus } from "@/lib/flow/types";
import { usePaging } from "@/lib/hooks/use-paging";
import { PageTitle } from "@/components/ui/misc";
import { Tabs } from "@/components/ui/tabs";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { PagedTable } from "@/components/ui/paged-table";
import type { Column } from "@/components/ui/data-table";
import { StatusBadge } from "@/components/ui/status-badge";
import { Badge } from "@/components/ui/badge";

type TabKey = ApprovalStatus | "ALL";
const TAB_KEYS: TabKey[] = ["PENDING", "APPROVED", "REJECTED", "WITHDRAWN", "ALL"];
const DAY = 86_400_000;
const MIN = 60_000;

function FlowApprovals() {
  const { t: tt, tc, fmt } = useFlowT("approvals");
  const TABS = TAB_KEYS.map((key) => ({ key, label: key === "ALL" ? tt("tab.all") : tc(`approvalStatus.${key}`) }));
  const SINCE = [{ value: "7", label: tt("since.d7") }, { value: "30", label: tt("since.d30") }];
  const paging = usePaging();
  const [tab, setTab] = useState<TabKey>("PENDING");
  const [processCode, setProcessCode] = useState("");
  const [requestedBy, setRequestedBy] = useState("");
  const [since, setSince] = useState("");
  const [keyword, setKeyword] = useState("");

  const filter = useMemo(() => ({
    ...(tab !== "ALL" ? { status: [tab] } : {}),
    ...(processCode ? { processCode } : {}),
    ...(requestedBy ? { requestedBy } : {}),
    ...(since ? { fromMs: Date.now() - Number(since) * DAY } : {}),
    ...(keyword.trim() ? { keyword: keyword.trim() } : {}),
  }), [tab, processCode, requestedBy, since, keyword]);

  const count = useQuery({ queryKey: [...FLOW_KEY, "approval-count"], queryFn: flow.approvalCount });
  const query = useQuery({
    queryKey: [...FLOW_KEY, "approvals", filter, paging.page, paging.size],
    queryFn: () => flow.approvals({ ...filter, page: paging.page, size: paging.size }),
  });
  const onFilter = (fn: () => void) => { fn(); paging.reset(); };

  const now = Date.now();
  const columns: Column<ApprovalRow>[] = [
    { header: tt("col.approvalNo"), pk: true, cell: (a) => <Link href={`/flow/approvals/detail/?no=${a.approvalNo}`} className="text-primary underline-offset-2 hover:underline">{a.approvalNo}</Link> },
    { header: tt("col.process"), cell: (a) => a.process.name },
    { header: tt("col.subject"), cell: (a) => a.subject.display.title },
    { header: tt("col.status"), cell: (a) => <StatusBadge map={approvalStatusMap(tc)} value={a.status} /> },
    { header: tt("col.stepRound"), cell: (a) => fmt.join(a.currentStep?.name ?? tc("none"), tt("round", { n: a.round })) },
    { header: tt("col.requestedBy"), cell: (a) => a.requestedBy.displayName },
    { header: tt("col.requestedAt"), cell: (a) => <span className="tabular-nums">{fmt.short(a.requestedAtMs)}</span> },
    {
      header: tt("col.waited"),
      cell: (a) => {
        if (a.status === "PENDING" && a.stepStartedAtMs) {
          const text = fmt.dur((now - a.stepStartedAtMs) / MIN);
          return a.stepDueAtMs && now > a.stepDueAtMs
            ? <Badge tone="warning" title={tt("overdueTitle")}>{text}</Badge>
            : <span className="tabular-nums">{text}</span>;
        }
        return a.finishedAtMs && a.status !== "WITHDRAWN"
          ? <span className="tabular-nums text-muted-foreground">{tt("took", { d: fmt.dur((a.finishedAtMs - a.requestedAtMs) / MIN) })}</span>
          : <span className="text-muted-foreground">{tc("none")}</span>;
      },
    },
  ];

  const c = count.data;
  return (
    <div>
      <PageTitle title={tc("page.approvals")} desc={tt("desc")} />
      <Tabs
        tabs={TABS.map((t) => ({ key: t.key, label: t.key !== "ALL" && c ? `${t.label} ${c[t.key]}` : t.label }))}
        value={tab}
        onChange={(k) => onFilter(() => setTab(k as TabKey))}
      />
      <Toolbar search={keyword} onSearch={(v) => onFilter(() => setKeyword(v))} searchPlaceholder={tt("search")}>
        <FilterSelect aria-label={tt("col.process")} value={processCode} onChange={(v) => onFilter(() => setProcessCode(v))}
          options={flow.processOptions().map((p) => ({ value: p.code, label: p.name }))} allLabel={tt("filterAll.process")} />
        <FilterSelect aria-label={tt("col.requestedBy")} value={requestedBy} onChange={(v) => onFilter(() => setRequestedBy(v))}
          options={flow.requesterOptions().map((p) => ({ value: p.code, label: p.name }))} allLabel={tt("filterAll.requestedBy")} />
        <FilterSelect aria-label={tt("col.requestedAt")} value={since} onChange={(v) => onFilter(() => setSince(v))} options={SINCE} allLabel={tt("filterAll.since")} />
      </Toolbar>
      <PagedTable
        query={query}
        paging={paging}
        columns={columns}
        rowKey={(a) => a.approvalNo}
        empty={processCode || requestedBy || since || keyword ? tt("empty.filtered") : tab === "PENDING" ? tt("empty.pending") : tt("empty.status")}
        footer={<p className="mt-3 txt-caption text-muted-foreground">{tt("footer")}</p>}
      />
    </div>
  );
}

export default function FlowApprovalsPage() {
  return <NeedPerm code="flow:approval:read" page="approvals"><FlowApprovals /></NeedPerm>;
}
