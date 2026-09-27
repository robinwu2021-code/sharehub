"use client";

// 审批列表（原型 P2a）：全部审批单（不只「待我审」），按数据范围过滤。
// 每个筛选控件对应 approvals/query 的一个参数（21/06 §四：status[] · processCode · requestedBy · from · keyword）。
import { useMemo, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { APPROVAL_STATUS } from "@/lib/flow/labels";
import { fmtShort } from "@/lib/flow/present";
import type { ApprovalRow, ApprovalStatus } from "@/lib/flow/types";
import { usePaging } from "@/lib/hooks/use-paging";
import { fmtMinutes } from "@/lib/types";
import { PageTitle } from "@/components/ui/misc";
import { Tabs } from "@/components/ui/tabs";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { PagedTable } from "@/components/ui/paged-table";
import type { Column } from "@/components/ui/data-table";
import { StatusBadge } from "@/components/ui/status-badge";
import { Badge } from "@/components/ui/badge";
import { FlowNav } from "@/components/flow/flow-nav";

type TabKey = ApprovalStatus | "ALL";
const TABS: { key: TabKey; label: string }[] = [
  { key: "PENDING", label: "待审" }, { key: "APPROVED", label: "已通过" }, { key: "REJECTED", label: "已驳回" },
  { key: "WITHDRAWN", label: "已撤回" }, { key: "ALL", label: "全部" },
];
const DAY = 86_400_000;
const SINCE = [{ value: "7", label: "提交时间：近 7 天" }, { value: "30", label: "提交时间：近 30 天" }];
const MIN = 60_000;

export default function FlowApprovalsPage() {
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
    { header: "审批单", pk: true, cell: (a) => <Link href={`/flow/approvals/detail/?no=${a.approvalNo}`} className="text-primary underline-offset-2 hover:underline">{a.approvalNo}</Link> },
    { header: "流程", cell: (a) => a.process.name },
    { header: "业务对象", cell: (a) => a.subject.display.title },
    { header: "状态", cell: (a) => <StatusBadge map={APPROVAL_STATUS} value={a.status} /> },
    { header: "当前步 / 轮次", cell: (a) => `${a.currentStep?.name ?? "—"} · 第 ${a.currentRound} 轮` },
    { header: "提交人", cell: (a) => a.requestedBy.displayName },
    { header: "提交时间", cell: (a) => <span className="tabular-nums">{fmtShort(a.requestedAtMs)}</span> },
    {
      header: "已等待",
      cell: (a) => {
        if (a.status === "PENDING" && a.stepStartedAtMs) {
          const text = fmtMinutes((now - a.stepStartedAtMs) / MIN);
          return a.stepDueAtMs && now > a.stepDueAtMs
            ? <Badge tone="warning" title="当前步已超过步骤时限">{text}</Badge>
            : <span className="tabular-nums">{text}</span>;
        }
        return a.finishedAtMs && a.status !== "WITHDRAWN"
          ? <span className="tabular-nums text-muted-foreground">耗时 {fmtMinutes((a.finishedAtMs - a.requestedAtMs) / MIN)}</span>
          : <span className="text-muted-foreground">—</span>;
      },
    },
  ];

  const c = count.data;
  return (
    <div>
      <FlowNav />
      <PageTitle title="审批列表" desc="全部审批单（不只「待我审」）；按数据范围过滤。筛选条件即查询接口参数，一一对应。" />
      <Tabs
        tabs={TABS.map((t) => ({ key: t.key, label: t.key !== "ALL" && c ? `${t.label} ${c[t.key]}` : t.label }))}
        value={tab}
        onChange={(k) => onFilter(() => setTab(k as TabKey))}
      />
      <Toolbar search={keyword} onSearch={(v) => onFilter(() => setKeyword(v))} searchPlaceholder="搜单号 / 对象编号 / 标题">
        <FilterSelect aria-label="流程" value={processCode} onChange={(v) => onFilter(() => setProcessCode(v))}
          options={flow.processOptions().map((p) => ({ value: p.code, label: p.name }))} allLabel="流程：全部" />
        <FilterSelect aria-label="提交人" value={requestedBy} onChange={(v) => onFilter(() => setRequestedBy(v))}
          options={flow.requesterOptions().map((p) => ({ value: p.code, label: p.name }))} allLabel="提交人：全部" />
        <FilterSelect aria-label="提交时间" value={since} onChange={(v) => onFilter(() => setSince(v))} options={SINCE} allLabel="提交时间：不限" />
      </Toolbar>
      <PagedTable
        query={query}
        paging={paging}
        columns={columns}
        rowKey={(a) => a.approvalNo}
        empty={processCode || requestedBy || since || keyword ? "按当前筛选没有审批单 —— 换个条件试试" : tab === "PENDING" ? "没有待审的审批单" : "这个状态下没有审批单"}
        footer={<p className="mt-3 txt-caption text-muted-foreground">「已等待」= 当前步开始至今；已结单显示总耗时。点单号进详情。</p>}
      />
    </div>
  );
}
