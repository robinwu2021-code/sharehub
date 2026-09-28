// 流程实例 · 历史 · 对账 · 统计的 mock（后端 O2-10 的形状）。
// 由审批 mock 派生：每张审批单 = 其审批类流程的一个实例（审批模板编译出的流程：草稿 → 审批中 → 已通过 / 已驳回，撤回回到草稿）。
// 所以在审批页里做的决定、撤回，这里的状态与历史立刻对得上。
import { notFound } from "@/lib/biz-error";
import { approvalSnapshots } from "./mock-approvals";
import { requirePerm } from "./mock-auth";
import type {
  ApprovalStatus, HistoryEntry, InstanceQuery, InstanceRow, Page, ProcessStats, ProcessStatsQuery, StatusCategory, SubjectHistory,
} from "./types";

const STATE: Record<ApprovalStatus, { code: string; name: string; cat: StatusCategory; open: boolean }> = {
  PENDING: { code: "REVIEWING", name: "审批中", cat: "IN_PROGRESS", open: true },
  APPROVED: { code: "APPROVED", name: "已通过", cat: "DONE", open: false },
  REJECTED: { code: "REJECTED", name: "已驳回", cat: "DONE", open: false },
  WITHDRAWN: { code: "DRAFT", name: "草稿", cat: "NEW", open: true },
};

type Snap = ReturnType<typeof approvalSnapshots>[number];
const instNo = (a: Snap) => `IN${a.approvalNo.slice(2)}`;

function row(a: Snap): InstanceRow {
  const st = STATE[a.status];
  const last = Math.max(a.requestedAtMs, a.finishedAtMs ?? 0, ...a.decisions.map((d) => d.atMs));
  return {
    instanceNo: instNo(a), process: a.process, processVersion: "a3f8c2d1", subject: a.subject,
    status: { code: st.code, name: st.name }, statusCategory: st.cat, open: st.open,
    createdBy: a.requestedBy, createdAtMs: a.requestedAtMs, updatedAtMs: last,
    ...(st.open ? {} : { closedAtMs: a.finishedAtMs }),
  };
}

/** 历史：词表与后端 37e78b21 一致（全小写；实例迁移代号是流程里定义的，名称放 actionName / fromName / toName） */
function history(a: Snap): HistoryEntry[] {
  const inst = instNo(a), tk = `TK${a.approvalNo.slice(2)}`;
  const e: HistoryEntry[] = [
    { entityKind: "INSTANCE", entityNo: inst, action: "created", to: "DRAFT", toName: "草稿", actor: a.requestedBy, atMs: a.requestedAtMs - 60_000 },
    { entityKind: "INSTANCE", entityNo: inst, action: "submit", actionName: "提交", from: "DRAFT", fromName: "草稿", to: "REVIEWING", toName: "审批中",
      actor: a.requestedBy, atMs: a.requestedAtMs },
    { entityKind: "APPROVAL", entityNo: a.approvalNo, action: "submitted", to: "PENDING", actor: a.requestedBy, atMs: a.requestedAtMs },
    { entityKind: "TASK", entityNo: tk, action: "created", to: "OPEN", actorText: "system", atMs: a.requestedAtMs },
  ];
  for (const d of a.decisions) {
    e.push({ entityKind: "APPROVAL", entityNo: a.approvalNo, action: "approve", from: d.stepName, actor: { ref: d.by, displayName: d.by }, atMs: d.atMs });
  }
  for (const r of a.rounds.filter((x) => x.status === "REJECTED")) {
    e.push({ entityKind: "APPROVAL", entityNo: a.approvalNo, action: "reject", reason: r.comment, actor: { ref: r.by, displayName: r.by }, atMs: r.atMs });
  }
  if (a.status !== "PENDING") {
    const at = a.finishedAtMs ?? a.requestedAtMs;
    e.push({ entityKind: "APPROVAL", entityNo: a.approvalNo, action: "completed", from: "PENDING", to: a.status, actorText: "system", atMs: at });
    e.push({ entityKind: "TASK", entityNo: tk, action: a.status === "WITHDRAWN" ? "cancelled" : "completed", from: "OPEN",
      to: a.status === "WITHDRAWN" ? "CANCELLED" : "DONE", actorText: "system", atMs: at });
    const to = STATE[a.status];
    e.push({ entityKind: "INSTANCE", entityNo: inst, action: a.status === "WITHDRAWN" ? "withdraw" : a.status === "APPROVED" ? "approved" : "rejected",
      actionName: a.status === "WITHDRAWN" ? "撤回" : a.status === "APPROVED" ? "审批通过" : "审批驳回",
      from: "REVIEWING", fromName: "审批中", to: to.code, toName: to.name, actorText: "system", atMs: at });
  }
  return e.sort((x, y) => x.atMs - y.atMs).map((x, i) => ({ ...x, seq: i + 1 }));
}

