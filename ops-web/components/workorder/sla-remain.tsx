"use client";

// SLA 剩余（方案 §8.4）：倒计时；两小时内到期 warning，超时 danger 并写「超 2 小时」。
// **形状 + 颜色**（规范 §11.4）：◆ 即将超时、▲ 已超时 —— 色觉障碍的人靠形状也分得清。
// 阈值与摘要条「即将超时」同一个数（WO_DUE_SOON_MINUTES），否则摘要说 18 张、列表只黄了 3 行。
import { cn } from "@/lib/utils";
import { fmtMinutes, woSlaRemain, WO_DUE_SOON_MINUTES, type WorkOrder } from "@/lib/types";

export function SlaRemain({ w, className }: { w: WorkOrder; className?: string }) {
  const remain = woSlaRemain(w);
  if (remain == null) {
    return <span className={cn("text-muted-foreground", className)}>{w.status === "DONE" || w.status === "CLOSED" || w.status === "AUDITED" ? "已完工" : "无时限"}</span>;
  }
  if (remain < 0) {
    return (
      <span className={cn("whitespace-nowrap tabular-nums text-destructive-ink", className)} title="已超过解决时限">
        ▲ 超 {fmtMinutes(remain)}
      </span>
    );
  }
  if (remain < WO_DUE_SOON_MINUTES) {
    return (
      <span className={cn("whitespace-nowrap tabular-nums text-warning-ink", className)} title="两小时内到期">
        ◆ 剩 {fmtMinutes(remain)}
      </span>
    );
  }
  return <span className={cn("whitespace-nowrap tabular-nums text-muted-foreground", className)}>剩 {fmtMinutes(remain)}</span>;
}
