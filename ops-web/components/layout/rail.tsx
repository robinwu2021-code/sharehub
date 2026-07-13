"use client";

// L1 域图标栏（Rail）：7 域 RBAC 过滤 + 当前域高亮 + 待建灰显(D2) + pinBottom + 可展开标签。
// 仅依赖 pathname（不读 query），无需 Suspense。
import Link from "next/link";
import { usePathname } from "next/navigation";
import * as Icons from "lucide-react";
import {
  NAV, RAIL_WIDTH, RAIL_EXPANDED_WIDTH, type NavDomain,
  visibleDomains, isDomainSoon, findActiveModule, domainDefaultHref, normPath,
} from "@/lib/nav";
import { useAuth } from "@/lib/auth";
import { useNavPrefs } from "@/lib/stores/nav-prefs";
import { useI18n } from "@/lib/i18n";
import { cn } from "@/lib/utils";

function iconOf(name: string) {
  return (Icons[name as keyof typeof Icons] ?? Icons.Circle) as React.ComponentType<{ className?: string }>;
}

function RailItem({
  domain, active, soon, href, expanded,
}: { domain: NavDomain; active: boolean; soon: boolean; href?: string; expanded: boolean }) {
  const Icon = iconOf(domain.icon);
  const { t, tNav } = useI18n();
  const label = tNav(domain.label);
  const inner = (
    <>
      {active && <span className="absolute top-1/2 h-6 w-[3px] -translate-y-1/2 rounded-e-full bg-primary" style={{ insetInlineStart: 0 }} />}
      <Icon className="size-5 shrink-0" />
      {expanded && <span className="truncate text-sm">{label}</span>}
      {expanded && soon && <span className="ms-auto rounded bg-muted px-1 text-[10px] leading-4 text-muted-foreground">{t("common.soon")}</span>}
    </>
  );
  const base = cn(
    "group relative flex items-center gap-3 rounded-md py-2 transition-colors",
    expanded ? "px-3" : "justify-center px-0",
  );
  // 折叠态用自绘 tooltip（原生 title 延迟高）
  const tip = !expanded && (
    <span className="pointer-events-none absolute top-1/2 z-50 hidden -translate-y-1/2 whitespace-nowrap rounded-md bg-foreground px-2 py-1 text-xs text-background group-hover:block" style={{ insetInlineStart: "100%", marginInlineStart: "0.5rem" }}>
      {label}{soon ? ` · ${t("common.soon")}` : ""}
    </span>
  );

  if (soon || !href) {
    return (
      <span aria-disabled className={cn(base, "cursor-not-allowed text-muted-foreground/40")} title={expanded ? t("common.soon") : undefined}>
        {inner}{tip}
      </span>
    );
  }
  return (
    <Link
      href={href}
      aria-label={label}
      className={cn(base, active ? "bg-accent font-medium text-[var(--primary)]" : "text-sidebar-foreground hover:bg-accent/60 hover:text-foreground")}
    >
      {inner}{tip}
    </Link>
  );
}

export function Rail() {
  const pathname = normPath(usePathname());
  const role = useAuth((s) => s.role);
  const { railExpanded, toggleRail } = useNavPrefs();
  const { t } = useI18n();

  const domains = visibleDomains(role);
  const activeDomainKey = findActiveModule(pathname)?.domain.key;
  const top = domains.filter((d) => !d.pinBottom);
  const bottom = domains.filter((d) => d.pinBottom);

  const render = (d: NavDomain) => (
    <RailItem
      key={d.key}
      domain={d}
      active={d.key === activeDomainKey}
      soon={isDomainSoon(d, role)}
      href={domainDefaultHref(d, role)}
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
