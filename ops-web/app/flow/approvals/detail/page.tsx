"use client";

// 审批详情（原型 P2）：业务对象 · 审批进度（含条件步）· 历史轮次（永不覆盖）· 我的决定 · 规则提示。
// 能不能决定、为什么不能，一律取服务给的 mine（规则在服务端强制，这里只呈现）。
import { Suspense, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { APPROVAL_STATUS } from "@/lib/flow/labels";
import { fmtShort } from "@/lib/flow/present";
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
import { FlowNav } from "@/components/flow/flow-nav";
import { FlowTimeline } from "@/components/flow/flow-timeline";

export default function Page() {
  return (
    <Suspense fallback={<Skeleton className="h-40" />}>
      <ApprovalDetail />
    </Suspense>
  );
}

function ApprovalDetail() {
  const no = useSearchParams().get("no") ?? "";
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const q = useQuery({ queryKey: [...FLOW_KEY, "approval", no], queryFn: () => flow.approval(no), enabled: !!no });
  const [reason, setReason] = useState("");
  const [rejectTried, setRejectTried] = useState(false);

  const done = (v: ApprovalView, what: string) => {
    notify.success(v.status === "PENDING" && v.currentStep ? `${what}，已进入「${v.currentStep.name}」步` : what);
    setReason(""); setRejectTried(false);
    qc.invalidateQueries({ queryKey: FLOW_KEY });
  };
  const decide = useMutation({
    mutationFn: (d: "APPROVE" | "REJECT") => flow.decide(no, q.data!.mine.taskNo!, d, reason),
    onSuccess: (v, d) => done(v, d === "APPROVE" ? "已通过" : "已驳回"),
    onError: (e: Error) => { notify.error(e.message); qc.invalidateQueries({ queryKey: FLOW_KEY }); },
  });
  const withdraw = useMutation({
    mutationFn: () => flow.withdraw(no),
    onSuccess: (v) => done(v, "已撤回"),
    onError: (e: Error) => notify.error(e.message),
  });

  if (!no) return <><FlowNav /><ErrorState error={new Error("缺少审批单号：从审批列表或待办中心进入")} /></>;
  if (q.isLoading) return <><FlowNav /><Skeleton className="h-40" /></>;
  if (q.error || !q.data) return <><FlowNav /><ErrorState error={q.error} onRetry={() => q.refetch()} /></>;
  const a = q.data;

  // 进度：提交 + 各步；跳过的条件步不进步骤条，单独注明
  const active = a.steps.filter((s) => s.state !== "SKIPPED");
  const steps = [{ key: "__submit", label: "提交" }, ...active.map((s) => ({ key: s.code, label: s.name }))];
  const cur = a.steps.find((s) => s.state === "CURRENT");
  const current = a.status === "APPROVED" ? active.at(-1)?.code ?? "__submit" : cur?.code ?? "__submit";
  const branch = a.status === "REJECTED" ? { label: "已驳回", after: cur?.code ?? active[0]?.code ?? "__submit" }
    : a.status === "WITHDRAWN" ? { label: "已撤回", after: "__submit" } : null;

  const rejectEmpty = rejectTried && !reason.trim();

  return (
    <div>
      <FlowNav />
      <div className="mb-1 txt-caption text-muted-foreground">
        <Link href="/flow/approvals/" className="hover:text-foreground">审批列表</Link> / {a.approvalNo}
      </div>
      <div className="mb-1 flex flex-wrap items-center gap-2">
        <h1 className="txt-title">{a.process.name}审批</h1>
        <StatusBadge map={APPROVAL_STATUS} value={a.status} />
        <span className="txt-body text-muted-foreground">第 {a.currentRound} 轮</span>
      </div>
      <p className="mb-4 txt-body text-muted-foreground">
        审批单 {a.approvalNo} · 流程 {a.process.code} · 提交人 {a.requestedBy.displayName} · {fmtShort(a.requestedAtMs)}
      </p>

      <div className="grid items-start gap-4 lg:grid-cols-[1fr_340px]">
        <div className="space-y-4">
          <Card className="p-5">
            <h2 className="mb-3 txt-strong">业务对象</h2>
            <dl className="grid grid-cols-[88px_1fr] gap-x-3 gap-y-2 txt-body sm:grid-cols-[88px_1fr_88px_1fr]">
              {a.facts.map((f) => (
                <Pair key={f.label} label={f.label}>
                  {f.value}{f.hint && <span className="ms-1 txt-caption text-warning-ink">（{f.hint}）</span>}
                </Pair>
              ))}
            </dl>
            {a.evidence.length > 0 && (
              <p className="mt-3 txt-caption text-muted-foreground">
                证据：{a.evidence.map((e) => (e.count ? `${e.name} ×${e.count}` : e.name)).join(" · ")}
              </p>
            )}
          </Card>

          <Card className="p-5">
            <h2 className="mb-3 txt-strong">审批进度</h2>
            <StatusStepper steps={steps} current={current} branch={branch} />
            <ul className="mt-3 space-y-1 txt-caption text-muted-foreground">
              {a.steps.map((s) => (
                <li key={s.code}>
                  {s.name}：{s.approver}{s.condition ? ` · ${s.condition}` : ""}
                  {s.state === "SKIPPED" ? " · 进入条件不满足，已跳过" : ""}
                  {s.decidedBy ? ` · 决定人 ${s.decidedBy}` : ""}
                </li>
              ))}
            </ul>
            {a.rules.crossStepExclusive && (
              <Notice className="mb-0 mt-3">跨步互斥已开启：前一步的决定人不能再审后一步。</Notice>
            )}
          </Card>

          <Card className="p-5">
            <h2 className="mb-3 txt-strong">历史轮次 <span className="txt-caption text-muted-foreground">（永不覆盖）</span></h2>
            <FlowTimeline
              empty="还没有轮次记录"
              items={[...a.rounds].reverse().map((r) => ({
                key: String(r.round),
                title: `第 ${r.round} 轮 · ${r.status === "IN_PROGRESS" ? "审批中" : APPROVAL_STATUS[r.status].label}`,
                meta: `${fmtShort(r.atMs)} · ${r.by}`,
                detail: r.comment ? `「${r.comment}」` : undefined,
              }))}
            />
          </Card>
        </div>

        <div className="space-y-4">
          <Card className="p-5">
            <h2 className="mb-3 txt-strong">我的决定</h2>
            {a.mine.canDecide ? (
              <>
                <Textarea
                  value={reason}
                  onChange={setReason}
                  rows={3}
                  invalid={rejectEmpty}
                  placeholder="意见（驳回时必填）"
                />
                <p className={rejectEmpty ? "mt-1 txt-caption text-destructive-ink" : "mt-1 txt-caption text-muted-foreground"}>
                  驳回必须填写原因 —— 只说「已驳回」，提交人无从改正后重提。
                </p>
                <div className="mt-3 grid grid-cols-2 gap-2">
                  <Button loading={decide.isPending && decide.variables === "APPROVE"} disabled={decide.isPending}
                    onClick={() => decide.mutate("APPROVE")}>通过</Button>
                  <Button variant="destructive" loading={decide.isPending && decide.variables === "REJECT"} disabled={decide.isPending}
                    onClick={() => { setRejectTried(true); if (reason.trim()) decide.mutate("REJECT"); }}>驳回</Button>
                </div>
              </>
            ) : (
              <Notice className="mb-0">{a.mine.whyNot ?? "你不是这一步的审批人"}</Notice>
            )}
            {a.mine.canWithdraw && (
              <Button className="mt-3 w-full" variant="secondary" loading={withdraw.isPending}
                onClick={async () => {
                  if (await confirm({ title: "撤回这张审批单？", desc: "撤回后本轮结束；需要时可在业务系统里重新提交。", confirmText: "撤回" })) withdraw.mutate();
                }}>
                撤回
              </Button>
            )}
          </Card>

          <Card className="p-5">
            <h2 className="mb-2 txt-strong">规则提示 <span className="txt-caption text-muted-foreground">（内建）</span></h2>
            <ul className="list-disc space-y-1 ps-5 txt-body text-muted-foreground">
              <li>提交人不能审批自己提交的单</li>
              <li>撤回仅提交人可用，且仅在待审时</li>
              <li>他人已处理时提交会提示「请刷新」</li>
              {a.rules.crossStepExclusive && <li>跨步互斥：前一步的决定人不能审后一步</li>}
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
