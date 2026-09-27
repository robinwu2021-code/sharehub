"use client";

// SLA 剩余（方案 §8.4）：倒计时；两小时内到期 warning，超时 danger 并写「超 2 小时」。
// **形状 + 颜色**（规范 §11.4）：◆ 即将超时、▲ 已超时 —— 色觉障碍的人靠形状也分得清。
// 阈值与摘要条「即将超时」同一个数（WO_DUE_SOON_MINUTES），否则摘要说 18 张、列表只黄了 3 行。
import { cn } from "@/lib/utils";
import { fmtMinutes, woSlaRemain, WO_DUE_SOON_MINUTES, type WorkOrder } from "@/lib/types";

/**
 * SLA **剩余**的比例（4–100）：从开单到截止这段时限还剩多少 —— 条越短越紧，对齐原型
 * （原型：剩 2 天 ≈80%、剩 28 分钟 ≈12%；已超时整条红、已完工整条绿）。
 * 拿不到截止时间（例如只有后端给的剩余分钟）就返回 null —— 没有分母就不画条，不猜。
 */
function slaLeftPct(w: WorkOrder, remain: number): number | null {
  if (!w.slaDueAt) return null;
  const parse = (t: string) => Date.parse(t.includes("T") ? t : t.replace(" ", "T"));
  const total = (parse(w.slaDueAt) - parse(w.createdAt)) / 60_000;
  if (!Number.isFinite(total) || total <= 0) return null;
  return Math.min(100, Math.max(4, Math.round((remain / total) * 100)));
}

/**
 * 迷你进度条。**默认隐藏**（`hidden`）：只有「清爽」风格在 globals.css 里把它显示出来，
 * 对齐原型的「色条 + 文字」。经典风格仍是纯文字，与改动前一致。
 * 纯装饰（aria-hidden）—— 含义已由旁边的文字表达，颜色不单独承载语义（规范 §11.4）。
 */
function SlaBar({ pct, tone }: { pct: number; tone: "ok" | "warn" | "bad" }) {
  return (
    <i data-slot="sla-bar" aria-hidden="true" className="hidden">
      <i
        className={cn(tone === "bad" ? "bg-destructive" : tone === "warn" ? "bg-warning" : "bg-success")}
        style={{ width: `${pct}%` }}
      />
    </i>
  );
}

export function SlaRemain({ w, className }: { w: WorkOrder; className?: string }) {
  const remain = woSlaRemain(w);
  if (remain == null) {
    const done = w.status === "DONE" || w.status === "CLOSED" || w.status === "AUDITED";
    return (
      <span className={cn("whitespace-nowrap text-muted-foreground", className)}>
        {done && <SlaBar pct={100} tone="ok" />}
        {done ? "已完工" : "无时限"}
      </span>
    );
  }
  const pct = slaLeftPct(w, remain);
  if (remain < 0) {
    return (
      <span className={cn("whitespace-nowrap tabular-nums text-destructive-ink", className)} title="已超过解决时限">
        <SlaBar pct={100} tone="bad" />▲ 超 {fmtMinutes(remain)}
      </span>
    );
  }
  if (remain < WO_DUE_SOON_MINUTES) {
    return (
      <span className={cn("whitespace-nowrap tabular-nums text-warning-ink", className)} title="两小时内到期">
        {pct != null && <SlaBar pct={pct} tone="warn" />}◆ 剩 {fmtMinutes(remain)}
      </span>
    );
  }
  return (
    <span className={cn("whitespace-nowrap tabular-nums text-muted-foreground", className)}>
      {pct != null && <SlaBar pct={pct} tone="ok" />}剩 {fmtMinutes(remain)}
    </span>
  );
}
