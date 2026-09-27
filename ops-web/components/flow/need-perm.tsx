"use client";

// 页面级查看守卫：没有查看权限就说清缺哪个码，不渲染内容。
// 菜单已按权限隐藏入口；这里防的是深链直达（真正的闸在 OpsKit，这里只是不让人看到一页注定 403 的空壳）。
// 页内写操作用各页的 allow("…") + <ReadOnlyNotice>，不走这里。
import { useCan } from "@/lib/hooks/use-can";
import { Notice } from "@/components/ui/notice";

export function NeedPerm({ code, what, children }: { code: string; what: string; children: React.ReactNode }) {
  const allow = useCan();
  if (!allow(code)) {
    return <Notice>当前角色没有「{what}」的查看权限（{code}），请联系管理员在角色权限里开通。</Notice>;
  }
  return <>{children}</>;
}
