"use client";

// 头像原语。
//
// **为什么需要它**：顶栏用户位、审计时间线的操作人、工单的处理人/派单对象、客服会话列表
// 都需要「一个人/一个租户」的视觉锚点，现在全是裸 `<div>` 里塞首字母或干脆只有文字，
// 没有任何一处处理「图挂了怎么办」—— 而运营台的头像来源是外部 URL，挂图是常态。
//
// **为什么用 Radix**：`Avatar.Image` + `Avatar.Fallback` 把「加载中 / 加载失败 → 退回首字母」
// 的状态机做好了，且**不会闪**（fallback 有 `delayMs`，避免图片秒回时先闪一下首字母）。
// 手写这个状态机就是 onError + useState，每处都会漏一半。
//
// **边界**：这是纯展示原语，不认「用户」这个业务概念 —— 传 `name` 只是为了派生首字母与
// `alt`。「显示当前登录用户」属业务件，放 `components/layout/`。
import * as React from "react";
import * as AvatarPrimitive from "@radix-ui/react-avatar";
import { cn } from "@/lib/utils";

const SIZE = {
  sm: "size-6 text-[10px]",
  md: "size-8 text-xs",
  lg: "size-10 text-sm",
} as const;

export type AvatarSize = keyof typeof SIZE;

/**
 * 取首字：中文取姓（首字），拉丁取首字母并大写。
 * 空名回退 "?" —— 绝不渲染空圆圈，那会被当成图还没加载完。
 */
function initial(name?: string): string {
  const s = (name ?? "").trim();
  if (!s) return "?";
  const first = s[0];
  return /[a-zA-Z]/.test(first) ? first.toUpperCase() : first;
}

export interface AvatarProps {
  /** 图片地址。缺省或加载失败即走首字母兜底。 */
  src?: string;
  /** 姓名/名称：用于派生首字母与图片 alt。 */
  name?: string;
  size?: AvatarSize;
  className?: string;
}

export function Avatar({ src, name, size = "md", className }: AvatarProps) {
  return (
    <AvatarPrimitive.Root
      className={cn(
        "relative inline-flex shrink-0 select-none items-center justify-center overflow-hidden rounded-chip align-middle",
        "bg-secondary text-secondary-foreground font-bold",
        SIZE[size],
        className,
      )}
    >
      {src && <AvatarPrimitive.Image src={src} alt={name ?? ""} className="size-full object-cover" />}
      {/* delayMs：图片秒回时不闪首字母 */}
      <AvatarPrimitive.Fallback delayMs={src ? 300 : 0} className="flex size-full items-center justify-center">
        {initial(name)}
      </AvatarPrimitive.Fallback>
    </AvatarPrimitive.Root>
  );
}

/**
 * 头像 + 文字（名称 / 副标题）。列表与时间线里高频，独立成件免得每处对齐都写歪。
 * 间距用 `gap`（flex 自身即逻辑方向），无 `ml-/mr-`，RTL 安全。
 */
export function AvatarLabel({
  src, name, desc, size = "md", className,
}: AvatarProps & { desc?: React.ReactNode }) {
  return (
    <div className={cn("flex items-center gap-2", className)}>
      <Avatar src={src} name={name} size={size} />
      <div className="min-w-0">
        <div className="truncate text-sm font-bold">{name}</div>
        {desc && <div className="truncate text-xs text-muted-foreground">{desc}</div>}
      </div>
    </div>
  );
}
