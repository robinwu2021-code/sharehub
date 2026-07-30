import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * 卡片。形态取自 C 端 pb-card：圆角 30rpx≈15px（= `--radius`）+ 阴影 + **无边框**。
 *
 * `tone` 是 C 端有而我们此前缺的一档：语义 tint 底 + **去掉阴影**。
 * 用在需要"这块要注意"的地方（提示卡、告警摘要、待办聚合），
 * 比给白卡加一圈彩色描边更贴近 C 端语言 —— C 端全局 border-width 是 0。
 */
export function Card({
  className, tone, ...props
}: React.HTMLAttributes<HTMLDivElement> & {
  tone?: "primary" | "success" | "warning" | "danger" | "info";
}) {
  return (
    <div
      className={cn(
        "rounded-card text-card-foreground",
        tone
          ? {
              primary: "bg-[color-mix(in_oklch,var(--primary)_10%,transparent)]",
              success: "bg-success-tint",
              warning: "bg-warning-tint",
              danger: "bg-destructive-tint",
              info: "bg-info-tint",
            }[tone]
          : "bg-card shadow-[var(--card-shadow)]",
        className,
      )}
      {...props}
    />
  );
}
export function CardHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("flex flex-col gap-1 p-5", className)} {...props} />;
}
export function CardTitle({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("font-medium leading-none tracking-tight", className)} {...props} />;
}
export function CardDescription({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("text-sm text-muted-foreground", className)} {...props} />;
}
export function CardContent({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("p-5 pt-0", className)} {...props} />;
}
