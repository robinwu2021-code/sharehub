"use client";

// 开关原语（启用/停用）。
//
// **为什么需要它**：运营台里「启用/停用」是最高频的单条状态切换（套餐、点位、白名单、
// 代理、告警规则…），现在全靠一个「启用/停用」按钮 + 二次确认，或者一个 `EnabledBadge`
// 加一列操作按钮。按钮表达不出「当前处于哪一态」—— 用户得先读徽章再读按钮文案，
// 而这两者的语义方向还相反（徽章说现状、按钮说下一步），这是实测最容易看错的一处。
//
// **Switch vs Checkbox**：Switch 是**立即生效**的状态切换（切完就发请求）；
// Checkbox 是**待提交**的表单勾选（要点保存才生效）。抽屉表单里的布尔字段用 Checkbox，
// 列表行里的启用位用 Switch。这条不区分，就会出现「开关拨了但没保存」的歧义。
//
// **不可撤销的切换不要只给 Switch**：调用方自行接 `useConfirm`，
// 本原语不内建确认（那是业务判断，原语不认业务）。
import * as React from "react";
import * as SwitchPrimitive from "@radix-ui/react-switch";
import { cn } from "@/lib/utils";

export interface SwitchProps
  extends Omit<React.ComponentPropsWithoutRef<typeof SwitchPrimitive.Root>, "onCheckedChange" | "onChange"> {
  /** 回调直接给值（README 约定）。 */
  onChange?: (checked: boolean) => void;
}

export const Switch = React.forwardRef<
  React.ComponentRef<typeof SwitchPrimitive.Root>,
  SwitchProps
>(({ className, onChange, ...props }, ref) => (
  <SwitchPrimitive.Root
    ref={ref}
    onCheckedChange={onChange}
    className={cn(
      // 药丸槽 + 白色药丸滑块 = C 端 pb-segmented 的同一套形状语言。
      "peer inline-flex h-5 w-9 shrink-0 items-center rounded-chip p-0.5",
      "bg-secondary data-[state=checked]:bg-primary",
      "transition-colors duration-[var(--dur-fast)] ease-[var(--ease)]",
      "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]",
      "disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50",
      "cursor-pointer",
      className,
    )}
    {...props}
  >
    <SwitchPrimitive.Thumb
      className={cn(
        "pointer-events-none block size-4 rounded-chip bg-card shadow-[var(--card-shadow)]",
        "transition-transform duration-[var(--dur-fast)] ease-[var(--ease)]",
        // RTL：滑块要往**起始侧**的反方向走，故整体镜像 translate。
        "translate-x-0 data-[state=checked]:translate-x-4 rtl:data-[state=checked]:-translate-x-4",
      )}
    />
  </SwitchPrimitive.Root>
));
Switch.displayName = "Switch";

/**
 * 带文字的开关。文字在**起始侧**、开关在末尾侧（列表/设置行的通行排布）。
 * `desc` 说明「打开会发生什么」—— 开关是立即生效的，副作用必须写在旁边。
 */
export function SwitchField({
  label, desc, checked, onChange, disabled, className, id,
}: {
  label: React.ReactNode;
  desc?: React.ReactNode;
  checked?: boolean;
  onChange?: (checked: boolean) => void;
  disabled?: boolean;
  className?: string;
  id?: string;
}) {
  const autoId = React.useId();
  const inputId = id ?? autoId;
  return (
    <div className={cn("flex items-center justify-between gap-3", className)}>
      <label
        htmlFor={inputId}
        className={cn("select-none text-sm leading-tight", disabled ? "cursor-not-allowed opacity-50" : "cursor-pointer")}
      >
        {label}
        {desc && <span className="mt-0.5 block text-xs font-normal text-muted-foreground">{desc}</span>}
      </label>
      <Switch id={inputId} checked={checked} onChange={onChange} disabled={disabled} />
    </div>
  );
}
