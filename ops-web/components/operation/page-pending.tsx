"use client";

// 运营管理新菜单：尚未建成的页面占位（F1 地基阶段）。
//
// 不是「暂无数据」式的泛化空态——要说清三件事：这页将来做什么、现在为什么还不能用、
// 同样的功能眼下去哪里用。过渡期旧菜单仍在，占位页必须把人引回可用的旧入口，
// 否则新菜单上线反而让运营找不到功能。
import Link from "next/link";
import { Construction } from "lucide-react";
import { PageTitle } from "@/components/ui/misc";
import { useI18n } from "@/lib/i18n";

export interface PagePendingProps {
  /** 导航中文标签（经 tNav 翻译后作标题）。 */
  label: string;
  /** 这页将来做什么（一句话，对标简电的哪个功能）。 */
  purpose: string;
  /** 为什么现在还不能用。 */
  reason: string;
  /** 过渡期可用的旧入口；没有对应旧功能时不传。 */
  legacy?: { href: string; label: string };
}

export function PagePending({ label, purpose, reason, legacy }: PagePendingProps) {
  const { tNav } = useI18n();
  return (
    <div>
      <PageTitle title={tNav(label)} />
      <div className="flex flex-col items-center gap-3 rounded-card bg-muted/50 px-6 py-16 text-center">
        <Construction className="size-8 text-muted-foreground" aria-hidden />
        <p className="max-w-lg txt-body">{purpose}</p>
        <p className="max-w-lg txt-caption text-muted-foreground">{reason}</p>
        {legacy && (
          <Link
            href={legacy.href}
            className="mt-2 rounded-control bg-secondary px-4 py-2 txt-strong text-foreground transition-colors hover:bg-accent"
          >
            过渡期请使用：{legacy.label}
          </Link>
        )}
      </div>
    </div>
  );
}
