"use client";

import { Suspense, useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { Rail } from "./rail";
import { SecondaryNav } from "./secondary-nav";
import { Header } from "./header";
import { PhaseGuard } from "./phase-guard";

/**
 * 不需要登录态的路由。**这是全站唯一的免登录清单**，加页面时必须同步这里。
 *
 * 它此前是一句硬编码的 `norm === "/login"`（2026-09-23 改，D6a）——
 * 入驻自助注册页面向的是**还没有账号的陌生人**，不改的话它会被守卫一路弹回登录页。
 *
 * ⚠️ 免登录**不等于**不设防：`/apply` 的后端端点靠手机号 OTP + 单 IP 限流 +
 * 「同手机号至多一张在途」三道闸（ADR-030 §三）。前端这份清单只管「放不放行进页面」。
 */
const PUBLIC_PATHS = ["/login", "/apply"] as const;

// 登录守卫 + 三级导航布局：Rail(L1) + SecondaryNav(L2/L3，单模块域自隐) + Header/main。
// 未登录跳 /login；免登录页自身不套 shell。ready 门保证导航在 hydration 后渲染（persist 安全）。
export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const loggedIn = useAuth((s) => s.loggedIn());
  const [ready, setReady] = useState(false);

  // trailingSlash:true → pathname 可能带尾斜杠，归一化后再比较。
  const norm = pathname.replace(/\/+$/, "") || "/";
  const isPublic = PUBLIC_PATHS.some((p) => norm === p || norm.startsWith(p + "/"));

  useEffect(() => {
    setReady(true);
  }, []);

  useEffect(() => {
    if (ready && !loggedIn && !isPublic) router.replace("/login");
  }, [ready, loggedIn, isPublic, router]);

  if (isPublic) return <>{children}</>;
  if (!ready || !loggedIn) return null;

  return (
    // 规范：设计规范-布局与外壳.md §2 —— 只用两级色调，全幅铺满不留边距。
    <div data-shell="page" className="flex h-screen overflow-hidden">
      {/* 一整块白底，中间嵌一条灰带：灰色的二级面板自己就是 rail 与 content 的分隔 */}
      <div data-shell="nav" className="flex min-h-0 shrink-0 overflow-hidden">
        <Rail />
        {/* SecondaryNav 读 useSearchParams（静态导出要求包 Suspense） */}
        <Suspense fallback={null}>
          <SecondaryNav />
        </Suspense>
      </div>

      {/* 内容纸：header 吸顶不滚，页体独立滚动 */}
      <div data-shell="sheet" className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
        {/* Header 放进滚动容器内 sticky：静止时与内容同底无感知，滚动时内容从
            毛玻璃下穿过、自然出现分界 —— 零线条（Linear/GitHub 的做法）。 */}
        <main className="flex-1 overflow-y-auto">
          <Header />
          <div className="p-6 pt-2">
          {/* PhaseGuard 读 useSearchParams（静态导出要求包 Suspense） */}
          <Suspense fallback={null}>
            <PhaseGuard>{children}</PhaseGuard>
          </Suspense>
          </div>
        </main>
      </div>
    </div>
  );
}
