"use client";

// 可点统计卡：点一张 = 把它的 queryParams 原样回填给列表查询（后端 21/06 的约定），
// 所以「卡上的数字」与「点进去的列表条数」是同一个查询、构造上一致。再点一次取消。
// 表面与工单摘要条同一套钩子（data-surface="stat" / data-tone / data-slot="value"），由风格决定形态。
import { cn } from "@/lib/utils";
import type { TicketStat } from "@/lib/flow/types";

export function StatStrip({
  stats, loading, active, onPick,
}: {
  stats: TicketStat[] | undefined;
  loading?: boolean;
  active: string | null;
  onPick: (s: TicketStat | null) => void;
}) {
  const list = stats ?? [];
  return (
    <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-5" role="group" aria-label="工单统计（点击筛选）">
      {(loading && !list.length ? Array.from({ length: 5 }, (_, i) => ({ key: `s${i}`, label: "", value: 0, queryParams: {} } as TicketStat)) : list).map((s) => {
        const on = active === s.key;
        const hot = s.value > 0 && !!s.tone;
        return (
          <button
            key={s.key}
            type="button"
            data-surface="stat"
            data-tone={hot ? s.tone : undefined}
            aria-pressed={on}
            disabled={loading}
            onClick={() => onPick(on ? null : s)}
            className={cn(
              "relative rounded-card bg-card p-4 text-start shadow-[var(--card-shadow)] transition-colors",
              "hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]",
              on && "ring-2 ring-primary",
            )}
          >
            {on && <span className="absolute end-3 top-3 txt-caption text-primary">✓ 已筛选</span>}
            <div className="txt-body text-muted-foreground">{s.label}</div>
            <div
              data-slot="value"
              className={cn(
                "mt-1 txt-display tabular-nums",
                hot && s.tone === "warning" && "text-warning-ink",
                hot && s.tone === "danger" && "text-destructive-ink",
              )}
            >
              {loading ? "–" : s.value}
            </div>
            {s.sub && <div className="mt-0.5 txt-caption text-muted-foreground">{s.sub}</div>}
          </button>
        );
      })}
    </div>
  );
}
