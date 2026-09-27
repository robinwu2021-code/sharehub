"use client";

// 流水式时间线（工单时间线、审批历史轮次）：标题在上、时间 · 操作人 · 说明在下。
// 用 data-slot="timeline" 钩子 —— 清爽风格画成「整条竖线 + 圆点，首项主色」，经典风格是每项左竖线。
import { cn } from "@/lib/utils";

export interface FlowTimelineItem {
  key: string;
  title: React.ReactNode;
  meta: React.ReactNode;
  detail?: React.ReactNode;
  hot?: boolean;
}

export function FlowTimeline({ items, empty }: { items: FlowTimelineItem[]; empty: string }) {
  if (!items.length) return <p className="txt-caption text-muted-foreground">{empty}</p>;
  return (
    <ol data-slot="timeline" className="space-y-2.5">
      {items.map((it) => (
        <li key={it.key} data-slot="timeline-item" className="border-l-2 border-[var(--border)] pl-3">
          <div className={cn("txt-strong", it.hot && "text-warning-ink")}>{it.title}</div>
          <div className="txt-caption text-muted-foreground tabular-nums">{it.meta}</div>
          {it.detail && <div className="mt-0.5 txt-caption text-foreground">{it.detail}</div>}
        </li>
      ))}
    </ol>
  );
}
