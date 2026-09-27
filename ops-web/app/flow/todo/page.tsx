"use client";

// 待办中心（原型 P1）：审批与工单在同一张列表；只显示我够资格办、且在我数据范围内的。
// 范围与资格由服务从宿主令牌解析（21/06），页面只传「来源」与「是否可抢」。
import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { TODO_KIND } from "@/lib/flow/labels";
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
/** 空态要说清「为什么空」，而不是「暂无数据」 */
const EMPTY: Record<TabKey, string> = {
  all: "你现在没有待办 —— 新的审批与派给你的工单会出现在这里",
  process: "没有等你审批的单",
  ticket: "没有派给你、还没办完的工单",
  pool: "抢单池里暂时没有你够资格领的单",
};

export default function FlowTodoPage() {
  const qc = useQueryClient();
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
      notify.success(`已领取 ${t.ticketNo}，派给了你`);
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
    { key: "all", label: `全部${n(c?.all)}` },
    { key: "process", label: `审批${n(c?.process)}` },
    { key: "ticket", label: `工单${n(c?.ticket)}` },
    { key: "pool", label: `可抢单${n(c?.claimable)}` },
  ];

  const columns: Column<TodoItem>[] = [
    { header: "类型", cell: (t) => <StatusBadge map={TODO_KIND} value={t.claimable ? "POOL" : t.origin} /> },
    { header: "标题", cell: (t) => <span className="txt-strong">{t.title}</span> },
    { header: "业务对象", cell: (t) => t.subject.display.title },
    { header: "优先级", cell: (t) => <PriorityBadge p={t.priority} /> },
    {
      header: "剩余时限",
      cell: (t) => (
        <span className="inline-flex items-center gap-2">
          <SlaCell sla={t.sla} dueAtMs={t.dueAtMs} />
          {t.claimable && t.ticketNo && (
            <Button
              size="sm"
              variant="secondary"
              loading={claim.isPending && claim.variables === t.ticketNo}
              disabled={claim.isPending}
              onClick={() => claim.mutate(t.ticketNo!)}
            >
              抢单
            </Button>
          )}
        </span>
      ),
    },
  ];

  return (
    <div>
      <PageTitle title="待办中心" desc="审批与工单在同一张列表；只显示你够资格办、且在你数据范围内的。" />
      <Tabs
        tabs={tabs}
        value={tab}
        onChange={(k) => { setTab(k as TabKey); paging.reset(); }}
      />
      <PagedTable
        query={query}
        paging={paging}
        columns={columns}
        rowKey={(t) => t.taskNo}
        empty={EMPTY[tab]}
        footer={
          <p className="mt-3 txt-caption text-muted-foreground">
            没有待办 ≠ 没有工单 —— 全部工单去
            <Link href="/flow/tickets/" className="mx-1 text-primary underline-offset-2 hover:underline">工单工作台</Link>
            ；已办结的在各单详情的时间线里。
          </p>
        }
      />
    </div>
  );
}
