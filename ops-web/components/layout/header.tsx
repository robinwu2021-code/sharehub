"use client";

import { Suspense } from "react";
import { MobileNav } from "./mobile-nav";
import { useViewer } from "@/lib/hooks/use-viewer";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { IS_MOCK } from "@/lib/api";
import { signOut } from "@/lib/api/session";
import { breadcrumb } from "@/lib/nav";
import { useI18n } from "@/lib/i18n";
import { OperatorSwitcher } from "./operator-switcher";
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
  const viewer = useViewer();
  const { tNav } = useI18n();
  const crumbs = breadcrumb(pathname, sp.get("tab"), sp.get("view"), viewer);
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
  const { username, role, currentOperatorNo, memberships } = useAuth();
  const { t } = useI18n();
  const router = useRouter();
  return (
    <header className="sticky top-0 z-[var(--z-sticky)] flex h-14 items-center justify-between gap-2 bg-[color-mix(in_srgb,var(--surface)_82%,transparent)] px-4 backdrop-blur-md sm:px-6">
      {/* 窄屏（商家用手机，需求方案 §九）：面包屑收起 —— 页标题已说明位置，挤在 375 宽里只会一字一行；
          「代理」字样、Mock 标、用户名也收起，只留主体名与必要按钮 */}
      <div className="flex min-w-0 items-center gap-3 txt-body text-muted-foreground">
        <MobileNav />
        <Suspense fallback={null}>
          <div className="hidden md:block"><Breadcrumb /></div>
        </Suspense>
        {/* 多主体时给切换器，单主体仍只显示一行字 —— 只有一个选项的下拉框是噪音 */}
        {role === "AGENT" && (
          memberships.length > 1
            ? <><span className="hidden sm:inline">{t("common.agent")}</span><OperatorSwitcher /></>
            : <span className="truncate"><span className="hidden sm:inline">{t("common.agent")} </span><span className="text-foreground">{currentOperatorNo || "-"}</span></span>
        )}
        {IS_MOCK && <span className="hidden sm:inline-flex"><Badge tone="warning">{t("common.mockData")}</Badge></span>}
      </div>
      <div className="flex shrink-0 items-center gap-2 txt-body">
        <LangSwitcher />
        <ThemeSwitcher />
        {/* 角色名不再单独占位：进用户名的 title。此前"运营管理员 admin"两段文本
            说的是同一个人，占了顶栏最大的一块。 */}
        <span className="hidden font-medium sm:inline" title={t(`role.${role}`)}>{username}</span>
        <Button
          size="sm"
          variant="ghost"
          aria-label={t("common.logout")}
          title={t("common.logout")}
          onClick={async () => {
            // signOut 会先请后端吊销 token 再清本地 —— 只调 store 的 logout()
            // 等于令牌在服务端一直有效到过期（本次修复前的实际行为）。
            await signOut();
            router.push("/login");
          }}
        >
          <LogOut className="size-4 rtl:-scale-x-100" />
        </Button>
      </div>
    </header>
  );
}
