"use client";

// 工单摘要条（规则 R2）：**一张卡 = 一个待办子集，点了就是筛选**。
// 四格都是要人动手的事，按紧急度排：待派单 → 即将超时 → 已超时 → 复核未通过。
// 不放「工单总数」—— 它不会让任何人去做任何事。
//
// 选中态用「描边 + 右上角 ✓ 已筛选」双重表达，不只靠颜色；再点一次取消筛选。
import { cn } from "@/lib/utils";
import type { WoSummary } from "@/lib/types";

export type WoSummaryKey = keyof WoSummary;

const CARDS: { key: WoSummaryKey; label: string; sub: string; tone: "warning" | "danger" | "default" }[] = [
  { key: "toDispatch", label: "待派单", sub: "还没人接的单", tone: "default" },
  { key: "dueSoon", label: "即将超时", sub: "两小时内到期", tone: "warning" },
  { key: "overdue", label: "已超时", sub: "过了解决时限仍未完工", tone: "danger" },
  { key: "reviewFailed", label: "复核未通过", sub: "完工了但关联告警仍在", tone: "danger" },
];

export function WoSummaryBar({
  data, loading, active, onPick,
}: {
  data?: WoSummary;
  loading?: boolean;
  active: WoSummaryKey | null;
  /** 点同一张卡 = 取消（传 null）。 */
  onPick: (k: WoSummaryKey | null) => void;
}) {
  return (
    <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4" role="group" aria-label="工单待办摘要（点击筛选）">
      {CARDS.map((c) => {
        const n = data?.[c.key];
        const on = active === c.key;
        const hot = !!n && c.tone !== "default";
        return (
          <button
            key={c.key}
            type="button"
            aria-pressed={on}
            onClick={() => onPick(on ? null : c.key)}
            className={cn(
              "relative rounded-card bg-card p-4 text-start shadow-[var(--card-shadow)] transition-colors",
              "hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]",
              on && "ring-2 ring-primary",
            )}
          >
            {on && <span className="absolute end-3 top-3 txt-caption text-primary">✓ 已筛选</span>}
            <div className="txt-body text-muted-foreground">{c.label}</div>
            <div
              className={cn(
                "mt-1 txt-display tabular-nums",
                hot && c.tone === "warning" && "text-warning-ink",
                hot && c.tone === "danger" && "text-destructive-ink",
              )}
            >
              {loading || n === undefined ? "–" : n}
            </div>
            <div className="mt-0.5 txt-caption text-muted-foreground">{c.sub}</div>
          </button>
        );
      })}
    </div>
  );
}

/** 摘要卡 → 列表筛选参数（与后端 summary 的四个口径一一对应）。 */
export function summaryFilter(k: WoSummaryKey | null): { status?: string; slaState?: string; reviewStatus?: string } {
  switch (k) {
    case "toDispatch": return { status: "CREATED" };
    case "dueSoon": return { slaState: "DUE_SOON" };
    case "overdue": return { slaState: "OVERDUE" };
    case "reviewFailed": return { status: "DONE", reviewStatus: "FAILED" };
    default: return {};
  }
}
