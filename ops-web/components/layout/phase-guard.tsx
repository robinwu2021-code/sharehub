"use client";

// 分期不再拦截路由：P2/P3 仅在导航中作「标识」（徽标），页面正常可访问。
// 保留此组件占位以兼容 AppShell 的引用与 Suspense 边界；如需恢复分期门禁，在此接回 routeLockedPhase。
export function PhaseGuard({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
