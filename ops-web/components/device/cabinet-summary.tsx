"use client";

// 设备台账摘要条（方案 §6.2 · 规则 R2）：一张卡 = 一个待办子集，点一下就按它筛选。
// 计数取列表接口的 total（size=1），与台账同一口径，不另起统计端点。
import { useQueries } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";

export interface CabinetFilter { onlineStatus?: string; status?: string }

const CARDS: { key: string; label: string; sub: string; filter: CabinetFilter }[] = [
  { key: "online", label: "在线", sub: "3 分钟内有心跳", filter: { onlineStatus: "ONLINE" } },
  { key: "offline", label: "离线", sub: "借不到也还不了，先看离线原因", filter: { onlineStatus: "OFFLINE" } },
  { key: "fault", label: "故障", sub: "已停止借出，等修复", filter: { status: "FAULT" } },
  { key: "stock", label: "在库待上线", sub: "过了上线门禁才开始接客", filter: { status: "IN_STOCK" } },
  { key: "transit", label: "运输中", sub: "随调拨单在路上，签收后回在库", filter: { status: "IN_TRANSIT" } },
];

export function CabinetSummaryStrip({ active, onPick }: {
  /** 当前筛选，用来高亮命中的卡。 */
  active: CabinetFilter;
  onPick: (f: CabinetFilter) => void;
}) {
  const counts = useQueries({
    queries: CARDS.map((c) => ({
      queryKey: ["cabinets", "summary", c.key],
      queryFn: () => api.listCabinets({ page: 1, size: 1, ...c.filter }),
      staleTime: 30_000,
    })),
  });
  return (
    <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
      {CARDS.map((c, i) => {
        const on = (c.filter.onlineStatus ?? "") === (active.onlineStatus ?? "")
          && (c.filter.status ?? "") === (active.status ?? "");
        return (
          <button
            key={c.key}
            type="button"
            aria-pressed={on}
            onClick={() => onPick(on ? {} : c.filter)}
            className={cn(
              "rounded-card bg-card p-4 text-start shadow-[var(--card-shadow)] transition-colors hover:bg-accent",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              on && "ring-2 ring-primary",
            )}
          >
            <div className="txt-body text-muted-foreground">{c.label}</div>
            <div className="mt-1 txt-title tabular-nums">{counts[i].data?.total ?? "—"}</div>
            <div className="mt-0.5 txt-caption text-muted-foreground">{on ? "再点一次取消筛选" : c.sub}</div>
          </button>
        );
      })}
    </div>
  );
}
