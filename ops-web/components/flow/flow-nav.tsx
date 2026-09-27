"use client";

// 流程与工单模块的页内导航（临时）。
// 这些页还没进全站菜单：flow:* 权限码未进真源表，按 CLAUDE.md 不能先用，也就不能挂菜单叶。
// 在那之前用这条导航让模块内的页面互相可达；进菜单后删掉本组件即可，页面本身不依赖它。
import Link from "next/link";
import { usePathname } from "next/navigation";
import { segmentedItemClass, segmentedTrackClass } from "@/components/ui/segmented";

export const FLOW_PAGES = [
  { href: "/flow/todo/", label: "待办中心" },
  { href: "/flow/approvals/", label: "审批列表" },
  { href: "/flow/queue/", label: "审核队列" },
  { href: "/flow/tickets/", label: "工单工作台" },
  { href: "/flow/processes/", label: "流程配置" },
  { href: "/flow/settings/", label: "运营配置" },
  { href: "/flow/access/", label: "接入管理" },
] as const;

export function FlowNav() {
  const path = usePathname() ?? "";
  const norm = (p: string) => p.replace(/\/+$/, "");
  return (
    <nav aria-label="流程与工单" className="mb-4">
      <div className={segmentedTrackClass("ui-seg-block max-w-full flex-wrap")}>
        {FLOW_PAGES.map((p) => {
          const active = norm(path).startsWith(norm(p.href));
          return (
            <Link key={p.href} href={p.href} aria-current={active ? "page" : undefined}
              className={segmentedItemClass(active, "px-3.5 py-1.5 text-sm")}>
              {p.label}
            </Link>
          );
        })}
      </div>
    </nav>
  );
}
