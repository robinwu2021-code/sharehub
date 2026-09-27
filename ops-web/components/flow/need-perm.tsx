"use client";

// 页面级查看守卫：没有查看权限就说清缺哪个码，不渲染内容。
// 菜单已按权限隐藏入口；这里防的是深链直达（真正的闸在 OpsKit，这里只是不让人看到一页注定 403 的空壳）。
// 页内写操作用各页的 allow("…") + <ReadOnlyNotice>，不走这里。
import { useCan } from "@/lib/hooks/use-can";
import { Notice } from "@/components/ui/notice";
import { useFlowT } from "@/lib/flow/i18n";
import type { FlowMessages } from "@/lib/i18n/messages/flow";

export function NeedPerm({ code, page, children }: { code: string; page: keyof FlowMessages["common"]["page"]; children: React.ReactNode }) {
  const allow = useCan();
  const { tc } = useFlowT("common");
  if (!allow(code)) {
    return <Notice>{tc("needPerm", { what: tc(`page.${page}`), code })}</Notice>;
  }
  return <>{children}</>;
}
