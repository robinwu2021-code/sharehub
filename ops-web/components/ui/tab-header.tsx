"use client";

// 紧凑页头 + tab 悬停/点击伸缩：标题=当前子功能名。
// - 悬停「标题区」→ tab 条从右侧临时滑出（预览，move away 收回，纯 CSS group-hover）
// - 点击「标题区」→ 固定展开（pinned，移开也不收；再点收回）—— 双保险，兼顾触摸/无鼠标
// - 窄屏(<md，无左侧 L3 导航)强制常显 + 换行，保留移动端切换
import * as React from "react";
import { ChevronRight } from "lucide-react";
import { useI18n } from "@/lib/i18n";
import { PHASE_LABEL, type Phase } from "@/lib/phase";
import { cn } from "@/lib/utils";

export function TabHeader({
  tabs, value, onChange, action,
}: {
  // phase?：标注该子功能的交付阶段；P2/P3 仅在 tab 上加徽标「标识」，不再隐藏（可正常切换）。
  tabs: { key: string; label: string; phase?: Phase }[];
  value: string;
  onChange: (k: string) => void;
  action?: React.ReactNode;
}) {
  const { t } = useI18n();
  const [pinned, setPinned] = React.useState(false);
  // 全部 tab 均可切换；P2/P3 仅以徽标标识交付阶段。
  const visibleTabs = tabs;
  const current = tabs.find((x) => x.key === value);
  const multi = visibleTabs.length > 1;
  const toggle = () => setPinned((p) => !p);

  return (
    <div className="mb-3 flex items-center gap-3">
      <div className="group flex min-w-0 flex-1 items-center gap-2">
        <div
          role={multi ? "button" : undefined}
          tabIndex={multi ? 0 : undefined}
          onClick={multi ? toggle : undefined}
          onKeyDown={multi ? (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); toggle(); } } : undefined}
          aria-expanded={multi ? pinned : undefined}
          title={multi ? t("common.switchView") : undefined}
          className={cn(
            "-mx-1 flex shrink-0 select-none items-center gap-1 rounded-md px-1",
            multi && "cursor-pointer hover:bg-accent/40",
          )}
        >
          <h1 className="truncate text-base font-medium">{current?.label ?? ""}</h1>
          {multi && (
            <ChevronRight
              className={cn(
                "size-4 shrink-0 text-muted-foreground/60 transition-transform duration-300 rtl:-scale-x-100",
                pinned ? "rotate-90" : "group-hover:rotate-90",
              )}
            />
          )}
        </div>

        {multi && (
          <div
            className={cn(
              "min-w-0 overflow-hidden transition-[max-width,opacity] duration-300 ease-out",
              pinned
                ? "opacity-100 [max-width:1000px]"
                : "opacity-0 [max-width:0px] group-hover:opacity-100 group-hover:[max-width:1000px]",
              // 窄屏：常显 + 换行
              "max-md:!max-w-full max-md:!opacity-100 max-md:overflow-visible",
            )}
          >
            <div className="flex w-max gap-1 rounded-xl bg-secondary p-1 max-md:w-full max-md:flex-wrap">
              {visibleTabs.map((tb) => (
                <button
                  key={tb.key}
                  type="button"
                  onClick={() => onChange(tb.key)}
                  className={cn(
                    "flex items-center gap-1 whitespace-nowrap rounded-lg px-3 py-1 text-[13px] transition-colors",
                    tb.key === value
                      ? "bg-card font-medium text-foreground shadow-[var(--card-shadow)]"
                      : "text-muted-foreground hover:text-foreground",
                  )}
                >
                  {tb.label}
                  {tb.phase && tb.phase > 1 && (
                    <span className="rounded bg-primary/8 px-1 text-[9px] leading-3 text-primary/60">{PHASE_LABEL[tb.phase]}</span>
                  )}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}
