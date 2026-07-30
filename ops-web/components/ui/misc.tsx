"use client";

import * as React from "react";
import { cn } from "@/lib/utils";
import { useI18n } from "@/lib/i18n";

// 统计卡（工作台 KPI）。
export function StatCard({
  label, value, sub, tone,
}: { label: string; value: React.ReactNode; sub?: string; tone?: "up" | "down" }) {
  return (
    <div className="rounded-card bg-card p-5 shadow-[var(--card-shadow)]">
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="mt-2 text-[20px] font-bold tracking-[-0.3px] tabular-nums">{value}</div>
      {sub && (
        <div className={cn("mt-1 text-xs", tone === "down" ? "text-[var(--destructive)]" : "text-[var(--success)]")}>
          {sub}
        </div>
      )}
    </div>
  );
}

export function EmptyState({ title, desc }: { title: string; desc?: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-card bg-muted/50 py-16 text-center">
      <div className="text-sm font-semibold">{title}</div>
      {desc && <div className="text-xs text-muted-foreground">{desc}</div>}
    </div>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn("animate-pulse rounded-field bg-muted", className)} />;
}

// 紧凑页头：标题与操作同一行；说明作为标题后的小字（面包屑已在顶栏给出位置，故从简）。
export function PageTitle({ title, desc, action }: { title: string; desc?: string; action?: React.ReactNode }) {
  return (
    <div className="mb-3 flex items-center justify-between gap-4">
      <div className="flex items-baseline gap-2 min-w-0">
        <h1 className="text-[17px] font-extrabold tracking-[-0.2px] shrink-0">{title}</h1>
        {desc && <p className="truncate text-xs text-muted-foreground">{desc}</p>}
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}

// 简易分页。
export function Pagination({
  page, size, total, onPage,
}: { page: number; size: number; total: number; onPage: (p: number) => void }) {
  const { t } = useI18n();
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div className="mt-4 flex items-center justify-between text-sm text-muted-foreground">
      <span>{t("common.totalItems", { n: total })}</span>
      <div className="flex items-center gap-2">
        <button
          className="rounded-chip bg-secondary px-2.5 py-1 transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)] disabled:opacity-40 disabled:hover:bg-secondary"
          disabled={page <= 1}
          onClick={() => onPage(page - 1)}
        >{t("common.prevPage")}</button>
        <span className="tabular-nums">{t("common.pageOf", { p: page, total: pages })}</span>
        <button
          className="rounded-chip bg-secondary px-2.5 py-1 transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)] disabled:opacity-40 disabled:hover:bg-secondary"
          disabled={page >= pages}
          onClick={() => onPage(page + 1)}
        >{t("common.nextPage")}</button>
      </div>
    </div>
  );
}
