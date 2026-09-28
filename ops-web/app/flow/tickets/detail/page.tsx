"use client";

// 工单详情（原型 P5）：对象 · 时间线（迁移 / 派单 / SLA / 去重 / 备注同一条流水）· SLA 各指标 · 动作 · 备注。
// 动作条 = 服务给的 actions（只含当前状态、当前操作人可走的迁移），前端不维护迁移表。
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { useCan } from "@/lib/hooks/use-can";
import { NeedPerm } from "@/components/flow/need-perm";
import { Suspense, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { actionVariant } from "@/lib/flow/present";
import { useFlowT, type FlowKey } from "@/lib/flow/i18n";
import { useMembers } from "@/lib/flow/options";
import type { Member, TicketAction } from "@/lib/flow/types";
import { notify } from "@/lib/notify";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { CheckboxField } from "@/components/ui/checkbox";
import { Badge } from "@/components/ui/badge";
import { useAuth } from "@/lib/auth";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { FlowTimeline } from "@/components/flow/flow-timeline";
import { PriorityBadge, TicketStatusBadge } from "@/components/flow/badges";
import { SlaCell } from "@/components/flow/sla-cell";

const POOL = "__pool__";
const memberLabel = (m: Pick<Member, "displayName" | "attrs">) =>
  [m.attrs?.partyType?.name ?? m.attrs?.partyType?.value, m.displayName].filter(Boolean).join(" · ");

/**
 * @form-none 提交到 OpsKit（POST /workspaces/{ws}/tickets/{no}/transitions/{code} 的 comment），
 * 不是 ShareHub 后端，contract.json 管不到；字段以 21/06 为准，经 lib/flow/client 转发。
 */
const reasonFields = (tt: (k: FlowKey<"ticketDetail">) => string): FieldDef[] => [
  { key: "reason", label: tt("field.reason.label"), type: "textarea", required: true, maxLength: 500, help: tt("field.reason.help") },
];

export default function Page() {
  return (
    <Suspense fallback={<Skeleton className="h-40" />}>
      <NeedPerm code="flow:ticket:read" page="ticketDetail"><TicketDetail /></NeedPerm>
    </Suspense>
  );
}

function TicketDetail() {
  const no = useSearchParams().get("no") ?? "";
  const qc = useQueryClient();
  const { t: tt, tc, fmt } = useFlowT("ticketDetail");
  const members = useMembers();
  const allow = useCan();
  const canDispatch = allow("flow:ticket:dispatch");
  const canHandle = allow("flow:ticket:handle");
  const refresh = () => qc.invalidateQueries({ queryKey: FLOW_KEY });
  const q = useQuery({ queryKey: [...FLOW_KEY, "ticket", no], queryFn: () => flow.ticket(no), enabled: !!no });
  const tl = useQuery({ queryKey: [...FLOW_KEY, "timeline", no], queryFn: () => flow.timeline(no), enabled: !!no });

  const [pending, setPending] = useState<TicketAction | null>(null);
  const [reasonForm, setReasonForm] = useState<Record<string, unknown>>({ reason: "" });
  const [assignOpen, setAssignOpen] = useState(false);
  const [assignForm, setAssignForm] = useState<Record<string, unknown>>({ to: "", reason: "" });
  const [note, setNote] = useState("");
  // 内部评论只有员工能发（OpsKit ticket:comment:internal 只映射给员工角色，§16.1）；代理发的一律公开。
  // 员工默认内部 —— 运营之间的沟通不该默认让代理看到，要给代理看时再勾掉
  const isAgent = useAuth((s) => s.realm) === "AGENT";
  const [internal, setInternal] = useState(true);

  const act = useMutation({
    mutationFn: ({ a, comment }: { a: TicketAction; comment?: string }) => flow.transition(no, a.code, comment),
    onSuccess: (_t, v) => { notify.success(tt("done", { action: v.a.name })); setPending(null); setReasonForm({ reason: "" }); refresh(); },
    onError: (e: Error) => { notify.error(e.message); refresh(); },
  });
  const assign = useMutation({
    mutationFn: () => {
      const reason = String(assignForm.reason ?? "");
      if (assignForm.to === POOL) return flow.pool(no, reason);
      const to = members.list.find((m) => m.ref === assignForm.to);
      if (!to) throw new Error(tt("assign.pickTarget"));
      return flow.assign(no, to, reason);
    },
    onSuccess: () => { notify.success(assignForm.to === POOL ? tt("assign.pooled") : tt("assign.reassigned")); setAssignOpen(false); setAssignForm({ to: "", reason: "" }); refresh(); },
    onError: (e: Error) => notify.error(e.message),
  });
  const comment = useMutation({
    mutationFn: () => flow.comment(no, note, !isAgent && internal ? "INTERNAL" : "PUBLIC"),
    onSuccess: () => { notify.success(tt("note.submitted")); setNote(""); refresh(); },
    onError: (e: Error) => notify.error(e.message),
  });

  if (!no) return <><ErrorState error={new Error(tt("missingNo"))} /></>;
  if (q.isLoading) return <><Skeleton className="h-40" /></>;
  if (q.error || !q.data) return <><ErrorState error={q.error} onRetry={() => q.refetch()} /></>;
  const t = q.data;
  const open = !["DONE", "CANCELLED"].includes(t.statusCategory);

  /**
   * @form-none 改派 / 退回抢单池提交到 OpsKit（POST /workspaces/{ws}/tickets/{no}/assign · /pool），
   * 不是 ShareHub 后端；字段以 21/06 为准。
   */
  const assignFields: FieldDef[] = [
    { key: "to", label: tt("field.to.label"), type: "select", required: true,
      help: members.truncated ? tc("membersTruncated", { n: members.list.length, total: members.total }) : undefined,
      options: [{ value: POOL, label: tt("field.to.pool") }, ...members.list.map((m) => ({ value: m.ref, label: memberLabel(m) }))] },
    { key: "reason", label: tt("field.reason.label"), type: "textarea", required: true, maxLength: 200, help: tt("field.reason.help") },
  ];

  const src = [t.fields.source?.name, t.fields.sourceRef?.value as string | undefined].filter(Boolean).join(" ");
  const cabinet = t.subject?.type === "cabinet" ? t.subject.ref : null;

  return (
    <div>
      <div className="mb-1 txt-caption text-muted-foreground">
        <Link href="/flow/tickets/" className="hover:text-foreground">{tc("page.tickets")}</Link> / {t.ticketNo}
      </div>
      <div className="mb-1 flex flex-wrap items-center gap-2">
        <h1 className="txt-title">{t.title}</h1>
        <TicketStatusBadge t={t} />
        <PriorityBadge p={t.priority} />
      </div>
      <p className="mb-4 txt-body text-muted-foreground">
        {fmt.join(t.ticketNo, tt("subtitle.type", { type: t.type.name }), src && tt("subtitle.source", { src }))}
        {t.dedupe && tt("subtitle.dedupe", { key: t.dedupe.key, source: t.fields.source?.name ?? "", n: t.dedupe.attachedCount })}
      </p>

      <div className="grid items-start gap-4 lg:grid-cols-[1fr_340px]">
        <div className="space-y-4">
          <Card className="p-5">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="txt-strong">{tt("section.subject")}</h2>
              {cabinet && (
                <Button asChild size="sm" variant="secondary">
                  <Link href={`/devices/detail/?no=${cabinet}`}>{tt("deviceProfile")}</Link>
                </Button>
              )}
            </div>
            <dl className="grid grid-cols-[88px_1fr] gap-x-3 gap-y-2 txt-body sm:grid-cols-[88px_1fr_88px_1fr]">
              {t.fieldList.map((f) => (
                <FieldPair key={f.code} label={f.label} value={f.value.name ?? String(f.value.value ?? "—")} />
              ))}
              {t.participants.map((p) => (
                <FieldPair key={p.role.code} label={p.role.name} value={p.member.displayName} />
              ))}
            </dl>
            {t.description && <p className="mt-3 txt-body text-muted-foreground">{t.description}</p>}
          </Card>

          <Card className="p-5">
            <h2 className="mb-3 txt-strong">{tt("section.timeline")}</h2>
            {tl.error ? <ErrorState error={tl.error} onRetry={() => tl.refetch()} /> : (
              <FlowTimeline
                empty={tl.isLoading ? tc("loading") : tt("timelineEmpty")}
                items={(tl.data ?? []).map((e) => ({
                  key: String(e.seq), title: e.title, hot: e.hot,
                  meta: fmt.join(fmt.when(e.atMs), e.actor?.displayName ?? tc("system"), e.kind !== "COMMENT" && e.detail),
                  detail: e.kind === "COMMENT" ? (e.visibility === "INTERNAL"
                    ? <><Badge tone="default" className="me-2">{tt("note.internalTag")}</Badge>{e.detail}</> : e.detail) : undefined,
                }))}
              />
            )}
          </Card>
        </div>

        <div className="space-y-4">
          <Card className="p-5">
            <h2 className="mb-3 txt-strong">SLA</h2>
            <dl className="grid grid-cols-[64px_1fr] gap-x-3 gap-y-2 txt-body">
              {t.slaMetrics.map((m) => (
                <FieldPair key={m.code} label={m.name} value={
                  <span className="inline-flex flex-wrap items-center gap-2">
                    <SlaCell sla={m} />
                    {m.state !== "MET" && <span className="txt-caption text-muted-foreground">{tt("dueBefore", { t: fmt.when(m.dueAtMs) })}</span>}
                  </span>
                } />
              ))}
              <FieldPair label={tc("sla.paused")} value={fmt.dur((t.sla?.pausedMs ?? 0) / 60_000)} />
            </dl>
          </Card>

          <Card className="p-5">
            <h2 className="mb-3 txt-strong">{tt("section.actions")} <span className="txt-caption text-muted-foreground">{tt("section.actionsHint")}</span></h2>
            {!open ? (
              <p className="txt-body text-muted-foreground">{t.resolution
                ? tt(t.statusCategory === "DONE" ? "closedWith.DONE" : "closedWith.CANCELLED", { r: t.resolution.name })
                : tt(t.statusCategory === "DONE" ? "closed.DONE" : "closed.CANCELLED")}</p>
            ) : (
              <div className="grid gap-2">
                {t.actions.map((a) => (
                  <Button
                    key={a.code}
                    variant={actionVariant(a, t.statusCategory)}
                    loading={act.isPending && act.variables?.a.code === a.code}
                    disabled={act.isPending}
                    onClick={() => (a.requires.comment ? setPending(a) : act.mutate({ a }))}
                  >
                    {a.requires.comment ? tt("needsReason", { action: a.name }) : a.name}
                  </Button>
                ))}
                {canDispatch && <Button variant="secondary" onClick={() => setAssignOpen(true)}>{tt("assign.btn")}</Button>}
              </div>
            )}
          </Card>

          <Card className="p-5">
            <h2 className="mb-3 txt-strong">{tt("section.note")}</h2>
            {canHandle ? (
              <>
                <Textarea value={note} onChange={setNote} rows={3} placeholder={tt(isAgent || !internal ? "note.placeholderPublic" : "note.placeholder")} />
                <div className="mt-2 flex items-center justify-end gap-3">
                  {!isAgent && (
                    <CheckboxField className="me-auto" label={tt("note.internal")} checked={internal} onChange={(v) => setInternal(v === true)} />
                  )}
                  <Button size="sm" loading={comment.isPending} disabled={!note.trim()} onClick={() => comment.mutate()}>{tt("note.submit")}</Button>
                </div>
              </>
            ) : (
              <ReadOnlyNotice what={tt("readOnly.what")} perm="flow:ticket:handle" note={tt("readOnly.note")} className="mb-0" />
            )}
          </Card>
        </div>
      </div>

      <FormDrawer
        open={!!pending}
        onOpenChange={(o) => { if (!o) setPending(null); }}
        titleNew={pending ? tt("reasonTitle", { action: pending.name, no: t.ticketNo }) : ""}
        titleEdit=""
        isEdit={false}
        fields={reasonFields(tt)}
        value={reasonForm}
        onChange={setReasonForm}
        onSubmit={() => pending && act.mutate({ a: pending, comment: String(reasonForm.reason ?? "") })}
        submitting={act.isPending}
      />
      <FormDrawer
        open={assignOpen}
        onOpenChange={setAssignOpen}
        titleNew={tt("assign.title", { no: t.ticketNo })}
        titleEdit=""
        isEdit={false}
        fields={assignFields}
        value={assignForm}
        onChange={setAssignForm}
        onSubmit={() => assign.mutate()}
        submitting={assign.isPending}
      />
    </div>
  );
}

function FieldPair({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0">{value}</dd>
    </>
  );
}
