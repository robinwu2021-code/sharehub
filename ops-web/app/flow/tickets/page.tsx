"use client";

// 工单工作台（原型 P4）：统计卡可点，点进即筛好的列表；SLA 色条：绿 正常 · 黄 将超时 · 红 已超时 · 灰 暂停。
// 每个筛选控件都对应 tickets/query 的一个参数（21/06），不做「取一页再页内过滤」。
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { useCan } from "@/lib/hooks/use-can";
import { NeedPerm } from "@/components/flow/need-perm";
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import type { Member, TicketFieldType, TicketRow, TicketFilter, TicketStat, TicketTypeDef, TicketTypeOption } from "@/lib/flow/types";
import { usePaging } from "@/lib/hooks/use-paging";
import { notify } from "@/lib/notify";
import { isValidationError } from "@/lib/flow/errors";
import { PageTitle } from "@/components/ui/misc";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterChips } from "@/components/ui/filter-chips";
import { FilterSelect } from "@/components/ui/filter-select";
import { PagedTable } from "@/components/ui/paged-table";
import type { Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Plus } from "lucide-react";
import { FormDrawer, type FieldDef, type FieldType } from "@/components/ui/form-drawer";
import { SlaCell } from "@/components/flow/sla-cell";
import { StatStrip } from "@/components/flow/stat-strip";
import { PriorityBadge, TicketStatusBadge } from "@/components/flow/badges";
import { actionVariant, inlineActions } from "@/lib/flow/present";
import { useFlowT, type FlowKey } from "@/lib/flow/i18n";
import { useMembers, usePartyOptions, useTicketTypes } from "@/lib/flow/options";
import Link from "next/link";
import { useRouter } from "next/navigation";

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

const SUBJECT_TYPES = ["cabinet", "site", "user"] as const;
/** 类型字段在表单里的键前缀：与固定项（title、priority…）隔开，字段代号由租户定义，可能重名 */
const FIELD_KEY = "f.";
/** OpsKit 字段类型 → 表单控件。日期时间、金额、成员、组、附件、引用建单页暂不收（帮助文字里点名，不静默丢） */
const FORM_TYPE: Partial<Record<TicketFieldType, FieldType>> = {
  TEXT: "text", LONG_TEXT: "textarea", NUMBER: "number", SELECT: "select", MULTI_SELECT: "multiselect",
};
const TEXT_MAX = 255;
/**
 * 必填下拉要带空项：没有空项时，状态是空、浏览器却显示第一项 —— 用户以为选了，提交报「不能为空」，
 * 且再选同一项 React 不认为有变化（9-28 e2e 实测）。
 */
const usable = (pick: string, o?: TicketTypeOption[]) =>
  [{ value: "", label: pick }, ...(o ?? []).filter((x) => x.enabled).map((x) => ({ value: x.code, label: x.name }))];
/**
 * @form-none 手工建单提交到 OpsKit（opskit-svc 的 POST /workspaces/{ws}/tickets，21/06 §五），
 * 不是 ShareHub 后端，contract.json 管不到；字段以 21/06 为准，经 lib/flow/client 转发。
 * 表单由所选工单类型驱动：优先级取类型的选项集，类型里 onCreate 的字段逐个出控件（必填照类型）。
 */
