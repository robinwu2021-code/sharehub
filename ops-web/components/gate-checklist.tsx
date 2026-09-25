"use client";

// 门禁清单（方案 C6）：渲染后端 `Checklist{allPassed, items[]}`。
// 用在设备上线门禁 · 站点开业 / 关闭 · 代理清退每一步。
//
// 两条硬规矩：
// 1. **allPassed 用服务端给的**，不在前端 every() —— 两处算法迟早不一致。
// 2. **未通过必须给去处**：只说缺什么而不给链接，门禁就成了拦路虎而不是向导。
import Link from "next/link";
import { Check, X } from "lucide-react";
import type { Checklist } from "@/lib/types";
import { Notice } from "@/components/ui/notice";
import { Skeleton } from "@/components/ui/misc";
import { cn } from "@/lib/utils";

export function GateChecklist({
  data, loading, passedHint, blockedHint, className,
}: {
  data: Checklist | undefined;
  loading?: boolean;
  /** 全部通过时顶部的一句话（例：「可以上线」）。 */
  passedHint?: string;
  /** 未全过时顶部的一句话（例：「还差 N 项，逐条处理后再上线」）；`{n}` 会替换成未通过条数。 */
  blockedHint?: string;
  className?: string;
}) {
  if (loading && !data) return <Skeleton className="h-24" />;
  if (!data) return null;
  const pending = data.items.filter((i) => !i.passed).length;
  return (
    <div className={cn("space-y-3", className)}>
      {data.allPassed
        ? passedHint && <Notice className="mb-0 bg-success-tint text-success-ink">{passedHint}</Notice>
        : blockedHint && <Notice className="mb-0 bg-warning-tint text-warning-ink">{blockedHint.replace("{n}", String(pending))}</Notice>}
      <ol className="space-y-2.5">
        {data.items.map((it) => (
          <li key={it.key} className="flex items-start gap-2.5">
            {/* 图标 + 文字两路表达通过与否：不只靠颜色（规范 §11.4） */}
            <span
              className={cn(
                "mt-0.5 inline-flex size-5 shrink-0 items-center justify-center rounded-chip",
                it.passed ? "bg-success-tint text-success-ink" : "bg-warning-tint text-warning-ink",
              )}
              aria-label={it.passed ? "已满足" : "未满足"}
            >
              {it.passed ? <Check className="size-3.5" /> : <X className="size-3.5" />}
            </span>
            <div className="min-w-0 flex-1">
              <div className="txt-strong">{it.label}</div>
              {it.detail && <div className="txt-caption text-muted-foreground">{it.detail}</div>}
              {!it.passed && it.fixHref && (
                <Link href={it.fixHref} className="txt-caption text-primary underline-offset-2 hover:underline">去处理</Link>
              )}
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}
