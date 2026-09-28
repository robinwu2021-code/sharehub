"use client";

// 流程实例的流转历史（后端 O2-10 GET /instances/{no}/history）：迁移、任务、审批、SLA 的事件一条线。
// 动作词表按后端 37e78b21（全小写原始代号，见 types.ts HistoryEntry）：
//  · INSTANCE 的迁移代号是租户在流程里定义的 —— 不做词表，直接显示服务给的 actionName / fromName / toName；
//  · TASK / APPROVAL 是固定词表，按键三语；from / to 里的 member:x 换成成员姓名；
//  · SLA 的 action 是指标代号，用 SLA 策略里的指标名；from / to 是计时状态。
// 不认识的代号原样显示 —— 看得到代号，比显示空白好查（后端加新动作时这里补文案即可）。
import { useQuery } from "@tanstack/react-query";
import { FlowTimeline } from "./flow-timeline";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { useFlowT } from "@/lib/flow/i18n";
import { useMembers } from "@/lib/flow/options";
import type { HistoryEntry, I18nText } from "@/lib/flow/types";

const HOT_TASK = new Set(["claim_timeout"]);
const HOT_TIMER = new Set(["BREACHED"]);
const APPROVAL_RESULT = new Set(["APPROVED", "REJECTED", "WITHDRAWN", "PENDING"]);

export function HistoryTimeline({ entries, loading }: { entries: HistoryEntry[] | undefined; loading?: boolean }) {
  const { t, tc, fmt, locale } = useFlowT("instances");
  const { t: ts } = useFlowT("sla");
  const members = useMembers();
  const policies = useQuery({ queryKey: [...FLOW_KEY, "sla-policies"], queryFn: flow.slaPolicies, staleTime: 60_000 });

  /** 词表取不到（返回的是 key 路径）就原样显示代号 */
  const or = (v: string, code: string) => (v.startsWith("flow.") ? code : v);
  const person = (v?: string) => {
    if (!v) return undefined;
    if (!v.startsWith("member:")) return v;
    const id = v.slice(7);
    const m = members.list.find((x) => x.ref === id);
    return m?.displayName || id;
  };
  const metricName = (code: string) => {
    if (code === "FIRST_RESPONSE" || code === "RESOLUTION") return ts(`metric.${code}`);
    for (const p of policies.data ?? []) {
      const m = p.metrics.find((x) => x.code === code);
      if (m) return (m.name as I18nText)[locale] || m.name.en || m.name.zh || code;
    }
    return code;
  };
  const state = (e: HistoryEntry, v?: string) => {
    if (!v) return undefined;
    if (e.entityKind === "TASK") return person(v) === v ? or(t(`taskState.${v as "OPEN"}`), v) : person(v);
    if (e.entityKind === "SLA") return or(t(`timer.${v as "RUNNING"}`), v);
    if (e.entityKind === "APPROVAL" && APPROVAL_RESULT.has(v)) return tc(`approvalStatus.${v as "PENDING"}`);
    return v;
  };

  const title = (e: HistoryEntry) => {
    switch (e.entityKind) {
      case "INSTANCE": {
        const known = e.action === "created" || e.action === "fields_updated";
        const name = known ? t(`action.instance.${e.action as "created"}`) : e.actionName || e.action;
        const from = e.fromName || e.from, to = e.toName || e.to;
        return fmt.join(name, from || to ? t("detail.fromTo", { from: from ?? "—", to: to ?? "—" }) : null);
      }
      case "TASK": {
        const name = or(t(`action.task.${e.action as "claimed"}`), e.action);
        const from = state(e, e.from), to = state(e, e.to);
        return fmt.join(name, from || to ? t("detail.fromTo", { from: from ?? "—", to: to ?? "—" }) : null);
      }
      case "APPROVAL": {
        const name = or(t(`action.approval.${e.action as "submitted"}`), e.action);
        // approve / reject 的 from 是步骤代号；completed 的 to 是审批结果
        const extra = e.action === "approve" || e.action === "reject" ? (e.from ? t("step", { code: e.from }) : null) : state(e, e.to);
        return fmt.join(name, extra);
      }
      case "SLA": {
        const from = state(e, e.from), to = state(e, e.to);
        return fmt.join(metricName(e.action), from || to ? t("detail.fromTo", { from: from ?? "—", to: to ?? "—" }) : null);
      }
      default:
        return fmt.join(e.actionName || e.action);
    }
  };
  const hot = (e: HistoryEntry) =>
    (e.entityKind === "TASK" && HOT_TASK.has(e.action)) || (e.entityKind === "SLA" && HOT_TIMER.has(e.to ?? "")) ||
    (e.entityKind === "APPROVAL" && (e.action === "reject" || e.to === "REJECTED"));
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
        hot: hot(e),
        title: fmt.join(t(`kind.${e.entityKind}`), title(e)),
        meta: fmt.join(fmt.when(e.atMs), who(e), e.entityKind !== "INSTANCE" ? e.entityNo : null),
        detail: e.reason ? t("detail.reason", { text: e.reason }) : undefined,
      }))}
    />
  );
}
