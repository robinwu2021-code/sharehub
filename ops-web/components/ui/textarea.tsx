"use client";

// 多行输入原语。
//
// **为什么需要它**：实测有 3 处手写 `<textarea>`（`app/work-orders/page.tsx`、
// `app/cs/page.tsx`、`components/ui/form-drawer.tsx`），三处的类名串各自复制粘贴、
// 已经开始漂移（圆角写的是待淘汰的 `rounded-field`，其中两处漏了 `disabled:opacity-50`）。
// 这正是「没有原语 → 每处重抄一遍 → 慢慢不一致」的标准症状。
//
// **与 `Input` 的边界**：单行走 `Input`（同一套 field 圆角 + 填充块），
// 需要换行/长文（处理说明、备注、驳回原因）走 `Textarea`。
// 二者故意保持同样的填充与圆角，只差高度与 `resize`。
//
// 迁移说明：上面 3 处调用点的替换属 P2（本轮只新增原语，不动业务页）。
import * as React from "react";
import { cn } from "@/lib/utils";

export interface TextareaProps extends Omit<React.TextareaHTMLAttributes<HTMLTextAreaElement>, "onChange"> {
  /** 回调直接给值（README 约定）。需要原生 event 时用 `onChangeEvent`。 */
  onChange?: (value: string) => void;
  onChangeEvent?: React.ChangeEventHandler<HTMLTextAreaElement>;
  /** 错误态：加红环 + `aria-invalid`。校验文案由调用方（如 FormDrawer 的 FieldRow）负责。 */
  invalid?: boolean;
}

export const Textarea = React.forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ className, onChange, onChangeEvent, invalid, rows = 3, ...props }, ref) => (
    <textarea
      ref={ref}
      rows={rows}
      aria-invalid={invalid || undefined}
      onChange={(e) => {
        onChangeEvent?.(e);
        onChange?.(e.target.value);
      }}
      className={cn(
        // 与 Input 同源：field 档圆角（11px）+ 填充块、零描边。
        // 只允许纵向 resize —— 横向拉伸会撑破抽屉栅格。
        "flex w-full resize-y rounded-field bg-secondary px-3.5 py-2 text-sm",
        "transition-colors duration-[var(--dur-fast)] ease-[var(--ease)] placeholder:text-muted-foreground",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]",
        "disabled:cursor-not-allowed disabled:opacity-50",
        invalid && "ring-2 ring-destructive",
        className,
      )}
      {...props}
    />
  ),
);
Textarea.displayName = "Textarea";
