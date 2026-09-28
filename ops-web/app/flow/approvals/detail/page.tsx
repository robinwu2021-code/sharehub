"use client";

// 审批详情（原型 P2）：业务对象 · 审批进度（含条件步）· 历史轮次（永不覆盖）· 我的决定 · 规则提示。
// 能不能决定、为什么不能，一律取服务给的 mine（规则在服务端强制，这里只呈现）。
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { useCan } from "@/lib/hooks/use-can";
import { NeedPerm } from "@/components/flow/need-perm";
import { Suspense, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { approvalStatusMap } from "@/lib/flow/labels";
import { useFlowT } from "@/lib/flow/i18n";
import { who } from "@/lib/flow/present";
import type { ApprovalView } from "@/lib/flow/types";
import { notify } from "@/lib/notify";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Notice } from "@/components/ui/notice";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import { StatusBadge } from "@/components/ui/status-badge";
import { StatusStepper } from "@/components/ui/status-stepper";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { FlowTimeline } from "@/components/flow/flow-timeline";

export default function Page() {
  return (
    <Suspense fallback={<Skeleton className="h-40" />}>
      <NeedPerm code="flow:approval:read" page="approvalDetail"><ApprovalDetail /></NeedPerm>
    </Suspense>
  );
}

function ApprovalDetail() {
  const no = useSearchParams().get("no") ?? "";
  const qc = useQueryClient();
  const { t: tt, tc, fmt, err } = useFlowT("approvalDetail");
  const allow = useCan();
  // 两层：有没有「审批」这项能力（权限码，这里判）× 是不是这一步的审批人（服务端 mine 判）
  const canDecidePerm = allow("flow:approval:decide");
  const { confirm, dialog } = useConfirm();
  const q = useQuery({ queryKey: [...FLOW_KEY, "approval", no], queryFn: () => flow.approval(no), enabled: !!no });
  const [reason, setReason] = useState("");
  const [rejectTried, setRejectTried] = useState(false);

  const done = (v: ApprovalView, what: string) => {
    notify.success(v.status === "PENDING" && v.currentStep ? tt("movedOn", { what, step: v.currentStep.name }) : what);
    setReason(""); setRejectTried(false);
    qc.invalidateQueries({ queryKey: FLOW_KEY });
  };
  const decide = useMutation({
    mutationFn: (d: "APPROVE" | "REJECT") => flow.decide(no, q.data!.mine.taskNo!, d, reason),
    onSuccess: (v, d) => done(v, tc(d === "APPROVE" ? "approvalStatus.APPROVED" : "approvalStatus.REJECTED")),
    onError: (e: Error) => { notify.error(e.message); qc.invalidateQueries({ queryKey: FLOW_KEY }); },
  });
  const withdraw = useMutation({
    mutationFn: () => flow.withdraw(no),
    onSuccess: (v) => done(v, tc("approvalStatus.WITHDRAWN")),
    onError: (e: Error) => notify.error(e.message),
  });

  if (!no) return <><ErrorState error={new Error(tt("missingNo"))} /></>;
  if (q.isLoading) return <><Skeleton className="h-40" /></>;
  if (q.error || !q.data) return <><ErrorState error={q.error} onRetry={() => q.refetch()} /></>;
  const a = q.data;

  // 进度：提交 + 各步；跳过的条件步不进步骤条，单独注明
  const active = a.steps.filter((s) => s.state !== "SKIPPED");
  const steps = [{ key: "__submit", label: tt("step.submit") }, ...active.map((s) => ({ key: s.code, label: s.name }))];
  const cur = a.steps.find((s) => s.state === "CURRENT");
  const current = a.status === "APPROVED" ? active.at(-1)?.code ?? "__submit" : cur?.code ?? "__submit";
  const branch = a.status === "REJECTED" ? { label: tc("approvalStatus.REJECTED"), after: cur?.code ?? active[0]?.code ?? "__submit" }
    : a.status === "WITHDRAWN" ? { label: tc("approvalStatus.WITHDRAWN"), after: "__submit" } : null;

  const rejectEmpty = rejectTried && !reason.trim();

  return (
    <div>
      <div className="mb-1 txt-caption text-muted-foreground">
        <Link href="/flow/approvals/" className="hover:text-foreground">{tc("page.approvals")}</Link> / {a.approvalNo}
      </div>
      <div className="mb-1 flex flex-wrap items-center gap-2">
        <h1 className="txt-title">{tt("heading", { name: a.process.name })}</h1>
        <StatusBadge map={approvalStatusMap(tc)} value={a.status} />
        <span className="txt-body text-muted-foreground">{tt("round", { n: a.round })}</span>
      </div>
      <p className="mb-4 txt-body text-muted-foreground">
        {fmt.join(tt("meta.no", { no: a.approvalNo }), tt("meta.process", { code: a.process.code }), tt("meta.by", { name: who(a.requestedBy) }), fmt.short(a.requestedAtMs))}
      </p>

      <div className="grid items-start gap-4 lg:grid-cols-[1fr_340px]">
        <div className="space-y-4">
          <Card className="p-5">
            <h2 className="mb-3 txt-strong">{tt("card.facts")}</h2>
            <dl className="grid grid-cols-[88px_1fr] gap-x-3 gap-y-2 txt-body sm:grid-cols-[88px_1fr_88px_1fr]">
              {a.facts.map((f) => (
                <Pair key={f.label} label={f.label}>
                  {f.value}{f.hint && <span className="ms-1 txt-caption text-warning-ink">{tt("paren", { text: f.hint })}</span>}
                </Pair>
              ))}
            </dl>
            {a.evidence.length > 0 && (
              <p className="mt-3 txt-caption text-muted-foreground">
                {tt("evidence", { list: fmt.join(...a.evidence.map((e) => (e.count ? `${e.name} ×${e.count}` : e.name))) })}
              </p>
            )}
          </Card>

          <Card className="p-5">
            <h2 className="mb-3 txt-strong">{tt("card.progress")}</h2>
            <StatusStepper steps={steps} current={current} branch={branch} />
            <ul className="mt-3 space-y-1 txt-caption text-muted-foreground">
              {a.steps.map((s) => (
                <li key={s.code}>
                  {s.name}{tc("colon")}{fmt.join(
                    s.approverText,
                    s.conditionText,
                    s.state === "SKIPPED" && tt("step.skipped"),
                    s.decidedBy && tt("step.decidedBy", { name: s.decidedBy }),
                  )}
                </li>
              ))}
            </ul>
            {a.rules.crossStepExclusive && (
              <Notice className="mb-0 mt-3">{tt("crossStepOn")}</Notice>
            )}
          </Card>

          <Card className="p-5">
            <h2 className="mb-3 txt-strong">{tt("card.rounds")} <span className="txt-caption text-muted-foreground">{tt("card.roundsNote")}</span></h2>
            <FlowTimeline
              empty={tt("emptyRounds")}
              items={[...a.rounds].reverse().map((r) => ({
                key: String(r.round),
                title: fmt.join(tt("round", { n: r.round }), r.status === "IN_PROGRESS" ? tc("approvalStatus.IN_PROGRESS") : approvalStatusMap(tc)[r.status].label),
                meta: fmt.join(fmt.short(r.atMs), r.by),
                detail: r.comment ? tt("quote", { text: r.comment }) : undefined,
              }))}
            />
          </Card>
        </div>

        <div className="space-y-4">
          <Card className="p-5">
            <h2 className="mb-3 txt-strong">{tt("card.mine")}</h2>
            {!canDecidePerm ? (
              <ReadOnlyNotice what={tt("readOnly.what")} perm="flow:approval:decide" note={tt("readOnly.note")} className="mb-0" />
            ) : a.mine.canDecide ? (
              <>
                <Textarea
                  value={reason}
                  onChange={setReason}
                  rows={3}
                  invalid={rejectEmpty}
                  placeholder={tt("placeholder")}
                />
                <p className={rejectEmpty ? "mt-1 txt-caption text-destructive-ink" : "mt-1 txt-caption text-muted-foreground"}>
                  {tt("rejectHint")}
                </p>
                <div className="mt-3 grid grid-cols-2 gap-2">
                  <Button loading={decide.isPending && decide.variables === "APPROVE"} disabled={decide.isPending}
                    onClick={() => decide.mutate("APPROVE")}>{tt("btn.approve")}</Button>
                  <Button variant="destructive" loading={decide.isPending && decide.variables === "REJECT"} disabled={decide.isPending}
                    onClick={() => { setRejectTried(true); if (reason.trim()) decide.mutate("REJECT"); }}>{tt("btn.reject")}</Button>
                </div>
              </>
            ) : (
              <div data-why={a.mine.whyNot ?? ""}><Notice className="mb-0">{a.mine.whyNot ? err(a.mine.whyNot) : tt("notApprover")}</Notice></div>
            )}
            {a.mine.canWithdraw && (
              <Button className="mt-3 w-full" variant="secondary" loading={withdraw.isPending}
                onClick={async () => {
                  if (await confirm({ title: tt("withdrawConfirm.title"), desc: tt("withdrawConfirm.desc"), confirmText: tt("btn.withdraw") })) withdraw.mutate();
                }}>
                {tt("btn.withdraw")}
              </Button>
            )}
          </Card>

          <Card className="p-5">
            <h2 className="mb-2 txt-strong">{tt("card.rules")} <span className="txt-caption text-muted-foreground">{tt("card.rulesNote")}</span></h2>
            <ul className="list-disc space-y-1 ps-5 txt-body text-muted-foreground">
              <li>{tt("rules.noSelf")}</li>
              <li>{tt("rules.withdraw")}</li>
              <li>{tt("rules.stale")}</li>
              {a.rules.crossStepExclusive && <li>{tt("rules.crossStep")}</li>}
            </ul>
          </Card>
        </div>
      </div>
      {dialog}
    </div>
  );
}

function Pair({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0">{children}</dd>
    </>
  );
}
