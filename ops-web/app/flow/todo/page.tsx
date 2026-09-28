"use client";

// 待办中心（原型 P1）：审批与工单在同一张列表；只显示我够资格办、且在我数据范围内的。
// 范围与资格由服务从宿主令牌解析（21/06），页面只传「来源」与「是否可抢」。
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { useCan } from "@/lib/hooks/use-can";
import { NeedPerm } from "@/components/flow/need-perm";
import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { todoKindMap } from "@/lib/flow/labels";
import { useFlowT } from "@/lib/flow/i18n";
import type { TodoItem, TodoQuery } from "@/lib/flow/types";
import { usePaging } from "@/lib/hooks/use-paging";
import { notify } from "@/lib/notify";
import { PageTitle } from "@/components/ui/misc";
import { Tabs } from "@/components/ui/tabs";
import { PagedTable } from "@/components/ui/paged-table";
import type { Column } from "@/components/ui/data-table";
import { StatusBadge } from "@/components/ui/status-badge";
import { Button } from "@/components/ui/button";
import { SlaCell } from "@/components/flow/sla-cell";
import { PriorityBadge } from "@/components/flow/badges";

type TabKey = "all" | "process" | "ticket" | "pool";
const TAB_QUERY: Record<TabKey, Pick<TodoQuery, "origin" | "claimable">> = {
  all: { origin: "ALL" },
  process: { origin: "PROCESS" },
  ticket: { origin: "TICKET", claimable: false },
  pool: { origin: "ALL", claimable: true },
};

function FlowTodo() {
  const qc = useQueryClient();
  const { t: tt, tc } = useFlowT("todo");
  const allow = useCan();
  // 抢单 = 领取后自己办理，与「办理」同一个码（功能权限清单 §16）
  const canHandle = allow("flow:ticket:handle");
  const paging = usePaging();
  const [tab, setTab] = useState<TabKey>("all");

  const count = useQuery({ queryKey: [...FLOW_KEY, "todo-count"], queryFn: flow.todoCount });
  const query = useQuery({
    queryKey: [...FLOW_KEY, "todos", tab, paging.page, paging.size],
    queryFn: () => flow.todos({ ...TAB_QUERY[tab], page: paging.page, size: paging.size }),
  });

  const claim = useMutation({
    mutationFn: (no: string) => flow.claim(no),
    onSuccess: (t) => {
      notify.success(tt("claimed", { no: t.ticketNo }));
      qc.invalidateQueries({ queryKey: FLOW_KEY });
    },
    onError: (e: Error) => {
      notify.error(e.message);
      qc.invalidateQueries({ queryKey: FLOW_KEY });
    },
  });

  const c = count.data;
  const n = (v?: number) => (v === undefined ? "" : ` ${v}`);
  const tabs = [
    { key: "all", label: `${tt("tab.all")}${n(c?.all)}` },
    { key: "process", label: `${tt("tab.process")}${n(c?.process)}` },
    { key: "ticket", label: `${tt("tab.ticket")}${n(c?.ticket)}` },
    { key: "pool", label: `${tt("tab.pool")}${n(c?.claimable)}` },
  ];

  const columns: Column<TodoItem>[] = [
    { header: tt("col.kind"), cell: (t) => <StatusBadge map={todoKindMap(tc)} value={t.claimable && t.origin === "TICKET" ? "POOL" : t.origin} /> },
    {
      header: tt("col.title"),
      cell: (t) => {
        const href = t.ticketNo ? `/flow/tickets/detail/?no=${t.ticketNo}` : t.approvalNo ? `/flow/approvals/detail/?no=${t.approvalNo}` : null;
        // 可抢的**工单**要先抢单才能办，不给链接；审批候选任务（服务标 claimable）直接进详情决定
        return href && !(t.claimable && t.origin === "TICKET")
          ? <Link href={href} className="txt-strong text-primary underline-offset-2 hover:underline">{t.title}</Link>
          : <span className="txt-strong">{t.title}</span>;
      },
    },
    { header: tt("col.subject"), cell: (t) => t.subject.display.title },
    { header: tt("col.priority"), cell: (t) => <PriorityBadge p={t.priority} /> },
    {
      header: tt("col.sla"),
      cell: (t) => (
        <span className="inline-flex items-center gap-2">
          <SlaCell sla={t.sla} dueAtMs={t.dueAtMs} />
          {t.claimable && t.ticketNo && canHandle && (
            <Button
              size="sm"
              variant="secondary"
              loading={claim.isPending && claim.variables === t.ticketNo}
              disabled={claim.isPending}
              onClick={() => claim.mutate(t.ticketNo!)}
            >
              {tt("claim")}
            </Button>
          )}
        </span>
      ),
    },
  ];

  return (
    <div>
      <PageTitle title={tc("page.todo")} desc={tt("desc")} />
      <Tabs
        tabs={tabs}
        value={tab}
        onChange={(k) => { setTab(k as TabKey); paging.reset(); }}
      />
      {!canHandle && tab === "pool" && (
        <ReadOnlyNotice what={tt("readOnly.what")} perm="flow:ticket:handle" note={tt("readOnly.note")} className="mb-3" />
      )}
      <PagedTable
        query={query}
        paging={paging}
        columns={columns}
        rowKey={(t) => t.taskNo}
        empty={tt(`empty.${tab}`)}
        footer={
          <p className="mt-3 txt-caption text-muted-foreground">
            {tt("footer.before")}
            <Link href="/flow/tickets/" className="mx-1 text-primary underline-offset-2 hover:underline">{tt("footer.link")}</Link>
            {tt("footer.after")}
          </p>
        }
      />
    </div>
  );
}

export default function FlowTodoPage() {
  return <NeedPerm code="flow:todo:read" page="todo"><FlowTodo /></NeedPerm>;
}
