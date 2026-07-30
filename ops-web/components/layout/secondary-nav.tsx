"use client";

// L2/L3 子导航面板，两种呈现（AC2，useNavPrefs 持久化）：
// - panel：单列，分组=小标题、子功能=条目（ai-kb SecondaryNav 心智）。
// - miller：两列，左=分组、右=选中分组的子功能（Rail 即"L1 列"，合计三列 Miller）。
// 2026-07-30 删「域」层后：Rail=L1(section)，本组件呈现 L2(分组)+L3(叶子)。
// 读 useSearchParams → 必须在 <Suspense> 下渲染（app-shell 已包）。
import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import * as Icons from "lucide-react";
import {
  PANEL_WIDTH, MILLER_GROUP_WIDTH, MILLER_LEAF_WIDTH, type NavLeaf, type NavMode,
  visibleLeaves, findActiveSection, activeLeafIndex, normPath, isLeafLocked, groupedLeaves,
} from "@/lib/nav";
import { useAuth } from "@/lib/auth";
import { useNavPrefs } from "@/lib/stores/nav-prefs";
import { useI18n } from "@/lib/i18n";
import { PHASE_LABEL, type Phase } from "@/lib/phase";
import { cn } from "@/lib/utils";

function SoonBadge() {
  const { t } = useI18n();
  return <span className="ms-auto shrink-0 rounded bg-muted px-1 text-[10px] leading-4 text-muted-foreground">{t("common.soon")}</span>;
}

function PhaseBadge({ phase }: { phase: Phase }) {
  return (
    <span className="ms-auto shrink-0 rounded bg-primary/8 px-1 text-[10px] leading-4 text-primary/60">
      {PHASE_LABEL[phase]}
    </span>
  );
}

function LeafRow({ leaf, active }: { leaf: NavLeaf; active: boolean }) {
  const { t, tNav } = useI18n();
  const locked = isLeafLocked(leaf);
  if (leaf.soon || locked) {
    return (
      <span
        className="flex items-center rounded-md px-2.5 py-1.5 text-[13px] text-muted-foreground/50"
        title={locked ? `${PHASE_LABEL[leaf.phase!]} ${t("phase.suffix")}` : t("common.soon")}
      >
        <span className="truncate">{tNav(leaf.label)}</span>
        {locked ? <PhaseBadge phase={leaf.phase!} /> : <SoonBadge />}
      </span>
    );
  }
  return (
    <Link
      href={leaf.href}
      className={cn(
        "flex items-center rounded-md px-2.5 py-1.5 text-[13px] transition-colors",
        active ? "bg-accent font-medium text-[var(--primary)]" : "text-muted-foreground hover:bg-accent/60 hover:text-foreground",
      )}
    >
      <span className="truncate">{tNav(leaf.label)}</span>
    </Link>
  );
}

function ModeToggle({ mode, onChange }: { mode: NavMode; onChange: (m: NavMode) => void }) {
  return (
    <div className="flex gap-0.5 rounded-lg bg-secondary p-0.5" role="group" aria-label="导航呈现模式">
      <button
        type="button"
        title="面板分组"
        aria-pressed={mode === "panel"}
        onClick={() => onChange("panel")}
        className={cn("rounded-md p-1 transition-colors", mode === "panel" ? "bg-card text-foreground shadow-[var(--card-shadow)]" : "text-muted-foreground hover:text-foreground")}
      >
        <Icons.Rows3 className="size-3.5" />
      </button>
      <button
        type="button"
        title="三列逐级"
        aria-pressed={mode === "miller"}
        onClick={() => onChange("miller")}
        className={cn("rounded-md p-1 transition-colors", mode === "miller" ? "bg-card text-foreground shadow-[var(--card-shadow)]" : "text-muted-foreground hover:text-foreground")}
      >
        <Icons.Columns3 className="size-3.5" />
      </button>
    </div>
  );
}

/** 分组段的稳定 key（无 group 的段用下标兜底）。 */
const segKey = (group: string | undefined, i: number) => group ?? `__flat${i}`;

