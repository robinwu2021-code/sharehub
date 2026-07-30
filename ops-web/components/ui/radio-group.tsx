"use client";

// 单选组原语。
//
// **为什么需要它**：全站没有任何单选控件，于是「二选一/三选一」的场景全被挤到别的形状里 ——
// `FormDrawer` 的 `FieldDef` 只有 select，Tabs 被当过开关用。选项 ≤4 且需要同时可见时，
// 下拉是错的形状（多一次点击、看不到全部选项）。
//
// **为什么用 Radix**：一组单选必须整组只占一个 Tab 位、组内用方向键切换（WAI-ARIA
// radiogroup 规范），这条自绘一定会漏 —— 交给 Radix。
//
// **边界**：
// - 互斥且选项少、需要全部可见 → `RadioGroup`
// - 互斥且是「页内视图维度」→ `Tabs`（有导航语义，会改变整块内容）
// - 互斥但选项多 → `Select` / `FilterSelect`
// - 单个开关（启用/停用）→ `Switch`，不要用两个 radio
import * as React from "react";
import * as RadioGroupPrimitive from "@radix-ui/react-radio-group";
import { cn } from "@/lib/utils";

export interface RadioGroupProps
  extends Omit<React.ComponentPropsWithoutRef<typeof RadioGroupPrimitive.Root>, "onValueChange" | "onChange"> {
  /** 回调直接给值（README 约定）。 */
  onChange?: (value: string) => void;
  /** 横排（默认竖排）。选项文案短、总宽可控时用横排。 */
  orientation?: "horizontal" | "vertical";
}

export const RadioGroup = React.forwardRef<
  React.ComponentRef<typeof RadioGroupPrimitive.Root>,
  RadioGroupProps
>(({ className, onChange, orientation = "vertical", ...props }, ref) => (
  <RadioGroupPrimitive.Root
    ref={ref}
    orientation={orientation}
    onValueChange={onChange}
    className={cn("flex gap-3", orientation === "horizontal" ? "flex-row flex-wrap items-center" : "flex-col", className)}
    {...props}
  />
));
RadioGroup.displayName = "RadioGroup";

/** 裸单选点（无文字）。绝大多数场景请用 `Radio`。 */
export const RadioGroupItem = React.forwardRef<
  React.ComponentRef<typeof RadioGroupPrimitive.Item>,
  React.ComponentPropsWithoutRef<typeof RadioGroupPrimitive.Item>
>(({ className, ...props }, ref) => (
  <RadioGroupPrimitive.Item
    ref={ref}
    className={cn(
      // 单选必须是**圆**的（与方形 Checkbox 形成形状区分：圆 = 互斥，方 = 可多选）。
      "inline-flex size-4 shrink-0 items-center justify-center rounded-chip bg-secondary",
      "transition-colors duration-[var(--dur-fast)] ease-[var(--ease)]",
      "data-[state=checked]:bg-primary",
      "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]",
      "disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50",
      "cursor-pointer",
      className,
    )}
    {...props}
  >
    <RadioGroupPrimitive.Indicator className="size-1.5 rounded-chip bg-primary-foreground" />
  </RadioGroupPrimitive.Item>
));
RadioGroupItem.displayName = "RadioGroupItem";

/**
 * 带文字的单选项（点文字也能选）。
 * `desc` 给补充说明 —— 单选常用于「选一种策略」，光看主文案判断不了差别。
 */
export function Radio({
  value, label, desc, disabled, className, id,
}: {
  value: string;
  label: React.ReactNode;
  desc?: React.ReactNode;
  disabled?: boolean;
  className?: string;
  id?: string;
}) {
  const autoId = React.useId();
  const inputId = id ?? autoId;
  return (
    <div className={cn("flex items-start gap-2", className)}>
      <RadioGroupItem id={inputId} value={value} disabled={disabled} className="mt-0.5" />
      <label
        htmlFor={inputId}
        className={cn("select-none text-sm leading-tight", disabled ? "cursor-not-allowed opacity-50" : "cursor-pointer")}
      >
        {label}
        {desc && <span className="mt-0.5 block text-xs font-normal text-muted-foreground">{desc}</span>}
      </label>
    </div>
  );
}
