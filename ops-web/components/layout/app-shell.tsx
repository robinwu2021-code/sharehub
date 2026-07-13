"use client";

import { Suspense, useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { Rail } from "./rail";
import { SecondaryNav } from "./secondary-nav";
import { Header } from "./header";
import { PhaseGuard } from "./phase-guard";

// 登录守卫 + 三级导航布局：Rail(L1) + SecondaryNav(L2/L3，单模块域自隐) + Header/main。
// 未登录跳 /login；/login 页自身不套 shell。ready 门保证导航在 hydration 后渲染（persist 安全）。
export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const loggedIn = useAuth((s) => s.loggedIn());
  const [ready, setReady] = useState(false);

  // trailingSlash:true → pathname 可能带尾斜杠，归一化后再比较。
  const norm = pathname.replace(/\/+$/, "") || "/";
  const isLogin = norm === "/login";

  useEffect(() => {
    setReady(true);
  }, []);

  useEffect(() => {
    if (ready && !loggedIn && !isLogin) router.replace("/login");
  }, [ready, loggedIn, isLogin, router]);

  if (isLogin) return <>{children}</>;
  if (!ready || !loggedIn) return null;

  return (
    <div className="flex h-screen overflow-hidden">
      <Rail />
      {/* SecondaryNav 读 useSearchParams（静态导出要求包 Suspense） */}
      <Suspense fallback={null}>
        <SecondaryNav />
      </Suspense>
      <div className="flex min-w-0 flex-1 flex-col">
        <Header />
        <main className="flex-1 overflow-y-auto p-6">
          {/* PhaseGuard 读 useSearchParams（静态导出要求包 Suspense） */}
          <Suspense fallback={null}>
            <PhaseGuard>{children}</PhaseGuard>
          </Suspense>
        </main>
      </div>
    </div>
  );
}
