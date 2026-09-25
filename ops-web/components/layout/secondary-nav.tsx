"use client";

// L2/L3 子导航面板：单列，分组=小标题、子功能=条目（ai-kb SecondaryNav 心智）。
// 2026-07-30 删「域」层后：Rail=L1(section)，本组件呈现 L2(分组)+L3(叶子)。
// 2026-07-30 删 miller（三列逐级）分支：切换入口早已撤掉，只剩老 localStorage
// 还能走到它 —— 一条没有入口的呈现路径不值得长期维护（navMode 也一并删）。
// 读 useSearchParams → 必须在 <Suspense> 下渲染（app-shell 已包）。
import Link from "next/link";
import { useViewer } from "@/lib/hooks/use-viewer";
import { usePathname, useSearchParams } from "next/navigation";
import {
  PANEL_WIDTH, type NavLeaf,
  visibleLeaves, findActiveSection, activeLeafIndex, normPath, isLeafLocked, groupedLeaves,
} from "@/lib/nav";
import { useAuth } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";
import { PHASE_LABEL, isPhaseLocked, type Phase } from "@/lib/phase";
import { cn } from "@/lib/utils";

function SoonBadge() {
  const { t } = useI18n();
  // 10px → txt-caption(12px)：规范的字号下限是 12px，10px 在任何屏幕上都读不清。
  return <span className="ms-auto shrink-0 rounded bg-muted px-1 txt-caption text-muted-foreground">{t("common.soon")}</span>;
}

function PhaseBadge({ phase }: { phase: Phase }) {
  return (
    <span className="ms-auto shrink-0 rounded bg-primary/8 px-1 txt-caption text-primary/60">
      {PHASE_LABEL[phase]}
    </span>
  );
}

function LeafRow({ leaf, active }: { leaf: NavLeaf; active: boolean }) {
  const { t, tNavNode } = useI18n();
  const locked = isLeafLocked(leaf);
  if (leaf.soon || locked) {
    return (
      <span
        className="flex items-center rounded-field px-2.5 py-1.5 text-[13px] text-muted-foreground/50"
        title={locked ? `${PHASE_LABEL[leaf.phase!]} ${t("phase.suffix")}` : t("common.soon")}
      >
        <span className="truncate">{tNavNode(leaf)}</span>
        {locked ? <PhaseBadge phase={leaf.phase!} /> : <SoonBadge />}
      </span>
    );
  }
  return (
    <Link
      href={leaf.href}
      className={cn(
        "flex items-center rounded-field px-2.5 py-1.5 text-[13px] transition-colors",
        active ? "bg-accent font-medium text-[var(--primary)]" : "text-muted-foreground hover:bg-accent/60 hover:text-foreground",
      )}
    >
      <span className="truncate">{tNavNode(leaf)}</span>
      {/* 已就绪但排期在后的叶子（ready + phase>当前）：可点，但保留分期徽章 ——
          否则「P2 功能已提前可用」这个信息在界面上完全消失，运营看不出自己在用超前功能。 */}
      {leaf.phase && isPhaseLocked(leaf.phase) && <PhaseBadge phase={leaf.phase} />}
    </Link>
  );
}

/** 分组段的稳定 key（无 group 的段用下标兜底）。 */
const segKey = (group: string | undefined, i: number) => group ?? `__flat${i}`;

export function SecondaryNav() {
  const pathname = normPath(usePathname());
  const sp = useSearchParams();
  const tab = sp.get("tab");
  const view = sp.get("view");
  const viewer = useViewer();
  const { tNav, tNavNode } = useI18n();

  const section = findActiveSection(pathname, viewer);
  const leaves = section ? visibleLeaves(section, viewer) : [];
  const activeIdx = section ? activeLeafIndex(leaves, pathname, tab, view) : -1;
  const segments = groupedLeaves(leaves);

  // 段的扁平起始下标（活跃叶属于哪一段 / 段内高亮换算）
  let acc = 0;
  const segBase = segments.map((s) => { const b = acc; acc += s.leaves.length; return b; });

  if (!section || !leaves.length) return null; // 无子功能的 section（经营看板）全宽（AC5）

  return (
    <aside className="hidden shrink-0 flex-col bg-sidebar/60 md:flex" style={{ width: PANEL_WIDTH }}>
      <div className="flex h-14 shrink-0 items-center px-3">
        <span className="truncate txt-strong">{tNavNode(section)}</span>
      </div>
      <nav className="flex-1 overflow-y-auto px-2 py-2">
        {segments.map((seg, si) => (
          <div key={segKey(seg.group, si)} className={cn(seg.group ? "mb-3" : "mb-1")}>
            {seg.group && (
              /* 父（分组）：标题化，14px 中黑（txt-strong），比子项更醒目 */
              <div className="flex items-center gap-2 px-2.5 pb-1 pt-0.5 txt-strong text-foreground">
                <span className="truncate">{tNav(seg.group)}</span>
              </div>
            )}
            {/* 子（功能）：有分组时缩进，13px 灰；无分组则平铺 */}
            <div className={cn("space-y-0.5", seg.group && "ms-1")}>
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
