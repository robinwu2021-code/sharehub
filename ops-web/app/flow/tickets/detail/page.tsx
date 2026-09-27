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
import { actionVariant, fmtWhen } from "@/lib/flow/present";
import type { Member, TicketAction } from "@/lib/flow/types";
import { notify } from "@/lib/notify";
import { fmtMinutes } from "@/lib/types";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
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
const REASON_FIELDS: FieldDef[] = [
  { key: "reason", label: "原因", type: "textarea", required: true, maxLength: 500, help: "会记入工单时间线" },
];

export default function Page() {
  return (
    <Suspense fallback={<Skeleton className="h-40" />}>
      <NeedPerm code="flow:ticket:read" what="工单详情"><TicketDetail /></NeedPerm>
    </Suspense>
  );
}

function TicketDetail() {
  const no = useSearchParams().get("no") ?? "";
  const qc = useQueryClient();
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

  const act = useMutation({
    mutationFn: ({ a, comment }: { a: TicketAction; comment?: string }) => flow.transition(no, a.code, comment),
    onSuccess: (_t, v) => { notify.success(`已${v.a.name}`); setPending(null); setReasonForm({ reason: "" }); refresh(); },
    onError: (e: Error) => { notify.error(e.message); refresh(); },
  });
  const assign = useMutation({
    mutationFn: () => {
      const reason = String(assignForm.reason ?? "");
      if (assignForm.to === POOL) return flow.pool(no, reason);
      const to = flow.assigneeOptions().find((m) => m.ref === assignForm.to);
      if (!to) throw new Error("请选择改派对象");
      return flow.assign(no, to, reason);
    },
    onSuccess: () => { notify.success(assignForm.to === POOL ? "已退回抢单池" : "已改派"); setAssignOpen(false); setAssignForm({ to: "", reason: "" }); refresh(); },
    onError: (e: Error) => notify.error(e.message),
  });
  const comment = useMutation({
    mutationFn: () => flow.comment(no, note),
    onSuccess: () => { notify.success("备注已提交"); setNote(""); refresh(); },
    onError: (e: Error) => notify.error(e.message),
  });

  if (!no) return <><ErrorState error={new Error("缺少工单号：从工单工作台或待办中心进入")} /></>;
  if (q.isLoading) return <><Skeleton className="h-40" /></>;
  if (q.error || !q.data) return <><ErrorState error={q.error} onRetry={() => q.refetch()} /></>;
  const t = q.data;
  const open = !["DONE", "CANCELLED"].includes(t.statusCategory);

  /**
   * @form-none 改派 / 退回抢单池提交到 OpsKit（POST /workspaces/{ws}/tickets/{no}/assign · /pool），
   * 不是 ShareHub 后端；字段以 21/06 为准。
   */
  const assignFields: FieldDef[] = [
    { key: "to", label: "改派给", type: "select", required: true,
      options: [{ value: POOL, label: "退回抢单池（未派）" }, ...flow.assigneeOptions().map((m) => ({ value: m.ref, label: memberLabel(m) }))] },
    { key: "reason", label: "原因", type: "textarea", required: true, maxLength: 200, help: "会记入工单时间线" },
  ];

  const src = [t.fields.source?.name, t.fields.sourceRef?.value as string | undefined].filter(Boolean).join(" ");
  const cabinet = t.subject?.type === "cabinet" ? t.subject.ref : null;

  return (
    <div>
      <div className="mb-1 txt-caption text-muted-foreground">
        <Link href="/flow/tickets/" className="hover:text-foreground">工单工作台</Link> / {t.ticketNo}
      </div>
      <div className="mb-1 flex flex-wrap items-center gap-2">
        <h1 className="txt-title">{t.title}</h1>
        <TicketStatusBadge t={t} />
        <PriorityBadge p={t.priority} />
      </div>
      <p className="mb-4 txt-body text-muted-foreground">
        {t.ticketNo} · {t.type.name}工单{src && ` · 来源 ${src}`}
        {t.dedupe && `（去重键 ${t.dedupe.key}，重复${t.fields.source?.name ?? ""}已并入 ${t.dedupe.attachedCount} 次）`}
      </p>

      <div className="grid items-start gap-4 lg:grid-cols-[1fr_340px]">
        <div className="space-y-4">
          <Card className="p-5">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="txt-strong">对象</h2>
              {cabinet && (
                <Button asChild size="sm" variant="secondary">
                  <Link href={`/devices/detail/?no=${cabinet}`}>设备档案 ↗</Link>
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
            <h2 className="mb-3 txt-strong">时间线</h2>
            {tl.error ? <ErrorState error={tl.error} onRetry={() => tl.refetch()} /> : (
              <FlowTimeline
                empty={tl.isLoading ? "加载中…" : "还没有流转记录"}
                items={(tl.data ?? []).map((e) => ({
                  key: String(e.seq), title: e.title, hot: e.hot,
                  meta: `${fmtWhen(e.atMs)} · ${e.actor?.displayName ?? "系统"}${e.detail && e.kind !== "COMMENT" ? ` · ${e.detail}` : ""}`,
                  detail: e.kind === "COMMENT" ? e.detail : undefined,
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
                    {m.state !== "MET" && <span className="txt-caption text-muted-foreground">{fmtWhen(m.dueAtMs)} 前</span>}
                  </span>
                } />
              ))}
              <FieldPair label="已暂停" value={fmtMinutes((t.sla?.pausedMs ?? 0) / 60_000)} />
            </dl>
          </Card>

          <Card className="p-5">
            <h2 className="mb-3 txt-strong">动作 <span className="txt-caption text-muted-foreground">（仅当前状态可走的）</span></h2>
            {!open ? (
              <p className="txt-body text-muted-foreground">工单已{t.statusCategory === "DONE" ? "关闭" : "撤销"}{t.resolution ? `：${t.resolution.name}` : ""}，没有可走的动作。</p>
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
                    {a.name}{a.requires.comment ? "（需填原因）" : ""}
                  </Button>
                ))}
                {canDispatch && <Button variant="secondary" onClick={() => setAssignOpen(true)}>改派 / 回抢单池</Button>}
              </div>
            )}
          </Card>

          <Card className="p-5">
            <h2 className="mb-3 txt-strong">备注</h2>
            {canHandle ? (
              <>
                <Textarea value={note} onChange={setNote} rows={3} placeholder="添加备注：会进入时间线，受理方与运营都能看到" />
                <div className="mt-2 flex justify-end">
                  <Button size="sm" loading={comment.isPending} disabled={!note.trim()} onClick={() => comment.mutate()}>提交备注</Button>
                </div>
              </>
            ) : (
              <ReadOnlyNotice what="工单办理" perm="flow:ticket:handle" note="不能添加备注" className="mb-0" />
            )}
          </Card>
        </div>
      </div>

      <FormDrawer
        open={!!pending}
        onOpenChange={(o) => { if (!o) setPending(null); }}
        titleNew={pending ? `${pending.name}：${t.ticketNo}` : ""}
        titleEdit=""
        isEdit={false}
        fields={REASON_FIELDS}
        value={reasonForm}
        onChange={setReasonForm}
        onSubmit={() => pending && act.mutate({ a: pending, comment: String(reasonForm.reason ?? "") })}
        submitting={act.isPending}
      />
      <FormDrawer
        open={assignOpen}
        onOpenChange={setAssignOpen}
        titleNew={`改派：${t.ticketNo}`}
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
