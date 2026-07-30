"use client";

// 紧凑页头 + tab 悬停/点击伸缩：标题=当前子功能名。
// - 悬停「标题区」→ tab 条从右侧临时滑出（预览，move away 收回，纯 CSS group-hover）
// - 点击「标题区」→ 固定展开（pinned，移开也不收；再点收回）—— 双保险，兼顾触摸/无鼠标
// - 窄屏(<md，无左侧 L3 导航)强制常显 + 换行，保留移动端切换
import * as React from "react";
import { ChevronRight } from "lucide-react";
import { useI18n } from "@/lib/i18n";
import { isPhaseLocked, type Phase } from "@/lib/phase";
import { cn } from "@/lib/utils";
import { segmentedItemClass, segmentedTrackClass } from "@/components/ui/segmented";

export function TabHeader({
  tabs, value, onChange, action,
}: {
  // phase?：标注该子功能的交付阶段；phase > CURRENT_PHASE 时从页内 tab 条隐藏（分期屏蔽）。
  tabs: { key: string; label: string; phase?: Phase }[];
  value: string;
  onChange: (k: string) => void;
  action?: React.ReactNode;
}) {
  const { t } = useI18n();
  const [pinned, setPinned] = React.useState(false);
  // 分期锁定的 tab 不出现在页内切换条（nav L3 已灰显，页面 PhaseGuard 兜底直达）。
  const visibleTabs = tabs.filter((x) => !isPhaseLocked(x.phase));
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
          <h1 className="truncate text-[17px] font-extrabold tracking-[-0.2px]">{current?.label ?? ""}</h1>
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
            {/* 分段控件 = C 端 pb-segmented：灰槽(faint) + 全圆，选中项是白色药丸。
                形状规格与 `ui/tabs.tsx` 共用 `ui/segmented.ts`，尺寸（13px/紧凑内边距）
                按这里的密度自己传。 */}
            <div className={segmentedTrackClass("w-max max-md:w-full max-md:flex-wrap")}>
              {visibleTabs.map((tb) => (
                <button
                  key={tb.key}
                  type="button"
                  onClick={() => onChange(tb.key)}
                  className={segmentedItemClass(tb.key === value, "px-3.5 py-1 text-[13px]")}
                >
                  {tb.label}
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