export function SecondaryNav() {
  const pathname = normPath(usePathname());
  const sp = useSearchParams();
  const tab = sp.get("tab");
  const view = sp.get("view");
  const role = useAuth((s) => s.role);
  const { navMode, setNavMode } = useNavPrefs();
  const { tNav } = useI18n();

  const section = findActiveSection(pathname, role);
  const leaves = section ? visibleLeaves(section, role) : [];
  const activeIdx = section ? activeLeafIndex(leaves, pathname, tab, view) : -1;
  const segments = groupedLeaves(leaves);

  // 段的扁平起始下标（活跃叶属于哪一段 / 段内高亮换算）
  let acc = 0;
  const segBase = segments.map((s) => { const b = acc; acc += s.leaves.length; return b; });
  const activeSegIdx = segBase.findIndex(
    (b, i) => activeIdx >= b && activeIdx < b + segments[i].leaves.length,
  );

  // miller 左列选中态（可浏览非当前分组的子功能）；路由变化时回同步
  const activeSegKey = activeSegIdx >= 0 ? segKey(segments[activeSegIdx].group, activeSegIdx) : undefined;
  const [selKey, setSelKey] = useState(activeSegKey);
  useEffect(() => setSelKey(activeSegKey), [activeSegKey]);

  if (!section || !leaves.length) return null; // 无子功能的 section（经营看板）全宽（AC5）

  const header = (
    <div className="flex h-14 shrink-0 items-center justify-between px-3">
      <span className="truncate text-sm font-medium">{tNav(section.label)}</span>
      <ModeToggle mode={navMode} onChange={setNavMode} />
    </div>
  );

  if (navMode === "panel") {
    return (
      <aside className="hidden shrink-0 flex-col bg-sidebar/60 md:flex" style={{ width: PANEL_WIDTH }}>
        {header}
        <nav className="flex-1 overflow-y-auto px-2 py-2">
          {segments.map((seg, si) => (
            <div key={segKey(seg.group, si)} className={cn(seg.group ? "mb-3" : "mb-1")}>
              {seg.group && (
                /* 父（分组）：标题化，14px 中黑，比子项更醒目 */
                <div className="flex items-center gap-2 px-2.5 pb-1 pt-0.5 text-sm font-medium text-foreground">
                  <span className="truncate">{tNav(seg.group)}</span>
                </div>
              )}
              {/* 子（功能）：有分组时缩进 + 左导引线，13px 灰；无分组则平铺 */}
              <div className={cn("space-y-0.5", seg.group && "ms-3 border-s border-border/70 ps-1.5")}>
                {seg.leaves.map((l, i) => (
                  <LeafRow key={l.href + l.label} leaf={l} active={segBase[si] + i === activeIdx} />
                ))}
              </div>
            </div>
          ))}
        </nav>
      </aside>
    );
  }

  // miller：左分组列 + 右子功能列
  const selIdx = Math.max(0, segments.findIndex((s, i) => segKey(s.group, i) === selKey));
  const sel = segments[selIdx];

  return (
    <div className="hidden shrink-0 md:flex">
      <aside className="flex flex-col bg-sidebar/60" style={{ width: MILLER_GROUP_WIDTH }}>
        {header}
        <nav className="flex-1 space-y-0.5 overflow-y-auto px-2 py-2">
          {segments.map((seg, si) => {
            const isActive = si === activeSegIdx;
            const isSel = si === selIdx;
            const allLocked = seg.leaves.every((l) => isLeafLocked(l) || l.soon);
            return (
              <button
                key={segKey(seg.group, si)}
                type="button"
                onClick={() => setSelKey(segKey(seg.group, si))}
                className={cn(
                  "flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm transition-colors",
                  isSel ? "bg-accent text-foreground" : "text-muted-foreground hover:bg-accent/60 hover:text-foreground",
                  isActive && "font-medium text-primary",
                  allLocked && "text-muted-foreground/40",
                )}
              >
                <span className="truncate">{seg.group ? tNav(seg.group) : tNav(section.label)}</span>
                <Icons.ChevronRight className="ms-auto size-3.5 shrink-0 opacity-50 rtl:-scale-x-100" />
              </button>
            );
          })}
        </nav>
      </aside>
      <aside className="flex flex-col bg-sidebar/35" style={{ width: MILLER_LEAF_WIDTH }}>
        <div className="flex h-14 shrink-0 items-center px-3 text-sm text-muted-foreground">
          {sel?.group ? tNav(sel.group) : tNav(section.label)}
        </div>
        <nav className="flex-1 space-y-0.5 overflow-y-auto px-2 py-2">
          {sel?.leaves.map((l, i) => (
            <LeafRow key={l.href + l.label} leaf={l} active={segBase[selIdx] + i === activeIdx} />
          ))}
        </nav>
      </aside>
    </div>
  );
}
