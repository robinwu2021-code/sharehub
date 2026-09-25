"use client";

// 门店进件详情抽屉：申请内容 · 审核结论 · 建出的场地方。审核动作由列表页传进来（与行内同一个 mutation）。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { VenueOnboarding } from "@/lib/types";
import { fmtTime } from "@/lib/utils";
import { Drawer, Field } from "@/components/ui/drawer";
import { DetailHeader } from "@/components/ui/detail-header";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Skeleton, EmptyState } from "@/components/ui/misc";

/** 入驻审核状态。 */
export const OB_STATUS: StatusMap<VenueOnboarding["status"]> = {
  PENDING: { label: "待审核", tone: "warning" },
  APPROVED: { label: "已通过", tone: "success" },
  REJECTED: { label: "已驳回", tone: "danger" },
};

export function OnboardingDetailDrawer({
  onboardingNo, onOpenChange, actions,
}: {
  onboardingNo: string | null;
  onOpenChange: (open: boolean) => void;
  /** 头部动作（通过 / 驳回 / 编辑），由列表页按权限与状态给。 */
  actions?: (o: VenueOnboarding) => React.ReactNode;
}) {
  const open = !!onboardingNo;
  const q = useQuery({
    queryKey: ["venue-onboarding", onboardingNo],
    queryFn: () => api.getVenueOnboarding(onboardingNo!),
    enabled: open,
    retry: false,
  });
  const o = q.data;
  return (
    <Drawer
      open={open}
      onOpenChange={onOpenChange}
      title={o ? `进件 · ${o.venueName}` : "进件详情"}
      desc="审核是动作不是改字段：只有待审能审，通过会建出场地方档案，驳回必须给原因"
      width="w-[520px]"
    >
      {q.isLoading && <Skeleton className="h-32" />}
      {!q.isLoading && !o && <EmptyState title="进件读不到" desc="编号可能有误，或已不在你的数据范围内。回到列表重新选择。" />}
      {o && (<>
        <DetailHeader
          no={o.onboardingNo}
          title={o.venueName}
          badge={<StatusBadge map={OB_STATUS} value={o.status} />}
          meta={`申请于 ${fmtTime(o.requestedAt)}`}
          actions={actions?.(o)}
          className="mb-4"
        />
        <Field label="联系人">{o.contact || <span className="text-muted-foreground">未填</span>}</Field>
        <Field label="行业">{o.industry || <span className="text-muted-foreground">未填</span>}</Field>
        <Field label="审核">
          {o.status === "PENDING"
            ? <span className="text-muted-foreground">待审核</span>
            : <>{fmtTime(o.reviewAt)}{o.reviewNote && <span className="ms-2">{o.reviewNote}</span>}</>}
        </Field>
        <Field label="建出的场地方">
          {o.venueNo
            ? <span className="tabular-nums">{o.venueNo}</span>
            : <span className="text-muted-foreground">{o.status === "APPROVED" ? "未回填 —— 请联系技术排查" : "通过后自动建档并回填"}</span>}
        </Field>
      </>)}
    </Drawer>
  );
}