/** POST /workspaces/{ws}/instances/query */
export function queryInstances(q: InstanceQuery): Page<InstanceRow> {
  requirePerm("flow:approval:read");
  const list = approvalSnapshots()
    .filter((a) => !q.processCode || a.process.code === q.processCode)
    .filter((a) => !q.statusCategory?.length || q.statusCategory.includes(STATE[a.status].cat))
    .filter((a) => q.open === undefined || STATE[a.status].open === q.open)
    .filter((a) => !q.subjectType || a.subject.type === q.subjectType)
    .filter((a) => !q.subjectRef || a.subject.ref.toLowerCase().includes(q.subjectRef.trim().toLowerCase()))
    .filter((a) => !q.createdFromMs || a.requestedAtMs >= q.createdFromMs)
    .filter((a) => !q.createdToMs || a.requestedAtMs < q.createdToMs)
    .sort((x, y) => y.requestedAtMs - x.requestedAtMs)
    .map(row);
  const from = (q.page - 1) * q.size;
  return { list: list.slice(from, from + q.size), total: list.length };
}

/** GET /workspaces/{ws}/instances/{no}/history */
export function instanceHistory(no: string): HistoryEntry[] {
  requirePerm("flow:approval:read");
  const a = approvalSnapshots().find((x) => instNo(x) === no);
  if (!a) notFound("流程实例", "Process instance", no);
  return history(a);
}

/** GET /workspaces/{ws}/instances/{no}：列表行（详情页页头用；后端给的是列表 + history，这里合在一处取） */
export function instanceRow(no: string): InstanceRow {
  requirePerm("flow:approval:read");
  const a = approvalSnapshots().find((x) => instNo(x) === no);
  if (!a) notFound("流程实例", "Process instance", no);
  return row(a);
}

/** GET /workspaces/{ws}/subjects/{type}/{ref}/history：对账 */
export function subjectHistory(type: string, ref: string): SubjectHistory {
  requirePerm("flow:approval:read");
  const hits = approvalSnapshots().filter((a) => a.subject.type === type && a.subject.ref === ref);
  return {
    type, ref, total: hits.length, truncated: false,
    instances: hits.map((a) => ({
      instance: row(a),
      approvals: [{ approvalNo: a.approvalNo, status: a.status, round: a.round, requestedBy: a.requestedBy, requestedAtMs: a.requestedAtMs,
        ...(a.finishedAtMs ? { finishedAtMs: a.finishedAtMs } : {}) }],
      history: history(a),
    })),
  };
}

/** POST /workspaces/{ws}/process-stats（后端需 report:view；前端按 flow:process:manage 给入口） */
export function processStats(q: ProcessStatsQuery): ProcessStats[] {
  requirePerm("flow:process:manage");
  const all = approvalSnapshots()
    .filter((a) => !q.processCode || a.process.code === q.processCode)
    .filter((a) => !q.fromMs || a.requestedAtMs >= q.fromMs)
    .filter((a) => !q.toMs || a.requestedAtMs < q.toMs);
  const by = new Map<string, Snap[]>();
  for (const a of all) by.set(a.process.code, [...(by.get(a.process.code) ?? []), a]);
  return [...by.values()].map((xs) => {
    const closed = xs.filter((a) => !STATE[a.status].open && a.finishedAtMs);
    const count = <K extends string>(f: (a: Snap) => K) => xs.reduce<Partial<Record<K, number>>>((m, a) => ({ ...m, [f(a)]: (m[f(a)] ?? 0) + 1 }), {});
    return {
      process: xs[0].process, total: xs.length, open: xs.filter((a) => STATE[a.status].open).length,
      byCategory: count((a) => STATE[a.status].cat), approvals: count((a) => a.status),
      ...(closed.length ? { avgCycleMs: Math.round(closed.reduce((s, a) => s + (a.finishedAtMs! - a.requestedAtMs), 0) / closed.length) } : {}),
    };
  }).sort((x, y) => y.total - x.total);
}
