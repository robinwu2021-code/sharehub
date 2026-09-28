"use client";

// 工单工作台（原型 P4）：统计卡可点，点进即筛好的列表；SLA 色条：绿 正常 · 黄 将超时 · 红 已超时 · 灰 暂停。
// 每个筛选控件都对应 tickets/query 的一个参数（21/06），不做「取一页再页内过滤」。
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { useCan } from "@/lib/hooks/use-can";
import { NeedPerm } from "@/components/flow/need-perm";
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import type { Member, TicketRow, TicketFilter, TicketStat } from "@/lib/flow/types";
import { usePaging } from "@/lib/hooks/use-paging";
import { notify } from "@/lib/notify";
import { PageTitle } from "@/components/ui/misc";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { PagedTable } from "@/components/ui/paged-table";
import type { Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { SlaCell } from "@/components/flow/sla-cell";
import { StatStrip } from "@/components/flow/stat-strip";
import { PriorityBadge, TicketStatusBadge } from "@/components/flow/badges";
import { actionVariant, inlineActions } from "@/lib/flow/present";
import { useFlowT, type FlowKey } from "@/lib/flow/i18n";
import { useMembers, usePartyOptions, useTicketTypes } from "@/lib/flow/options";
import Link from "next/link";

/** 受理方显示：成员属性 partyType 的显示名（未定义显示名时用原值）+ 成员名 */
const memberLabel = (m: Member) => {
  const party = m.attrs?.partyType;
  return [party?.name ?? party?.value, m.displayName].filter(Boolean).join(" · ");
};

/**
 * @form-none 批量改派提交到 OpsKit（opskit-svc 的 POST /workspaces/{ws}/tickets/reassign，21/06），
 * 不是 ShareHub 后端，contract.json 管不到；字段以 21/06 为准，经 lib/flow/client 转发。
 */
const reassignFields = (tt: TicketsT, members: Member[], membersHelp?: string): FieldDef[] => [
  { key: "to", label: tt("reassign.to"), type: "select", required: true, help: membersHelp,
    options: members.map((a) => ({ value: a.ref, label: memberLabel(a) })) },
  { key: "reason", label: tt("reassign.reason"), type: "textarea", required: true, maxLength: 200,
    help: tt("reassign.reasonHelp") },
];
type TicketsT = (k: FlowKey<"tickets">, p?: Record<string, string | number>) => string;

function FlowTickets() {
  const qc = useQueryClient();
  const { t: tt, tc } = useFlowT("tickets");
  const allow = useCan();
  // 筛选项都来自服务（工单类型 / 成员属性定义），不在前端内置业务词（21/06 §七）
  const typeOptions = useTicketTypes().map((t) => ({ value: t.code, label: t.name }));
  const partyOptions = usePartyOptions().map((p) => ({ value: p.code, label: p.name }));
  const members = useMembers();
  // 行内的迁移动作（接单 / 升级 / 复核…）不在前端判权：服务端给的 actions[] 已按当前操作人算好，
  // 前端再判一遍只会与服务端口径打架。前端只管不属于迁移的能力 —— 批量改派。
  const canDispatch = allow("flow:ticket:dispatch");
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [typeCode, setTypeCode] = useState("");
  const [assigneeKind, setAssigneeKind] = useState("");
  /** 统计卡带来的筛选（与上面两个下拉互斥：点卡 = 换成卡的口径） */
  const [card, setCard] = useState<TicketStat | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [reassignOpen, setReassignOpen] = useState(false);
  const [reassignForm, setReassignForm] = useState<Record<string, unknown>>({ to: "", reason: "" });

  const filter: TicketFilter = useMemo(() => ({
    ...(card?.queryParams ?? {}),
    ...(typeCode ? { typeCode: [typeCode] } : {}),
    ...(assigneeKind ? { assigneeAttr: { key: "partyType", values: [assigneeKind] } } : {}),
    ...(keyword.trim() ? { keyword: keyword.trim() } : {}),
  }), [card, typeCode, assigneeKind, keyword]);

  const stats = useQuery({ queryKey: [...FLOW_KEY, "ticket-stats"], queryFn: flow.ticketStats });
  const query = useQuery({
    queryKey: [...FLOW_KEY, "tickets", filter, paging.page, paging.size],
    queryFn: () => flow.tickets({ ...filter, page: paging.page, size: paging.size }),
  });

  const refresh = () => qc.invalidateQueries({ queryKey: FLOW_KEY });
  const act = useMutation({
    mutationFn: ({ no, code }: { no: string; code: string }) => flow.transition(no, code),
    onSuccess: (t, v) => { notify.success(`${t.ticketNo}${tc("colon")}${actionDone(tt, v.code)}`); refresh(); },
    onError: (e: Error) => { notify.error(e.message); refresh(); },
  });
  const reassign = useMutation({
    mutationFn: () => {
      const to = members.list.find((a) => a.ref === reassignForm.to)
        ?? { ref: String(reassignForm.to ?? ""), displayName: String(reassignForm.to ?? "") };
      return flow.reassign(selected, to, String(reassignForm.reason ?? ""));
    },
    // 逐单结果：单张失败不回滚其余，失败的逐条说出来（常见是「已结」）
    onSuccess: (r) => {
      if (r.failed.length) {
        notify.error(tt("reassign.partial", { ok: r.succeeded.length, n: r.failed.length,
          list: r.failed.map((f) => `${f.ticketNo}${tc("colon")}${f.error}`).join(tc("listSep")) }));
      } else {
        notify.success(tt("reassign.done", { n: r.succeeded.length }));
      }
      setReassignOpen(false);
      setSelected([]);
      setReassignForm({ to: "", reason: "" });
      refresh();
    },
    onError: (e: Error) => notify.error(e.message),
  });

  const columns: Column<TicketRow>[] = [
    { header: tt("col.no"), pk: true, cell: (t) => <Link href={`/flow/tickets/detail/?no=${t.ticketNo}`} className="text-primary underline-offset-2 hover:underline">{t.ticketNo}</Link> },
    { header: tt("col.type"), cell: (t) => <Badge tone="default">{t.type.name}</Badge> },
    { header: tt("col.titleSubject"), cell: (t) => <span>{t.title}{t.subject && <> · <span className="text-muted-foreground">{t.subject.display.title}</span></>}</span> },
    { header: tt("col.priority"), cell: (t) => <PriorityBadge p={t.priority} /> },
    { header: tt("col.status"), cell: (t) => <TicketStatusBadge t={t} /> },
    {
      header: tt("col.assignee"),
      cell: (t) => (t.assignee ? memberLabel(t.assignee) : <span className="text-muted-foreground">{tt("unassigned")}</span>),
    },
    { header: tt("col.sla"), cell: (t) => <SlaCell sla={t.sla} state={t.slaState} dueAtMs={t.slaNextDueAtMs} /> },
    {
      header: tt("col.source"),
      cell: (t) => <span className="text-muted-foreground">{[t.fields.source?.name, t.fields.sourceRef?.value as string | undefined].filter(Boolean).join(" ")}</span>,
    },
    {
      header: tt("col.actions"),
      className: "text-end",
      cell: (t) => (
        <span className="inline-flex justify-end gap-2">
          {inlineActions(t.actions).map((a) => (
            <Button
              key={a.code}
              size="sm"
              variant={actionVariant(a, t.statusCategory)}
              loading={act.isPending && act.variables?.no === t.ticketNo && act.variables?.code === a.code}
              disabled={act.isPending}
              onClick={() => act.mutate({ no: t.ticketNo, code: a.code })}
            >
              {a.name}
            </Button>
          ))}
        </span>
      ),
    },
  ];

  const onFilter = (fn: () => void) => { fn(); paging.reset(); setSelected([]); };

  return (
    <div>
      <PageTitle title={tc("page.tickets")} desc={tt("desc")} />
      <StatStrip
        stats={stats.data}
        loading={stats.isLoading}
        active={card?.key ?? null}
        onPick={(s) => onFilter(() => { setCard(s); if (s) { setTypeCode(""); setAssigneeKind(""); } })}
      />
      {!canDispatch && (
        <ReadOnlyNotice what={tt("readOnly.what")} perm="flow:ticket:dispatch" note={tt("readOnly.note")} className="mb-3" />
      )}
      <Toolbar
        search={keyword}
        onSearch={(v) => onFilter(() => setKeyword(v))}
        searchPlaceholder={tt("searchPlaceholder")}
        selectedCount={selected.length}
        onClearSelection={() => setSelected([])}
        batchActions={canDispatch ? <Button size="sm" onClick={() => setReassignOpen(true)}>{tt("reassign.btn")}</Button> : undefined}
      >
        <FilterSelect
          aria-label={tt("filter.type")}
          value={typeCode}
          onChange={(v) => onFilter(() => setTypeCode(v))}
          options={typeOptions}
          allLabel={tt("filter.typeAll")}
        />
        <FilterSelect
          aria-label={tt("filter.assignee")}
          value={assigneeKind}
          onChange={(v) => onFilter(() => setAssigneeKind(v))}
          options={partyOptions}
          allLabel={tt("filter.assigneeAll")}
        />
      </Toolbar>
      <PagedTable
        query={query}
        paging={paging}
        columns={columns}
        rowKey={(t) => t.ticketNo}
        selectable={canDispatch}
        selectedKeys={selected}
        onSelectedChange={setSelected}
        empty={card || typeCode || assigneeKind || keyword ? tt("empty.filtered") : tt("empty.none")}
      />
      <FormDrawer
        open={reassignOpen}
        onOpenChange={setReassignOpen}
        titleNew={tt("reassign.title", { n: selected.length })}
        titleEdit=""
        isEdit={false}
        fields={reassignFields(tt, members.list, members.truncated ? tc("membersTruncated", { n: members.list.length, total: members.total }) : undefined)}
        value={reassignForm}
        onChange={setReassignForm}
        onSubmit={() => reassign.mutate()}
        submitting={reassign.isPending}
      />
    </div>
  );
}

const DONE_CODES = ["NUDGE", "ESCALATE", "TAKEOVER", "REVIEW"] as const;
function actionDone(tt: TicketsT, code: string) {
  const known = DONE_CODES.find((c) => c === code);
  return tt(known ? `done.${known}` : "done.other");
}

export default function FlowTicketsPage() {
  return <NeedPerm code="flow:ticket:read" page="tickets"><FlowTickets /></NeedPerm>;
}