const createFields = (tt: TicketsT, listSep: string, types: TicketTypeDef[], type?: TicketTypeDef): FieldDef[] => {
  const onCreate = (type?.fields ?? []).filter((f) => f.onCreate);
  const skipped = onCreate.filter((f) => !FORM_TYPE[f.type]);
  return [
    { key: "typeCode", label: tt("create.field.typeCode"), type: "select", required: true,
      options: [{ value: "", label: tt("create.field.pick") }, ...types.filter((t) => t.enabled).map((t) => ({ value: t.code, label: t.name }))],
      help: skipped.length ? tt("create.field.skipped", { list: skipped.map((f) => f.name).join(listSep) }) : undefined },
    { key: "title", label: tt("create.field.title"), required: true, maxLength: 80, help: tt("create.field.titleHelp") },
    { key: "priority", label: tt("create.field.priority"), type: "select", required: true,
      options: usable(tt("create.field.pick"), type?.priorityOptions), disabledWhen: (v) => !v.typeCode },
    ...onCreate.filter((f) => FORM_TYPE[f.type]).map((f): FieldDef => ({
      key: FIELD_KEY + f.code, label: f.name, type: FORM_TYPE[f.type], required: f.required,
      ...(f.options ? { options: f.type === "SELECT" ? usable(tt("create.field.pick"), f.options) : usable("", f.options).slice(1) } : {}),
      ...(f.type === "TEXT" ? { maxLength: TEXT_MAX } : {}),
    })),
    { key: "subjectType", label: tt("create.field.subjectType"), type: "select",
      options: [{ value: "", label: tt("create.subject.none") }, ...SUBJECT_TYPES.map((s) => ({ value: s, label: tt(`create.subject.${s}`) }))] },
    { key: "subjectRef", label: tt("create.field.subjectRef"), help: tt("create.field.subjectRefHelp"), disabledWhen: (v) => !v.subjectType },
    { key: "description", label: tt("create.field.description"), type: "textarea", rows: 4, maxLength: 1000 },
    { key: "dedupeKey", label: tt("create.field.dedupeKey"), help: tt("create.field.dedupeKeyHelp"), maxLength: 128 },
  ];
};

/** 表单值 → 类型字段的原始值（与 OpsKit FieldValues 的入参同形：选项给代号、多选给代号数组、数字给数值）；空值不发 */
function typeFieldValues(type: TicketTypeDef | undefined, form: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const f of type?.fields ?? []) {
    if (!f.onCreate || !FORM_TYPE[f.type]) continue;
    const v = form[FIELD_KEY + f.code];
    if (v == null || v === "" || (Array.isArray(v) && v.length === 0)) continue;
    if (f.type === "NUMBER") out[f.code] = Number(v);
    else if (f.type === "MULTI_SELECT") out[f.code] = Array.isArray(v) ? v : String(v).split(",").filter(Boolean);
    else out[f.code] = typeof v === "string" ? v.trim() : v;
  }
  return out;
}

