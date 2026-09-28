"use client";

// 流程实例详情：页头（流程 · 版本 · 状态 · 业务对象）+ 完整流转历史（后端 O2-10 instances/{no}/history）。
// 在途单按提交时的版本走完 —— 版本号在页头，与流程配置页的版本历史对得上。
import { NeedPerm } from "@/components/flow/need-perm";
import { Suspense } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { useFlowT } from "@/lib/flow/i18n";
import { who } from "@/lib/flow/present";
import { Card } from "@/components/ui/card";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import { TicketStatusBadge } from "@/components/flow/badges";
import { HistoryTimeline } from "@/components/flow/history-timeline";

export default function FlowInstanceDetailPage() {
  return (
    <Suspense fallback={<Skeleton className="h-40" />}>
      <NeedPerm code="flow:approval:read" page="instanceDetail"><InstanceDetail /></NeedPerm>
    </Suspense>
  );
}

function InstanceDetail() {
  const no = useSearchParams().get("no") ?? "";
  const { t, tc, fmt } = useFlowT("instances");
  const q = useQuery({ queryKey: [...FLOW_KEY, "instance", no], queryFn: () => flow.instance(no), enabled: !!no });
  const h = useQuery({ queryKey: [...FLOW_KEY, "instance-history", no], queryFn: () => flow.instanceHistory(no), enabled: !!no });

  if (!no) return <ErrorState error={new Error(t("detail.missingNo"))} />;
  if (q.isLoading) return <Skeleton className="h-40" />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const r = q.data;

  return (
    <div>
      <div className="mb-1 txt-caption text-muted-foreground">
        <Link href="/flow/instances/" className="hover:text-foreground">{tc("page.instances")}</Link> / {r.instanceNo}
      </div>
      <div className="mb-1 flex flex-wrap items-center gap-2">
        <h1 className="txt-title">{r.process.name}</h1>
        <TicketStatusBadge t={r} />
      </div>
      <p className="mb-4 txt-body text-muted-foreground">
        {fmt.join(t("detail.meta.no", { no: r.instanceNo }), t("detail.meta.version", { v: r.processVersion }),
          r.createdBy ? t("detail.meta.by", { name: who(r.createdBy) }) : tc("system"), fmt.short(r.createdAtMs))}
      </p>

      <div className="grid items-start gap-4 lg:grid-cols-[1fr_340px]">
        <Card className="p-5">
          <h2 className="mb-1 txt-strong">{t("detail.history")}</h2>
          <p className="mb-3 txt-caption text-muted-foreground">{t("detail.historyHint")}</p>
          {h.error ? <ErrorState error={h.error} onRetry={() => h.refetch()} /> : <HistoryTimeline entries={h.data} loading={h.isLoading} />}
        </Card>
        {r.subject && (
          <Card className="p-5">
            <h2 className="mb-2 txt-strong">{t("col.subject")}</h2>
            <p className="txt-body">{r.subject.display.title}</p>
            <p className="mb-3 txt-caption text-muted-foreground">{`${r.subject.type}:${r.subject.ref}`}</p>
            <Link className="txt-body text-primary underline-offset-2 hover:underline"
              href={`/flow/instances/subject/?type=${encodeURIComponent(r.subject.type)}&ref=${encodeURIComponent(r.subject.ref)}`}>
              {t("detail.toSubject")}
            </Link>
          </Card>
        )}
      </div>
    </div>
  );
}
