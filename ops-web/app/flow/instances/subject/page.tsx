"use client";

// 对账（后端 O2-10 GET /subjects/{type}/{ref}/history）：一个业务对象名下的全部流程实例、审批与流转。
// 业务系统与平台对不上时，拿这一页核：它在平台里走过哪些流程、每一步谁在什么时候做的。
import { NeedPerm } from "@/components/flow/need-perm";
import { Suspense } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { useFlowT } from "@/lib/flow/i18n";
import { approvalStatusMap } from "@/lib/flow/labels";
import { who } from "@/lib/flow/present";
import { Card } from "@/components/ui/card";
import { Notice } from "@/components/ui/notice";
import { ErrorState, EmptyState, Skeleton } from "@/components/ui/misc";
import { StatusBadge } from "@/components/ui/status-badge";
import { TicketStatusBadge } from "@/components/flow/badges";
import { HistoryTimeline } from "@/components/flow/history-timeline";

export default function FlowSubjectPage() {
  return (
    <Suspense fallback={<Skeleton className="h-40" />}>
      <NeedPerm code="flow:approval:read" page="subject"><SubjectHistoryView /></NeedPerm>
    </Suspense>
  );
}

function SubjectHistoryView() {
  const sp = useSearchParams();
  const type = sp.get("type") ?? "", ref = sp.get("ref") ?? "";
  const { t, tc, fmt } = useFlowT("instances");
  const q = useQuery({ queryKey: [...FLOW_KEY, "subject", type, ref], queryFn: () => flow.subjectHistory(type, ref), enabled: !!type && !!ref });

  if (!type || !ref) return <ErrorState error={new Error(t("subject.missing"))} />;
  if (q.isLoading) return <Skeleton className="h-40" />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const s = q.data;

  return (
    <div>
      <div className="mb-1 txt-caption text-muted-foreground">
        <Link href="/flow/instances/" className="hover:text-foreground">{tc("page.instances")}</Link> / {tc("page.subject")}
      </div>
      <h1 className="mb-1 txt-title">{t("subject.title", { type: s.type, ref: s.ref })}</h1>
      <p className="mb-4 txt-body text-muted-foreground">{fmt.join(t("subject.hint"), t("subject.total", { n: s.total }))}</p>
      {s.truncated && <Notice className="mb-4">{t("subject.truncated", { n: s.instances.length })}</Notice>}
      {s.instances.length === 0 ? <EmptyState title={t("subject.empty")} /> : (
        <div className="space-y-4">
          {s.instances.map(({ instance: r, approvals, history }) => (
            <Card key={r.instanceNo} className="p-5">
              <div className="mb-3 flex flex-wrap items-center gap-2">
                <Link href={`/flow/instances/detail/?no=${r.instanceNo}`} className="txt-strong text-primary underline-offset-2 hover:underline">{r.instanceNo}</Link>
                <span className="txt-body">{r.process.name}</span>
                <TicketStatusBadge t={r} />
                <span className="txt-caption text-muted-foreground">{fmt.join(r.createdBy ? who(r.createdBy) : tc("system"), fmt.short(r.createdAtMs))}</span>
              </div>
              {approvals.length > 0 && (
                <div className="mb-3">
                  <span className="mb-1 block txt-label text-muted-foreground">{t("subject.approvals")}</span>
                  <ul className="space-y-1">
                    {approvals.map((a) => (
                      <li key={a.approvalNo} className="flex flex-wrap items-center gap-2 txt-body">
                        <Link href={`/flow/approvals/detail/?no=${a.approvalNo}`} className="text-primary underline-offset-2 hover:underline">{a.approvalNo}</Link>
                        <StatusBadge map={approvalStatusMap(tc)} value={a.status} />
                        <span className="txt-caption text-muted-foreground">{fmt.join(t("subject.round", { n: a.round }), who(a.requestedBy), fmt.short(a.requestedAtMs))}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              <HistoryTimeline entries={history} />
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
