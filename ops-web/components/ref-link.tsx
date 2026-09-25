"use client";

// 业务号 → 该对象详情的链接（方案 C5 / 规则 R3）。
//
// 路由规则**只在这里维护一处**：详情抽屉统一读 `?no=`，详情页用路径。
// 各页自己拼 href 的话，某页把抽屉参数从 `no` 改成 `id`，全站指向它的链接静默失效 —— 不报错，只是点了没反应。
//
// 无权限时显示纯文本 + 提示缺哪个码：让它可点然后 403，比不可点坏得多（规范 §13）。
import Link from "next/link";
import { useCan } from "@/lib/hooks/use-can";
import { cn } from "@/lib/utils";

export type RefKind =
  | "alarm" | "wo" | "contract" | "site" | "cabinet" | "order" | "lead"
  | "agent" | "transfer" | "settlement";

/** kind → [详情 href 生成器, 查看它需要的权限码]。 */
export const REF_ROUTES: Record<RefKind, { href: (no: string) => string; perm: string }> = {
  alarm: { href: (no) => `/alarms?no=${encodeURIComponent(no)}`, perm: "workorder:alarm:read" },
  wo: { href: (no) => `/work-orders?view=list&no=${encodeURIComponent(no)}`, perm: "workorder:wo:read" },
  contract: { href: (no) => `/venues?tab=contracts&no=${encodeURIComponent(no)}`, perm: "location:contract:read" },
  site: { href: (no) => `/operation/sites?no=${encodeURIComponent(no)}`, perm: "location:poi:read" },
  cabinet: { href: (no) => `/devices/detail?no=${encodeURIComponent(no)}`, perm: "device:cabinet:read" },
  order: { href: (no) => `/orders?no=${encodeURIComponent(no)}`, perm: "order:order:read" },
  lead: { href: (no) => `/venues?tab=crm&no=${encodeURIComponent(no)}`, perm: "location:lead:read" },
  agent: { href: (no) => `/agents?no=${encodeURIComponent(no)}`, perm: "agent:agent:read" },
  transfer: { href: (no) => `/devices?tab=inventory&no=${encodeURIComponent(no)}`, perm: "device:inventory:read" },
  settlement: { href: (no) => `/finance?tab=settlements&no=${encodeURIComponent(no)}`, perm: "finance:settlement:read" },
};

export function RefLink({
  kind, no, label, className,
}: {
  kind: RefKind;
  /** 业务号；空值渲染「-」，调用方不必自己判空。 */
  no: string | null | undefined;
  /** 显示文字，默认就是业务号。 */
  label?: React.ReactNode;
  className?: string;
}) {
  const allow = useCan();
  if (!no) return <span className="text-muted-foreground">-</span>;
  const r = REF_ROUTES[kind];
  const text = label ?? no;
  if (!allow(r.perm)) {
    return (
      <span className={cn("tabular-nums", className)} title={`无查看权限（需要 ${r.perm}）`}>{text}</span>
    );
  }
  return (
    <Link href={r.href(no)} className={cn("tabular-nums underline-offset-2 hover:underline text-primary", className)}>
      {text}
    </Link>
  );
}
