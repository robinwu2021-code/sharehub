"use client";

// SLA 单元格：色条 + 文字（对齐原型）。色条长度 = **剩余**时限的比例，条越短越紧；
// 已超时整条红、已达标整条绿、暂停为灰。
//
// 色条沿用 SlaRemain 的做法：`data-slot="sla-bar"` 默认隐藏，只有「清爽」风格显示 ——
// 经典风格仍是纯文字。色条是装饰（aria-hidden），含义由文字表达，颜色不单独承载语义（规范 §11.4）。
import { cn } from "@/lib/utils";
import { useFlowT } from "@/lib/flow/i18n";
import type { SlaState, SlaSummary } from "@/lib/flow/types";

const MIN = 60_000;

function Bar({ pct, tone }: { pct: number; tone: "ok" | "warn" | "bad" | "mute" }) {
  const bg = tone === "bad" ? "bg-destructive" : tone === "warn" ? "bg-warning" : tone === "mute" ? "bg-muted-foreground" : "bg-success";
  return (
    <i data-slot="sla-bar" aria-hidden="true" className="hidden">
      <i className={bg} style={{ width: `${Math.min(100, Math.max(4, Math.round(pct)))}%` }} />
    </i>
  );
}

/**
 * `sla` 给了就按「起点 · 截止 · 已暂停」算剩余比例画色条（21/06 §七：前端不猜起点）；
 * 没给（例如工单行后端暂未带 SLA 摘要）就退回 `state` + `dueAtMs`，只显示文字、不画条。
 */
export function SlaCell({
  sla, state, dueAtMs, now = Date.now(), className,
}: { sla?: SlaSummary; state?: SlaState; dueAtMs?: number; now?: number; className?: string }) {
  const { tc, fmt } = useFlowT("common");
  const st = sla?.state ?? state ?? "RUNNING";
  const d = (min: number) => fmt.dur(min);
  const due = sla?.dueAtMs ?? dueAtMs;
  const base = cn("inline-flex items-center whitespace-nowrap tabular-nums", className);
  const window = sla ? sla.dueAtMs - sla.startedAtMs : 0;
  const leftPct = sla && window > 0 ? ((sla.dueAtMs + sla.pausedMs - now) / window) * 100 : null;

  switch (st) {
    case "MET":
      return <span className={cn(base, "text-muted-foreground")}><Bar pct={100} tone="ok" />{tc("sla.met")}</span>;
    case "PAUSED":
      return (
        <span className={cn(base, "text-muted-foreground")}>
          {leftPct != null && <Bar pct={leftPct} tone="mute" />}
          {sla?.pausedMs ? tc("sla.pausedFor", { d: d(sla.pausedMs / MIN) }) : tc("sla.paused")}
        </span>
      );
    case "BREACHED":
      return (
        <span className={cn(base, "text-destructive-ink")} title={tc("sla.breachedTitle")}>
          {sla && <Bar pct={100} tone="bad" />}{due != null ? tc("sla.breached", { d: d((now - due) / MIN) }) : tc("none")}
        </span>
      );
    case "WARNING":
      return (
        <span className={cn(base, "text-warning-ink")} title={tc("sla.warnTitle")}>
          {leftPct != null && <Bar pct={leftPct} tone="warn" />}{due != null ? tc("sla.left", { d: d((due - now) / MIN) }) : tc("none")}
        </span>
      );
    default:
      return (
        <span className={cn(base, "text-muted-foreground")}>
          {leftPct != null && <Bar pct={leftPct} tone="ok" />}
          {due != null ? tc("sla.left", { d: d((due - now) / MIN) }) : tc("sla.noLimit")}
        </span>
      );
  }
}
