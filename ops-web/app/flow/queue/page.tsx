"use client";

// 审核队列（原型 P3）：领取后才能决定；领取超时 30 分钟自动放回并留痕（服务端读时放回，这里只显示倒计时）。
import { NeedPerm } from "@/components/flow/need-perm";
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import type { QueueTask } from "@/lib/flow/types";
import { usePaging } from "@/lib/hooks/use-paging";
import { notify } from "@/lib/notify";
import { useFlowT } from "@/lib/flow/i18n";
import { who } from "@/lib/flow/present";
import { PageTitle } from "@/components/ui/misc";
import { Tabs } from "@/components/ui/tabs";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { PagedTable } from "@/components/ui/paged-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { PriorityBadge } from "@/components/flow/badges";

const MIN = 60_000;

/**
 * @form-none 驳回原因提交到 OpsKit（POST /workspaces/{ws}/tasks/{no}/complete），
 * 不是 ShareHub 后端，contract.json 管不到；字段以 21/06 为准。
 */
const rejectFields = (tt: (k: "reject.reason.label" | "reject.reason.help") => string): FieldDef[] => [
  { key: "reason", label: tt("reject.reason.label"), type: "textarea", required: true, maxLength: 500, help: tt("reject.reason.help") },
];

/** 每秒走一次的「现在」，给倒计时用 */
function useNow() {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(id); }, []);
  return now;
}

const mmss = (ms: number) => {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
};

