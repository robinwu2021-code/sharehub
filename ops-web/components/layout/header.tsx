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

// 面包屑：L1 › 分组 › 子功能（URL 反推，标签经 tNav 本地化）。分组为不可点的中间项。
// 读 useSearchParams → 包 Suspense。
function Breadcrumb() {
  const pathname = usePathname();
  const sp = useSearchParams();
  const role = useAuth((s) => s.role);
  const { tNav } = useI18n();
  const crumbs = breadcrumb(pathname, sp.get("tab"), sp.get("view"), role);
  if (!crumbs.length) return null;
  return (
    <nav aria-label="breadcrumb" className="flex items-center gap-1 txt-body text-muted-foreground">
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
    <header className="sticky top-0 z-[var(--z-sticky)] flex h-14 items-center justify-between bg-[color-mix(in_srgb,var(--surface)_82%,transparent)] px-6 backdrop-blur-md">
      <div className="flex items-center gap-3 txt-body text-muted-foreground">
        <Suspense fallback={null}>
          <Breadcrumb />
        </Suspense>
        {role === "AGENT" && <span>{t("common.agent")} <span className="text-foreground">{agentNo || "-"}</span></span>}
        {IS_MOCK && <Badge tone="warning">{t("common.mockData")}</Badge>}
      </div>
      <div className="flex items-center gap-2 txt-body">
        <LangSwitcher />
        <ThemeSwitcher />
        {/* 角色名不再单独占位：进用户名的 title。此前"运营管理员 admin"两段文本
            说的是同一个人，占了顶栏最大的一块。 */}
        <span className="font-medium" title={t(`role.${role}`)}>{username}</span>
        <Button
          size="sm"
          variant="ghost"
          aria-label={t("common.logout")}
          title={t("common.logout")}
          onClick={() => {
            logout();
            router.push("/login");
          }}
        >
          <LogOut className="size-4 rtl:-scale-x-100" />
        </Button>
      </div>
    </header>
  );
}
