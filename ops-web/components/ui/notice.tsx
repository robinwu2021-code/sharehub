import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * 页内提示条（原语）：列表/表单上方的一行灰底小字，说明「当前视图为什么少了点什么」。
 * 只管样式，不管文案语义 —— 权限降级请用业务件 `<ReadOnlyNotice>`（components/read-only-notice）。
 */
export function Notice({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div className={cn("mb-4 rounded-card bg-muted px-3.5 py-2 txt-body text-muted-foreground", className)}>
      {children}
    </div>
  );
}