function FlowQueue() {
  const qc = useQueryClient();
  const { t: tt, tc, fmt } = useFlowT("queue");
  const now = useNow();
  const paging = usePaging();
  const [queue, setQueue] = useState("merchant");
  const [rejecting, setRejecting] = useState<QueueTask | null>(null);
  const [form, setForm] = useState<Record<string, unknown>>({ reason: "" });
  const refresh = () => qc.invalidateQueries({ queryKey: FLOW_KEY });

  const queues = useQuery({ queryKey: [...FLOW_KEY, "queue-counts"], queryFn: flow.queueCounts });
  const mine = useQuery({ queryKey: [...FLOW_KEY, "my-claimed"], queryFn: flow.myClaimed });
  const list = useQuery({
    queryKey: [...FLOW_KEY, "queue-tasks", queue, paging.page, paging.size],
    queryFn: () => flow.queueTasks(queue, paging.page, paging.size),
  });

  // 倒计时走到 0：服务端会在下次读时放回；这里主动刷新一次，让「我在审」及时变化
  const expired = (mine.data ?? []).some((t) => t.claimExpiresAtMs && t.claimExpiresAtMs <= now);
  useEffect(() => { if (expired) refresh(); }, [expired]); // eslint-disable-line react-hooks/exhaustive-deps

  const onError = (e: Error) => { notify.error(e.message); refresh(); };
  const claim = useMutation({ mutationFn: flow.claimTask, onSuccess: () => { notify.success(tt("toast.claimed")); refresh(); }, onError });
  const release = useMutation({ mutationFn: flow.releaseTask, onSuccess: () => { notify.success(tt("toast.released")); refresh(); }, onError });
  const complete = useMutation({
    mutationFn: ({ t, d, reason }: { t: QueueTask; d: "APPROVE" | "REJECT"; reason?: string }) => flow.completeTask(t.taskNo, d, reason),
    onSuccess: (_v, { d }) => { notify.success(d === "APPROVE" ? tt("toast.approved") : tt("toast.rejected")); setRejecting(null); setForm({ reason: "" }); refresh(); },
    onError,
  });

  const busy = claim.isPending || release.isPending || complete.isPending;
  const age = (t: QueueTask) => fmt.dur((now - t.createdAtMs) / MIN);

  const mineCols: Column<QueueTask>[] = [
    { header: tt("col.subject"), cell: (t) => <span className="txt-strong">{t.subject.display.title}</span> },
    { header: tt("col.priority"), cell: (t) => <PriorityBadge p={t.priority} /> },
    { header: tt("col.wait"), cell: (t) => <span className="tabular-nums">{age(t)}</span> },
    { header: tt("col.evidence"), cell: (t) => <span className="text-muted-foreground">{t.evidenceSummary ?? tc("none")}</span> },
    {
      header: tt("col.actions"), className: "text-end",
      cell: (t) => (
        <span className="inline-flex gap-2">
          <Button size="sm" disabled={busy} onClick={() => complete.mutate({ t, d: "APPROVE" })}>{tt("btn.approve")}</Button>
          <Button size="sm" variant="destructive" disabled={busy} onClick={() => setRejecting(t)}>{tt("btn.reject")}</Button>
          <Button size="sm" variant="secondary" disabled={busy} onClick={() => release.mutate(t.taskNo)}>{tt("btn.release")}</Button>
        </span>
      ),
    },
  ];

  const queueCols: Column<QueueTask>[] = [
    { header: tt("col.subject"), cell: (t) => t.subject.display.title },
    { header: tt("col.priority"), cell: (t) => <PriorityBadge p={t.priority} /> },
    { header: tt("col.wait"), cell: (t) => <span className="tabular-nums">{age(t)}</span> },
    { header: tt("col.status"), cell: (t) => (t.claimedBy ? <span className="text-muted-foreground">{tt("status.reviewing", { name: who(t.claimedBy) })}</span> : tt("status.unclaimed")) },
    {
      header: tt("col.actions"), className: "text-end",
      cell: (t) => (
        <Button size="sm" variant="secondary" disabled={busy || !!t.claimedBy}
          loading={claim.isPending && claim.variables === t.taskNo} onClick={() => claim.mutate(t.taskNo)}>
          {tt("btn.claim")}
        </Button>
      ),
    },
  ];

  const qname = queues.data?.find((q) => q.queue.code === queue)?.queue.name ?? "";
  const myList = mine.data ?? [];
  const soonest = myList.map((t) => t.claimExpiresAtMs ?? Infinity).sort((a, b) => a - b)[0];

  return (
    <div>
      <PageTitle title={tc("page.queue")} desc={tt("desc")} />
      <Tabs
        tabs={(queues.data ?? []).map((q) => ({ key: q.queue.code, label: `${q.queue.name}${tc("sep")}${tt("tab.unclaimed", { n: q.unclaimed })}` }))}
        value={queue}
        onChange={(k) => { setQueue(k); paging.reset(); }}
      />

      <Card className="mb-4 p-5">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <h2 className="txt-strong">{tt("mine.title", { n: myList.length })}</h2>
          {soonest !== undefined && Number.isFinite(soonest) && (
            <span className="txt-caption text-warning-ink tabular-nums">{tt("mine.countdown", { t: mmss(soonest - now) })}</span>
          )}
        </div>
        <DataTable
          columns={mineCols}
          rows={myList}
          loading={mine.isLoading}
          error={mine.error}
          onRetry={() => mine.refetch()}
          rowKey={(t) => t.taskNo}
          empty={tt("mine.empty")}
        />
      </Card>

      <h2 className="mb-2 txt-strong">{tt("queue.title")}{tc("sep")}{qname} <span className="txt-caption text-muted-foreground">{tt("queue.sortHint")}</span></h2>
      <PagedTable
        query={list}
        paging={paging}
        columns={queueCols}
        rowKey={(t) => t.taskNo}
        empty={tt("queue.empty")}
      />

      <FormDrawer
        open={!!rejecting}
        onOpenChange={(o) => { if (!o) setRejecting(null); }}
        titleNew={rejecting ? tt("reject.title", { title: rejecting.subject.display.title }) : ""}
        titleEdit=""
        isEdit={false}
        fields={rejectFields(tt)}
        value={form}
        onChange={setForm}
        onSubmit={() => rejecting && complete.mutate({ t: rejecting, d: "REJECT", reason: String(form.reason ?? "") })}
        submitting={complete.isPending}
      />
    </div>
  );
}

export default function FlowQueuePage() {
  return <NeedPerm code="flow:queue:claim" page="queue"><FlowQueue /></NeedPerm>;
}
