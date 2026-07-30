"use client";

// 分隔线原语。
//
// **为什么需要它**：实测各处分隔全是裸 `<div className="h-px bg-border" />` / `border-t`
// / `<hr>` 混用，且都缺 a11y 语义（屏读器会把它当内容读或完全忽略分组）。
// Radix 的 Separator 默认 `role="none"`（纯装饰，屏读器跳过），传 `decorative={false}`
// 才输出 `role="separator"` —— 这个区分手写基本不会做。
//
// **注意本项目的边框克制原则**（见 `设计规范-运营端配色与形状.md` §三）：
// C 端 `border-width` 全为 0，运营端只在**表格行分隔**破例。所以 Separator 的正当用途是
// 「同一容器内两组内容的语义分界」（下拉菜单里危险操作与普通操作之间、抽屉里两个分区之间），
// **不是**给卡片/工具栏/输入框描边 —— 那些一律走填充块 + 阴影。
import * as React from "react";
import * as SeparatorPrimitive from "@radix-ui/react-separator";
import { cn } from "@/lib/utils";

export const Separator = React.forwardRef<
  React.ComponentRef<typeof SeparatorPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof SeparatorPrimitive.Root>
>(({ className, orientation = "horizontal", decorative = true, ...props }, ref) => (
  <SeparatorPrimitive.Root
    ref={ref}
    orientation={orientation}
    decorative={decorative}
    className={cn(
      "shrink-0 bg-border",
      orientation === "horizontal" ? "h-px w-full" : "h-full w-px",
      className,
    )}
    {...props}
  />
));
Separator.displayName = "Separator";
