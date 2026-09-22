"use client";

import * as React from "react";
import { AlertTriangle } from "lucide-react";
import { cn } from "@/lib/utils";
import { useI18n } from "@/lib/i18n";

// 统计卡（工作台 KPI）。
export function StatCard({
  label, value, sub, tone,
}: { label: string; value: React.ReactNode; sub?: string; tone?: "up" | "down" }) {
  return (
    <div data-surface="stat" className="rounded-card bg-card p-5 shadow-[var(--card-shadow)]">
      <div data-slot="label" className="txt-body text-muted-foreground">{label}</div>
      <div className="mt-2 txt-display tabular-nums">{value}</div>
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
      {desc && <div className="txt-caption text-muted-foreground">{desc}</div>}
    </div>
  );
}

/**
 * 取数失败态。
 *
 * **它与 `EmptyState` 是两件事，不许互相顶替**：空态说「按这个条件没有东西」，
 * 失败态说「没取到，别信这一屏」。整理前整个运营端只有空态 ——
 * 接口 500 时表格渲染成「暂无数据」，运营会去改筛选条件，不会报障。
 *
 * 失败原因**照实显示**（`ApiError.message` 已由 http-client / biz-error 本地化过），
 * 不要统一成「系统繁忙」—— 那句话让人无从下手。
 */
export function ErrorState({ error, onRetry, className }: { error?: unknown; onRetry?: () => void; className?: string }) {
  const { t } = useI18n();
  const msg = error instanceof Error ? error.message : undefined;
  return (
    <div className={cn("flex flex-col items-center justify-center gap-2 rounded-card bg-muted/50 py-16 text-center", className)}>
      <AlertTriangle className="size-5 text-[var(--destructive)]" aria-hidden />
      <div className="text-sm font-semibold">{t("common.loadFailed")}</div>
      <div className="max-w-md txt-caption text-muted-foreground">{msg || t("common.loadFailedDesc")}</div>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-1 rounded-field bg-secondary px-3 py-1 txt-body transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]"
        >{t("common.retry")}</button>
      )}
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
        <h1 className="txt-title shrink-0">{title}</h1>
        {desc && <p className="truncate txt-caption text-muted-foreground">{desc}</p>}
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}

// 简易分页。
/** 每页条数候选。超出这几档的需求基本都该用筛选解决，而不是一次看 200 行。 */
export const PAGE_SIZES = [10, 20, 50, 100] as const;

export function Pagination({
  page, size, total, onPage, onSize,
}: {
  page: number; size: number; total: number;
  onPage: (p: number) => void;
  /**
   * 换每页条数。**给了才显示选择器** —— 不给就是「这张表的条数不可调」，
   * 是个明确决定，不是忘了接（`PagedTable` 那条路上它是必填的）。
   */
  onSize?: (n: number) => void;
}) {
  const { t } = useI18n();
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div className="mt-4 flex items-center justify-between txt-body text-muted-foreground">
      <span>{t("common.totalItems", { n: total })}</span>
      <div className="flex items-center gap-2">
        {onSize && (
          <select
            aria-label={t("common.pageSize", { n: size })}
            value={size}
            onChange={(e) => onSize(Number(e.target.value))}
            className="rounded-field bg-secondary px-2 py-1 transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]"
          >
            {PAGE_SIZES.map((n) => <option key={n} value={n}>{t("common.pageSize", { n })}</option>)}
          </select>
        )}
        <button
          className="rounded-field bg-secondary px-2.5 py-1 transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)] disabled:opacity-40 disabled:hover:bg-secondary"
          disabled={page <= 1}
          onClick={() => onPage(page - 1)}
        >{t("common.prevPage")}</button>
        <span className="tabular-nums">{t("common.pageOf", { p: page, total: pages })}</span>
        <button
          className="rounded-field bg-secondary px-2.5 py-1 transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)] disabled:opacity-40 disabled:hover:bg-secondary"
          disabled={page >= pages}
          onClick={() => onPage(page + 1)}
        >{t("common.nextPage")}</button>
      </div>
    </div>
  );
}
