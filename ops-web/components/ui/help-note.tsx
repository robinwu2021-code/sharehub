"use client";

// 帮助说明 —— **一个小触发器 + 浮层**，正文不进主界面。
//
// ## 为什么不是常驻的 Notice
//
// 运营端有 45 处 `<Notice>`，其中 **27 处是无条件常驻**（12 个文件），说的多是
// 「这一页是什么、这一栏怎么算」。对第一次来的人有用，而对**每天开同一页的操作员是纯噪声**：
// 他要的那一行数据被两三行解释推到了下面，一页上摞两条时列表要滚半屏才开始。
//
// ## 为什么不是内联展开（details）
//
// 点开会把下面的内容整体推下去 —— 在表格里尤其糟：展开一行说明，整张表跳一下。
// 浮层不占布局流，点开点关列表纹丝不动，这也是「帮助」该有的分量。
//
// ## 什么该换、什么不该
//
// 判据不是文字长短，是**它说的是「现在有事」还是「这是什么」**：
//
//   · **条件渲染的 Notice 不要换**（「这个站点还没有点位」「先选一条再操作」）——
//     它出现本身就是信息，收进浮层等于把当前状态藏了；
//   · 无条件常驻的说明性文字用它；
//   · 说明里带「暂未开放 / 只读 / 待定案」这类**当前限制**的也不要换 ——
//     那是状态，归 `Notice` 或 `ReadOnlyNotice`。

import * as React from "react";
import { HelpCircle } from "lucide-react";
import { cn } from "@/lib/utils";
import { useT } from "@/lib/i18n";
import { Popover, PopoverContent, PopoverTrigger } from "./popover";

export function HelpNote({
  children, title, className,
}: {
  /** 正文。可以是多段；写清「这一页/这一栏是什么、怎么算」 */
  children: React.ReactNode;
  /** 触发器旁的短标签。不给就只有一个问号图标（表头、字段名旁用这种） */
  title?: string;
  className?: string;
}) {
  const t = useT();
  const label = title ?? t("common.helpNote");
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={label}
          className={cn(
            "inline-flex shrink-0 items-center gap-1 rounded-field px-1 py-0.5 txt-caption text-muted-foreground",
            "transition-colors hover:bg-accent hover:text-foreground",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]",
            className,
          )}
        >
          <HelpCircle className="size-3.5" aria-hidden />
          {title && <span>{title}</span>}
        </button>
      </PopoverTrigger>
      <PopoverContent className="max-w-sm txt-body text-muted-foreground">
        {children}
      </PopoverContent>
    </Popover>
  );
}
