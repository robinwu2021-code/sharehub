"use client";

// 页内小节标题：标题 + 右侧概要（多半是「n / total 已配置」这类计数）+ 可选说明行。
//
// ## 为什么要收一件
//
// 页头（`PageTitle` / `TabHeader`）与卡片标题（`CardTitle`）都有唯一实现，
// 唯独「一屏里再分一小节」这层没有 —— 实测 20 处 `<h2>` / `<h3>` 长出了 **11 种写法**，
// 其中两种绕开了七档类型阶：
//
//   · `text-sm font-medium`                        ×6
//   · `text-[15px] font-extrabold tracking-[-0.2px]` ×1  ← 手写像素与字重
//
// 它们的意图是同一个。收成一件之后，字阶由这里定，调用点只说「标题是什么」。
//
// ⚠️ 类型阶不与 `font-*` / `leading-*` 共存（`.txt-*` 定义在 globals.css 的非 layer 区，
// 永远胜出，共存时那些工具类是死代码）—— 所以这里用 `txt-strong` 裸档，不叠字重。

import * as React from "react";
import { cn } from "@/lib/utils";

export function SectionHeader({
  title, summary, desc, action, className,
}: {
  title: React.ReactNode;
  /**
   * 右侧概要，与标题**同一基线**（`items-baseline`，不是 `items-center` ——
   * 两种字号居中对齐时视觉上会差半个字）。带 `tabular-nums`：
   * 这里放的多半是计数，数字跳动时宽度不该跟着抖。
   */
  summary?: React.ReactNode;
  /** 说明行，落在标题下方。一句话讲清这小节管什么；**风险提示不放这里**（那是 `Notice`） */
  desc?: React.ReactNode;
  /** 右端操作（新增、导出）。有它时 summary 让到它左边 */
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn(desc ? "mb-3" : "mb-2", className)}>
      <div className="flex items-baseline justify-between gap-3">
        <div className="flex min-w-0 items-baseline gap-2">
          <h2 className="truncate txt-strong">{title}</h2>
          {summary && <span className="shrink-0 txt-caption tabular-nums text-muted-foreground">{summary}</span>}
        </div>
        {action && <div className="shrink-0 self-center">{action}</div>}
      </div>
      {desc && <p className="mt-0.5 txt-caption text-muted-foreground">{desc}</p>}
    </div>
  );
}