function FlowTickets() {
  const qc = useQueryClient();
  const { t: tt, tc, fmt } = useFlowT("tickets");
  const allow = useCan();
  // 筛选项都来自服务（工单类型 / 成员属性定义），不在前端内置业务词（21/06 §七）
  const ticketTypes = useTicketTypes();
  const typeOptions = ticketTypes.map((t) => ({ value: t.code, label: t.name }));
  const partyOptions = usePartyOptions().map((p) => ({ value: p.code, label: p.name }));
  const members = useMembers();
  // 行内的迁移动作（接单 / 升级 / 复核…）不在前端判权：服务端给的 actions[] 已按当前操作人算好，
  // 前端再判一遍只会与服务端口径打架。前端只管不属于迁移的能力 —— 批量改派。
  const canDispatch = allow("flow:ticket:dispatch");
  // 建单与办理同一个码：客服报障是手工建单的主要来源，而客服没有派单码（功能权限清单 §16）
  const canCreate = allow("flow:ticket:handle");
  const router = useRouter();
  const [createOpen, setCreateOpen] = useState(false);
  const blankCreate = { typeCode: "", title: "", priority: "", subjectType: "", subjectRef: "", description: "", dedupeKey: "" };
  const [createForm, setCreateForm] = useState<Record<string, unknown>>(blankCreate);
  const createType = ticketTypes.find((t) => t.code === createForm.typeCode);
  /** 换类型：优先级与类型字段都属于旧类型，清掉（留着会提交别的类型的代号，被服务拒） */
  const onCreateChange = (next: Record<string, unknown>) => {
    if (next.typeCode === createForm.typeCode) return setCreateForm(next);
    const kept = Object.fromEntries(Object.entries(next).filter(([k]) => !k.startsWith(FIELD_KEY)));
    setCreateForm({ ...kept, priority: "" });
  };
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [typeCode, setTypeCode] = useState("");
  const [assigneeKind, setAssigneeKind] = useState("");
  /** 统计卡带来的筛选（与上面两个下拉互斥：点卡 = 换成卡的口径） */
  const [card, setCard] = useState<TicketStat | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  // 勾选列按需出现（原型做法，用户 2026-09-28 定）：常驻的一列勾选框是「线条感」的一大来源，
  // 而批量改派是低频操作。点「批量改派」进入选择模式，改派完成或取消即退出。
  const [selecting, setSelecting] = useState(false);
  const exitSelecting = () => { setSelecting(false); setSelected([]); };
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
  const create = useMutation({
    mutationFn: () => {
      const f = createForm;
      const str = (k: string) => String(f[k] ?? "").trim();
      return flow.createTicket({
        typeCode: str("typeCode"), title: str("title"), priority: str("priority") || undefined,
        ...(() => { const fields = typeFieldValues(createType, f); return Object.keys(fields).length ? { fields } : {}; })(),
        ...(str("description") ? { description: str("description") } : {}),
        ...(str("subjectType") && str("subjectRef") ? { subject: { type: str("subjectType"), ref: str("subjectRef") } } : {}),
        ...(str("dedupeKey") ? { dedupeKey: str("dedupeKey") } : {}),
      });
    },
    onSuccess: (r) => {
      if (r.attached) notify.info(tt("create.attached", { no: r.ticket.ticketNo }));
      // 派单策略可能建单即派人（OpsKit 的类型可配），按返回的受理人如实说
      else if (r.ticket.assignee) notify.success(tt("create.doneAssigned", { no: r.ticket.ticketNo, name: r.ticket.assignee.displayName }));
      else notify.success(tt("create.done", { no: r.ticket.ticketNo }));
      setCreateOpen(false);
      refresh();
      router.push(`/flow/tickets/detail/?no=${r.ticket.ticketNo}`);
    },
    // 422 逐条说「哪个字段：什么问题」—— 路径按表单反查标签（fields.<code> → 类型字段名）
    onError: (e: Error) => {
      if (!isValidationError(e)) return notify.error(e.message);
      const defs = createFields(tt, tc("listSep"), ticketTypes, createType);
      const label = (path: string) => {
        const key = path.startsWith("fields.") ? FIELD_KEY + path.slice(7) : path === "subject" ? "subjectRef" : path;
        return defs.find((d) => d.key === key)?.label ?? path;
      };
      notify.error(`${e.message}${tc("colon")}${e.errors.map((x) => `${label(x.path)}${tc("colon")}${x.error}`).join(tc("listSep"))}`);
    },
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
      exitSelecting();
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
      <PageTitle title={tc("page.tickets")} desc={tt("desc")}
        action={canCreate ? <Button size="sm" onClick={() => { setCreateForm(blankCreate); setCreateOpen(true); }}><Plus className="size-4" /> {tt("create.btn")}</Button> : undefined} />
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
        onClearSelection={exitSelecting}
        batchActions={canDispatch ? <Button size="sm" onClick={() => setReassignOpen(true)}>{tt("reassign.btn")}</Button> : undefined}
      >
        <FilterChips
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
        {canDispatch && (selecting ? (
          <span className="ms-auto inline-flex items-center gap-2">
            <span className="txt-caption text-muted-foreground">{tt("reassign.pickHint")}</span>
            <Button size="sm" variant="ghost" onClick={exitSelecting}>{tt("reassign.cancel")}</Button>
          </span>
        ) : (
          <Button size="sm" variant="secondary" className="ms-auto" onClick={() => setSelecting(true)}>{tt("reassign.btn")}</Button>
        ))}
      </Toolbar>
      <PagedTable
        query={query}
        paging={paging}
        columns={columns}
        rowKey={(t) => t.ticketNo}
        selectable={canDispatch && selecting}
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
      <FormDrawer
        open={createOpen}
        onOpenChange={setCreateOpen}
        titleNew={tt("create.title")}
        titleEdit=""
        isEdit={false}
        fields={createFields(tt, tc("listSep"), ticketTypes, createType)}
        value={createForm}
        onChange={onCreateChange}
        onSubmit={() => create.mutate()}
        submitting={create.isPending}
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
