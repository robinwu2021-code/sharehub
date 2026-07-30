"use client";

// 复选框原语。
//
// **为什么需要它**：`ui/data-table.tsx` 第 21 行原先就地实现了一个 `RowCheckbox`
// （注释写着「项目无 checkbox 原语」），含半选态。表格是全站唯一一处有复选框的地方，
// 一旦批量操作/权限勾选/字段多选铺开，就会出现第二、第三份就地实现 —— 所以上移成原语。
//
// **为什么用 Radix 而不是原生 `<input type=checkbox>`**：原生控件的勾/半选标记由
// 系统绘制，只能用 `accent-color` 影响填充色，勾的粗细、圆角、半选那一横的长度全不可控，
// 与「药丸 + 零描边 + 重字重」的形状语言对不上；半选态还必须走 ref 副作用（旧实现就是）。
// Radix 把 checked / indeterminate 收成一个受控 `checked: boolean | "indeterminate"`，
// 标记由我们自己画，键盘与 a11y（role/aria-checked）由它保证。
//
// **边界**：这是原语，不认业务。表格的全选/行选语义留在 `DataTable`；
// 「多选下拉」是 `MultiSelect`（组合件），不要用一堆 Checkbox 拼下拉。
import * as React from "react";
import * as CheckboxPrimitive from "@radix-ui/react-checkbox";
import { Check, Minus } from "lucide-react";
import { cn } from "@/lib/utils";

/** 三态：`true` 全选 · `false` 未选 · `"indeterminate"` 半选（部分选中）。 */
export type CheckedState = boolean | "indeterminate";

export interface CheckboxProps
  extends Omit<
    React.ComponentPropsWithoutRef<typeof CheckboxPrimitive.Root>,
    "checked" | "onCheckedChange" | "defaultChecked" | "onChange"
  > {
  /** 受控值。半选传 `"indeterminate"`。 */
  checked?: CheckedState;
  /**
   * 回调直接给值（沿用 README 的「回调给值不给 event」约定）。
   * 半选态被点击时 Radix 给出 `true`，即「半选 → 全选」，与表格全选的直觉一致。
   */
  onChange?: (checked: CheckedState) => void;
  /** 非受控初始值。 */
  defaultChecked?: CheckedState;
}

export const Checkbox = React.forwardRef<
  React.ComponentRef<typeof CheckboxPrimitive.Root>,
  CheckboxProps
>(({ className, checked, defaultChecked, onChange, ...props }, ref) => (
  <CheckboxPrimitive.Root
    ref={ref}
    checked={checked}
    defaultChecked={defaultChecked}
    onCheckedChange={onChange}
    className={cn(
      // ⚠️ 圆角**刻意不走四档**，这是全站唯一一处例外，理由记在这里免得被当成漏改：
      // 控件只有 16px，field 档 11px 已超过半边长（8px）会被浏览器夹成正圆 —— 那就和
      // 单选点长得一模一样，「方=可多选 / 圆=互斥」的形状区分当场失效。四档是为
      // 卡片/输入/弹层这类大面定的，覆盖不到 16px 的小方块。故显式给 4px。
      "peer inline-flex size-4 shrink-0 items-center justify-center rounded-control align-middle",
      // 未选：填充块（同 Input 的 bg-secondary），不描边 —— C 端 border-width 全为 0。
      "bg-secondary text-primary-foreground",
      "transition-colors duration-[var(--dur-fast)] ease-[var(--ease)]",
      "data-[state=checked]:bg-primary data-[state=indeterminate]:bg-primary",
      "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]",
      "disabled:pointer-events-none disabled:opacity-50",
      "cursor-pointer disabled:cursor-not-allowed",
      className,
    )}
    {...props}
  >
    <CheckboxPrimitive.Indicator className="flex items-center justify-center text-current">
      {checked === "indeterminate" ? <Minus className="size-3" strokeWidth={3} /> : <Check className="size-3" strokeWidth={3} />}
    </CheckboxPrimitive.Indicator>
  </CheckboxPrimitive.Root>
));
Checkbox.displayName = "Checkbox";

/**
 * 带文字的复选框（点文字也能勾）。
 *
 * 独立成件而不是让调用方自己拼 `<label>`：勾选框与文字的对齐、行高、disabled 时
 * 文字也要变淡这三件事每处都会写歪。`label` 为空时请直接用 `Checkbox` + `aria-label`。
 */
export function CheckboxField({
  label, checked, onChange, disabled, className, id,
}: {
  label: React.ReactNode;
  checked?: CheckedState;
  onChange?: (checked: CheckedState) => void;
  disabled?: boolean;
  className?: string;
  id?: string;
}) {
  const autoId = React.useId();
  const inputId = id ?? autoId;
  return (
    <div className={cn("flex items-center gap-2", className)}>
      <Checkbox id={inputId} checked={checked} onChange={onChange} disabled={disabled} />
      <label
        htmlFor={inputId}
        className={cn(
          "select-none text-sm leading-none",
          disabled ? "cursor-not-allowed opacity-50" : "cursor-pointer",
        )}
      >
        {label}
      </label>
    </div>
  );
}
