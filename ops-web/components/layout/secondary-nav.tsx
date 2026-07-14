"use client";

// L2/L3 子导航面板，两种呈现（AC2，useNavPrefs 持久化）：
// - panel：单列，模块=分组标题、子功能=条目（ai-kb SecondaryNav 心智）。
// - miller：两列，左=模块、右=选中模块的子功能（Rail 即"域列"，合计三列 Miller）。
// 读 useSearchParams → 必须在 <Suspense> 下渲染（app-shell 已包）。
import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import * as Icons from "lucide-react";
import {
  PANEL_WIDTH, MILLER_MODULE_WIDTH, MILLER_LEAF_WIDTH, type NavModule, type NavLeaf, type NavMode,
  visibleModules, visibleLeaves, findActiveModule, activeLeafIndex, isSingleModuleDomain,
  moduleDefaultHref, normPath,
} from "@/lib/nav";
import { useAuth } from "@/lib/auth";
import { useNavPrefs } from "@/lib/stores/nav-prefs";
import { useI18n } from "@/lib/i18n";
import { PHASE_LABEL, type Phase } from "@/lib/phase";
import { cn } from "@/lib/utils";

function iconOf(name: string) {
  return (Icons[name as keyof typeof Icons] ?? Icons.Circle) as React.ComponentType<{ className?: string }>;
}

function SoonBadge() {
  const { t } = useI18n();
  return <span className="ms-auto shrink-0 rounded bg-muted px-1 text-[10px] leading-4 text-muted-foreground">{t("common.soon")}</span>;
}

// P2/P3 徽标：仅作阶段「标识」，不再屏蔽点击。
function PhaseBadge({ phase }: { phase: Phase }) {
  return (
    <span className="ms-auto shrink-0 rounded bg-primary/8 px-1 text-[10px] leading-4 text-primary/60">
      {PHASE_LABEL[phase]}
    </span>
  );
}

