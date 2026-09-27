"use client";

// 工单工作台（原型 P4）：统计卡可点，点进即筛好的列表；SLA 色条：绿 正常 · 黄 将超时 · 红 已超时 · 灰 暂停。
// 每个筛选控件都对应 tickets/query 的一个参数（21/06），不做「取一页再页内过滤」。
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

// 筛选项都来自服务（工单类型 / 成员属性定义），不在前端内置业务词（21/06 §七）
const TYPE_OPTIONS = flow.ticketTypes().map((t) => ({ value: t.code, label: t.name }));
const PARTY_OPTIONS = flow.partyOptions().map((p) => ({ value: p.code, label: p.name }));
/** 受理方显示：成员属性 partyType 的显示名 + 成员名 */
const memberLabel = (m: Member) => [m.attrs?.partyType?.name, m.displayName].filter(Boolean).join(" · ");

/**
 * @form-none 批量改派提交到 OpsKit（opskit-svc 的 POST /workspaces/{ws}/tickets/reassign，21/06），
 * 不是 ShareHub 后端，contract.json 管不到；字段以 21/06 为准，经 lib/flow/client 转发。
 */
const REASSIGN_FIELDS: FieldDef[] = [
  { key: "to", label: "改派给", type: "select", required: true,
    options: flow.assigneeOptions().map((a) => ({ value: a.ref, label: memberLabel(a) })) },
  { key: "reason", label: "原因", type: "textarea", required: true, maxLength: 200,
    help: "会记入每张工单的时间线；例如「Musa 休假，片区改由 Omar 负责」" },
];

export default function FlowTicketsPage() {
  const qc = useQueryClient();
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
    onSuccess: (t, v) => { notify.success(`${t.ticketNo}：${actionDone(v.code)}`); refresh(); },
    onError: (e: Error) => { notify.error(e.message); refresh(); },
  });
  const reassign = useMutation({
    mutationFn: () => {
      const to = flow.assigneeOptions().find((a) => a.ref === reassignForm.to)
        ?? { ref: String(reassignForm.to ?? ""), displayName: String(reassignForm.to ?? "") };
      return flow.reassign(selected, to, String(reassignForm.reason ?? ""));
    },
    onSuccess: (list) => {
      notify.success(`已改派 ${list.length} 张工单`);
      setReassignOpen(false);
      setSelected([]);
      setReassignForm({ to: "", reason: "" });
      refresh();
    },
    onError: (e: Error) => notify.error(e.message),
  });

  const columns: Column<TicketRow>[] = [
    { header: "单号", pk: true, cell: (t) => t.ticketNo },
    { header: "类型", cell: (t) => <Badge tone="default">{t.type.name}</Badge> },
    { header: "标题 / 对象", cell: (t) => <span>{t.title}{t.subject && <> · <span className="text-muted-foreground">{t.subject.display.title}</span></>}</span> },
    { header: "优先级", cell: (t) => <PriorityBadge p={t.priority} /> },
    { header: "状态", cell: (t) => <TicketStatusBadge t={t} /> },
    {
      header: "受理方",
      cell: (t) => (t.assignee ? memberLabel(t.assignee) : <span className="text-muted-foreground">未派 · 抢单池</span>),
    },
    { header: "SLA", cell: (t) => <SlaCell sla={t.sla} state={t.slaState} dueAtMs={t.slaNextDueAtMs} /> },
    {
      header: "来源",
      cell: (t) => <span className="text-muted-foreground">{[t.fields.source?.name, t.fields.sourceRef?.value as string | undefined].filter(Boolean).join(" ")}</span>,
    },
    {
      header: "操作",
      className: "text-end",
      cell: (t) => (
        <span className="inline-flex justify-end gap-2">
          {(t.actions ?? []).map((a) => (
            <Button
              key={a.code}
              size="sm"
              variant={a.toCategory === "DONE" ? "default" : "secondary"}
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
      <PageTitle title="工单工作台" desc="统计卡可点，点进即筛好的列表。SLA 色条：绿 正常 · 黄 将超时 · 红 已超时 · 灰 暂停。" />
      <StatStrip
        stats={stats.data}
        loading={stats.isLoading}
        active={card?.key ?? null}
        onPick={(s) => onFilter(() => { setCard(s); if (s) { setTypeCode(""); setAssigneeKind(""); } })}
      />
      <Toolbar
        search={keyword}
        onSearch={(v) => onFilter(() => setKeyword(v))}
        searchPlaceholder="搜单号 / 标题 / 对象 / 受理方"
        selectedCount={selected.length}
        onClearSelection={() => setSelected([])}
        batchActions={<Button size="sm" onClick={() => setReassignOpen(true)}>批量改派</Button>}
      >
        <FilterSelect
          aria-label="工单类型"
          value={typeCode}
          onChange={(v) => onFilter(() => setTypeCode(v))}
          options={TYPE_OPTIONS}
          allLabel="全部类型"
        />
        <FilterSelect
          aria-label="受理方"
          value={assigneeKind}
          onChange={(v) => onFilter(() => setAssigneeKind(v))}
          options={PARTY_OPTIONS}
          allLabel="受理方：全部"
        />
      </Toolbar>
      <PagedTable
        query={query}
        paging={paging}
        columns={columns}
        rowKey={(t) => t.ticketNo}
        selectable
        selectedKeys={selected}
        onSelectedChange={setSelected}
        empty={card || typeCode || assigneeKind || keyword ? "按当前筛选没有工单 —— 换个条件，或点已选中的统计卡取消筛选" : "还没有工单"}
      />
      <FormDrawer
        open={reassignOpen}
        onOpenChange={setReassignOpen}
        titleNew={`批量改派 ${selected.length} 张工单`}
        titleEdit=""
        isEdit={false}
        fields={REASSIGN_FIELDS}
        value={reassignForm}
        onChange={setReassignForm}
        onSubmit={() => reassign.mutate()}
        submitting={reassign.isPending}
      />
    </div>
  );
}

function actionDone(code: string) {
  return { NUDGE: "已催办受理方", ESCALATE: "已升级给平台", TAKEOVER: "已接管，派给了你", REVIEW: "复核通过，工单已关闭" }[code] ?? "已完成";
}
