"use client";

// 流程实例的流转历史（后端 O2-10 GET /instances/{no}/history）：迁移、任务、审批、SLA、门的事件一条线。
// 标题 = 类别 · 动作（迁移另带「从 → 到」）；次行 = 时间 · 操作人；原因放详情。
// 动作代号不认识时原样显示 —— 看得到代号，比显示空白好查（后端加新动作时这里补文案即可）。
import { FlowTimeline } from "./flow-timeline";
import { useFlowT } from "@/lib/flow/i18n";
import type { HistoryEntry } from "@/lib/flow/types";
import type { FlowMessages } from "@/lib/i18n/messages/flow";

type Action = keyof FlowMessages["instances"]["action"];
const HOT = new Set(["BREACHED", "REJECTED", "TIMEOUT"]);

export function HistoryTimeline({ entries, loading }: { entries: HistoryEntry[] | undefined; loading?: boolean }) {
  const { t, tc, fmt } = useFlowT("instances");
  const actionLabel = (a: string) => {
    const v = t(`action.${a as Action}`);
    return v.startsWith("flow.") ? a : v;
  };
  const who = (e: HistoryEntry) => {
    if (e.actor) return e.actor.displayName || e.actor.ref;
    if (!e.actorText || e.actorText === "system") return tc("system");
    return e.actorText.startsWith("app:") ? t("actorApp", { app: e.actorText.slice(4) }) : e.actorText;
  };
  // 新的在上：与工单时间线同向
  const list = [...(entries ?? [])].sort((a, b) => (b.seq ?? b.atMs) - (a.seq ?? a.atMs));
  return (
    <FlowTimeline
      empty={loading ? tc("loading") : t("detail.empty")}
      items={list.map((e) => ({
        key: `${e.seq ?? e.atMs}-${e.entityNo}-${e.action}`,
        hot: HOT.has(e.action),
        title: fmt.join(t(`kind.${e.entityKind}`), actionLabel(e.action), e.from || e.to ? t("detail.fromTo", { from: e.from ?? "—", to: e.to ?? "—" }) : null),
        meta: fmt.join(fmt.when(e.atMs), who(e), e.entityKind !== "INSTANCE" ? e.entityNo : null),
        detail: e.reason ? t("detail.reason", { text: e.reason }) : undefined,
      }))}
    />
  );
}
