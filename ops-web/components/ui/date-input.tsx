"use client";

// 日期输入：原生 <input type="date">（output: export 静态导出下最稳，无需第三方库）。
// 值格式固定 YYYY-MM-DD，样式与 components/ui/input.tsx 的 Input 完全一致。
import * as React from "react";
import { cn } from "@/lib/utils";

export const DateInput = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => (
    <input
      ref={ref}
      type="date"
      className={cn(
        "flex h-[var(--ctl-h)] w-full rounded-field bg-secondary px-3.5 py-1 text-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)] disabled:opacity-50",
        "[&::-webkit-calendar-picker-indicator]:opacity-60 [&::-webkit-calendar-picker-indicator]:hover:opacity-100",
        className,
      )}
      {...props}
    />
  ),
);
DateInput.displayName = "DateInput";
