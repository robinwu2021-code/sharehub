"use client";

// L1 图标栏（Rail）：15 项 RBAC 过滤 + 当前项高亮 + 待建灰显(D2) + pinBottom + 可展开标签。
// 项数从 7 增至 15（2026-07-30 删「域」层）→ 竖排可能超出视口，nav 保留 overflow-y-auto。
// 仅依赖 pathname（不读 query），无需 Suspense。
import Link from "next/link";
import { usePathname } from "next/navigation";
import * as Icons from "lucide-react";
import {
  RAIL_WIDTH, RAIL_EXPANDED_WIDTH, type NavSection,
  visibleSections, findActiveSection, sectionDefaultHref, normPath,
} from "@/lib/nav";
import { useAuth } from "@/lib/auth";
import { useNavPrefs } from "@/lib/stores/nav-prefs";
import { useI18n } from "@/lib/i18n";
import { Tooltip } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

function iconOf(name: string) {
  return (Icons[name as keyof typeof Icons] ?? Icons.Circle) as React.ComponentType<{ className?: string }>;
}

function RailItem({
  section, active, soon, href, expanded,
}: { section: NavSection; active: boolean; soon: boolean; href?: string; expanded: boolean }) {
  const Icon = iconOf(section.icon);
  const { t, tNav } = useI18n();
  const label = tNav(section.label);
  const inner = (
    <>
      {active && <span className="absolute top-1/2 h-6 w-[3px] -translate-y-1/2 rounded-e-full bg-primary" style={{ insetInlineStart: 0 }} />}
      <Icon className="size-5 shrink-0" />
      {expanded && <span className="truncate text-sm">{label}</span>}
      {expanded && soon && <span className="ms-auto rounded-control bg-muted px-1 text-[11px] font-semibold leading-4 text-muted-foreground">{t("common.soon")}</span>}
    </>
  );
  // shrink-0：15 项时容器溢出滚动，flex 默认会压扁子项（图标变形），必须禁止收缩。
  const base = cn(
    "group relative flex shrink-0 items-center gap-3 rounded-md py-2 transition-colors",
    expanded ? "px-3" : "justify-center px-0",
  );
  // 折叠态提示：自绘 Tooltip（components/ui/tooltip.tsx，portal+fixed）。
  // 注意 ⚠️ 不要改回 absolute 浮层：父级 nav 有 overflow-y-auto，CSS 规范下一轴 auto 会把
  // 另一轴的 visible 也算成 auto → 浮层被裁掉，表现为「只有图标、没有任何提示」。
  // 也不要退回原生 title（约 1s 延迟 + 系统样式，15 个纯图标下辨识成本太高）。
  // 展开态已显示名称，不出 tip（label=undefined 时 Tooltip 完全透传）。
  const tipText = !expanded ? `${label}${soon ? ` · ${t("common.soon")}` : ""}` : undefined;

  return (
    <Tooltip label={tipText} side="right">
      {({ ref, ...tp }) =>
        soon || !href ? (
          <span
            {...tp}
            ref={ref}
            aria-disabled
            tabIndex={0} // 不可点也要能 Tab 到，否则键盘用户读不到「敬请期待」
            className={cn(base, "cursor-not-allowed text-muted-foreground/40")}
          >
            {inner}
          </span>
        ) : (
          <Link
            {...tp}
            ref={ref}
            href={href}
            aria-label={label}
            className={cn(base, active ? "bg-accent font-medium text-[var(--primary)]" : "text-sidebar-foreground hover:bg-accent/60 hover:text-foreground")}
          >
            {inner}
          </Link>
        )
      }
    </Tooltip>
  );
}

export function Rail() {
  const pathname = normPath(usePathname());
  const role = useAuth((s) => s.role);
  const { railExpanded, toggleRail } = useNavPrefs();
  const { t } = useI18n();

  const sections = visibleSections(role);
  const activeKey = findActiveSection(pathname, role)?.key;
  const top = sections.filter((s) => !s.pinBottom);
  const bottom = sections.filter((s) => s.pinBottom);

  const render = (s: NavSection) => (
    <RailItem
      key={s.key}
      section={s}
      active={s.key === activeKey}
      soon={!!s.soon}
      href={sectionDefaultHref(s, role)}
      expanded={railExpanded}
    />
  );

  return (
    <aside
      className="hidden shrink-0 flex-col bg-sidebar text-sidebar-foreground md:flex"
      style={{ width: railExpanded ? RAIL_EXPANDED_WIDTH : RAIL_WIDTH }}
    >
      <div className={cn("flex h-14 items-center gap-2", railExpanded ? "px-4" : "justify-center")}>
        <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-primary text-xs text-primary-foreground">PB</span>
        {railExpanded && <span className="truncate text-sm font-medium">{t("common.appName")}</span>}
      </div>
      <nav className={cn("flex flex-1 flex-col gap-1 overflow-y-auto py-2", railExpanded ? "px-2" : "px-2")}>
        {top.map(render)}
        <div className="flex-1" />
        {bottom.map(render)}
      </nav>
      <button
        type="button"
        onClick={toggleRail}
        aria-label={railExpanded ? "收起导航" : "展开导航"}
        className="flex h-10 items-center justify-center text-muted-foreground hover:bg-accent hover:text-foreground"
      >
        {railExpanded ? <Icons.ChevronsLeft className="size-4" /> : <Icons.ChevronsRight className="size-4" />}
      </button>
    </aside>
  );
}