function LeafRow({ leaf, active }: { leaf: NavLeaf; active: boolean }) {
  const { t, tNav } = useI18n();
  // soon（待建，页面不存在）仍不可点；分期(P2/P3)改为「仅标识」——可正常点击，仅加徽标。
  if (leaf.soon) {
    return (
      <span
        className="flex items-center rounded-md px-2.5 py-1.5 text-[13px] text-muted-foreground/50"
        title={t("common.soon")}
      >
        <span className="truncate">{tNav(leaf.label)}</span>
        <SoonBadge />
      </span>
    );
  }
  const marked = !!leaf.phase && leaf.phase > 1;
  return (
    <Link
      href={leaf.href}
      className={cn(
        "flex items-center rounded-md px-2.5 py-1.5 text-[13px] transition-colors",
        active ? "bg-accent font-medium text-[var(--primary)]" : "text-muted-foreground hover:bg-accent/60 hover:text-foreground",
      )}
    >
      <span className="truncate">{tNav(leaf.label)}</span>
      {marked && <PhaseBadge phase={leaf.phase!} />}
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

export function SecondaryNav() {
  const pathname = normPath(usePathname());
  const sp = useSearchParams();
  const tab = sp.get("tab");
  const view = sp.get("view");
  const role = useAuth((s) => s.role);
  const { navMode, setNavMode } = useNavPrefs();
  const { tNav } = useI18n();

  const hit = findActiveModule(pathname, tab, view);
  // miller 左列选中态（可浏览非当前模块的子功能）；路由变化时回同步
  const [selKey, setSelKey] = useState(hit?.module.key);
  useEffect(() => setSelKey(hit?.module.key), [hit?.module.key]);

  if (!hit || isSingleModuleDomain(hit.domain)) return null; // 单模块域全宽（AC5）

  const { domain } = hit;
  const modules = visibleModules(domain, role);
  const activeModuleKey = hit.module.key;

  const leavesOf = (m: NavModule) => visibleLeaves(m, role);
  const activeIdxIn = (m: NavModule) =>
    m.key === activeModuleKey ? activeLeafIndex(leavesOf(m), pathname, tab, view) : -1;

  const header = (
    <div className="flex h-14 shrink-0 items-center justify-between px-3">
      <span className="truncate text-sm font-medium">{tNav(domain.label)}</span>
      <ModeToggle mode={navMode} onChange={setNavMode} />
    </div>
  );

  if (navMode === "panel") {
    return (
      <aside className="hidden shrink-0 flex-col bg-sidebar/60 md:flex" style={{ width: PANEL_WIDTH }}>
        {header}
        <nav className="flex-1 overflow-y-auto px-2 py-2">
          {modules.map((m) => {
            const leaves = leavesOf(m);
            const activeIdx = activeIdxIn(m);
            const MIcon = iconOf(m.icon);
            return (
              <div key={m.key} className="mb-3">
                {/* 父（模块）：标题化，14px 中黑 + 图标，比子项更醒目 */}
                <div className={cn(
                  "flex items-center gap-2 px-2.5 pb-1 pt-0.5 text-sm font-medium",
                  m.soon ? "text-muted-foreground/50" : "text-foreground",
                )}>
                  <MIcon className="size-4 shrink-0" />
                  <span className="truncate">{tNav(m.label)}</span>
                  {m.soon && <SoonBadge />}
                </div>
                {/* 子（功能）：缩进 + 左导引线，13px 灰 */}
                <div className="ms-5 space-y-0.5 border-s border-border/70 ps-1.5">
                  {leaves.length
                    ? leaves.map((l, i) => <LeafRow key={l.href + l.label} leaf={l} active={i === activeIdx} />)
                    : <LeafRow leaf={{ href: m.href, label: m.label, soon: m.soon }} active={m.key === activeModuleKey} />}
                </div>
              </div>
            );
          })}
        </nav>
      </aside>
    );
  }

  // miller：左模块列 + 右子功能列
  const sel = modules.find((m) => m.key === selKey) ?? modules[0];
  const selLeaves = sel ? leavesOf(sel) : [];
  const selActiveIdx = sel ? activeIdxIn(sel) : -1;

  return (
    <div className="hidden shrink-0 md:flex">
      <aside className="flex flex-col bg-sidebar/60" style={{ width: MILLER_MODULE_WIDTH }}>
        {header}
        <nav className="flex-1 space-y-0.5 overflow-y-auto px-2 py-2">
          {modules.map((m) => {
            const MIcon = iconOf(m.icon);
            const isActive = m.key === activeModuleKey;
            const isSel = m.key === sel?.key;
            return (
              <button
                key={m.key}
                type="button"
                onClick={() => setSelKey(m.key)}
                className={cn(
                  "flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm transition-colors",
                  isSel ? "bg-accent text-foreground" : "text-muted-foreground hover:bg-accent/60 hover:text-foreground",
                  isActive && "font-medium text-primary",
                  m.soon && "text-muted-foreground/40",
                )}
              >
                <MIcon className="size-4 shrink-0" />
                <span className="truncate">{tNav(m.label)}</span>
                <Icons.ChevronRight className="ms-auto size-3.5 shrink-0 opacity-50 rtl:-scale-x-100" />
              </button>
            );
          })}
        </nav>
      </aside>
      <aside className="flex flex-col bg-sidebar/35" style={{ width: MILLER_LEAF_WIDTH }}>
        <div className="flex h-14 shrink-0 items-center px-3 text-sm text-muted-foreground">
          {sel ? tNav(sel.label) : ""}
        </div>
        <nav className="flex-1 space-y-0.5 overflow-y-auto px-2 py-2">
          {selLeaves.length
            ? selLeaves.map((l, i) => <LeafRow key={l.href + l.label} leaf={l} active={i === selActiveIdx} />)
            : sel && <LeafRow leaf={{ href: moduleDefaultHref(sel, role), label: sel.label, soon: sel.soon }} active={sel.key === activeModuleKey} />}
        </nav>
      </aside>
    </div>
  );
}
