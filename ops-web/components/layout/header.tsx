"use client";

import { Suspense } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { IS_MOCK } from "@/lib/api";
import { breadcrumb } from "@/lib/nav";
import { useI18n } from "@/lib/i18n";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ThemeSwitcher } from "./theme-switcher";
import { LangSwitcher } from "./lang-switcher";
import { ChevronRight, LogOut } from "lucide-react";

// 面包屑：域 › 模块 › 子功能（URL 反推，标签经 tNav 本地化）。读 useSearchParams → 包 Suspense。
function Breadcrumb() {
  const pathname = usePathname();
  const sp = useSearchParams();
  const role = useAuth((s) => s.role);
  const { tNav } = useI18n();
  const crumbs = breadcrumb(pathname, sp.get("tab"), sp.get("view"), role);
  if (!crumbs.length) return null;
  return (
    <nav aria-label="breadcrumb" className="flex items-center gap-1 text-sm text-muted-foreground">
      {crumbs.map((c, i) => (
        <span key={i} className="flex items-center gap-1">
          {i > 0 && <ChevronRight className="size-3.5 opacity-50 rtl:-scale-x-100" />}
          <span className={i === crumbs.length - 1 ? "text-foreground" : undefined}>{tNav(c)}</span>
        </span>
      ))}
    </nav>
  );
}

export function Header() {
  const { username, role, agentNo, logout } = useAuth();
  const { t } = useI18n();
  const router = useRouter();
  return (
    <header className="flex h-14 items-center justify-between px-6">
      <div className="flex items-center gap-3 text-sm text-muted-foreground">
        <Suspense fallback={null}>
          <Breadcrumb />
        </Suspense>
        {role === "AGENT" && <span>{t("common.agent")} <span className="text-foreground">{agentNo || "-"}</span></span>}
        {IS_MOCK && <Badge tone="warning">{t("common.mockData")}</Badge>}
      </div>
      <div className="flex items-center gap-3 text-sm">
        <LangSwitcher />
        <ThemeSwitcher />
        <span className="text-muted-foreground">{t(`role.${role}`)}</span>
        <span className="font-medium">{username}</span>
        <Button
          size="sm"
          variant="ghost"
          onClick={() => {
            logout();
            router.push("/login");
          }}
        >
          <LogOut className="size-4 rtl:-scale-x-100" /> {t("common.logout")}
        </Button>
      </div>
    </header>
  );
}
