// 审批与审核队列的 mock（与 mock-db 同一纪律：真改库、规则在这一层强制）。
//
// 内建规则（PRD F2 / 原型 P2 的「规则提示」），这里全部强制，页面按钮只是体验：
//   1. 提交人不能审批自己提交的单            → 403 语义
//   2. 驳回必须填原因                        → 422 语义
//   3. 跨步互斥：前一步的决定人不能再审后一步  → 403 语义（流程开关）
//   4. 他人已处理后再提交会提示「请刷新」      → 409 语义（按 taskNo 判：任务已不是当前任务）
// 另：条件步（进入条件不满足就跳过）、历史轮次只追加不覆盖、撤回仅提交人且仅待审。
//
// 审核队列：领取后才能决定；领取超时（30 分钟）读时自动放回并留痕。
import { notFound } from "@/lib/biz-error";
import { ME } from "./mock-db";
import { requirePerm, requireBackendPerm, hasBackendPerm } from "./mock-auth";
import { ApiError } from "@/lib/api/error";
import { FlowValidationError } from "./errors";
import { useLocaleStore } from "@/lib/stores/locale";
import { translate } from "@/lib/i18n";
import { errText } from "./i18n";

/** 与服务端同码：409 冲突 · 403 规则拒绝 · 422 校验不过（带路径） */
const pickLang = (zh: string, en: string) => (useLocaleStore.getState().locale === "zh" ? zh : en);
const conflict = (zh: string, en: string): never => { throw new ApiError(409, pickLang(zh, en)); };
const denied = (zh: string, en: string): never => { throw new ApiError(403, pickLang(zh, en)); };
/** 403 + 错误键：消息按键取当前语言的文案（与 http.ts 对真服务的处理同一条路） */
const forbidKey = (key: string): never => {
  throw new ApiError(403, errText((k, p) => translate(useLocaleStore.getState().locale, k, p), key));
};
const invalid = (path: string, zh: string, en: string): never => {
  const m = pickLang(zh, en);
  throw new FlowValidationError(m, [{ path, error: m }]);
};
import type {
  ApprovalQuery, ApprovalRound, ApprovalRow, ApprovalStatus, ApprovalStep, ApprovalTemplate, ApprovalView, FieldError, I18nText, Named, Page,
  Priority, QueueCount, QueueTask, StartedApproval,
} from "./types";

const MIN = 60_000;
const HOUR = 60 * MIN;
export const CLAIM_TIMEOUT_MS = 30 * MIN;

const PRIO: Record<string, Priority> = {
  URGENT: { code: "URGENT", name: "紧急", ordinal: 0 },
  HIGH: { code: "HIGH", name: "高", ordinal: 1 },
  NORMAL: { code: "NORMAL", name: "普通", ordinal: 2 },
};

// ── 流程定义（简化：步骤 + 条件）────────────────────────────────────────────
interface StepDef { code: string; name: string; approver: string; condition?: { field: string; label: string } }
interface ProcessDef { code: string; name: string; steps: StepDef[]; crossStepExclusive: boolean }

const PROCESSES: Record<string, ProcessDef> = {
  inventory_count_audit: { code: "inventory_count_audit", name: "盘点差异复核", crossStepExclusive: false, steps: [
    { code: "inventory", name: "库存复核", approver: "持 device:inventory:audit" },
  ] },
  LOC_CONTRACT: { code: "LOC_CONTRACT", name: "进场合同", crossStepExclusive: true, steps: [
    { code: "ops", name: "运营审批", approver: "持 location:contract:audit" },
    { code: "fin", name: "财务会签", approver: "持 location:contract:cosign", condition: { field: "needsCosign", label: "条件步：分成 > 30% 触发" } },
  ] },
  FIN_WITHDRAWAL: { code: "FIN_WITHDRAWAL", name: "提现审批", crossStepExclusive: false, steps: [
    { code: "fin", name: "财务审批", approver: "持 finance:withdrawal:audit" },
  ] },
  AGT_APPLY: { code: "AGT_APPLY", name: "代理入驻", crossStepExclusive: false, steps: [
    { code: "ops", name: "运营审核", approver: "持 agent:apply:audit" },
  ] },
  CS_REFUND: { code: "CS_REFUND", name: "退款审批", crossStepExclusive: false, steps: [
    { code: "cs", name: "客服主管审批", approver: "持 cs:refund:audit" },
  ] },
};

export const PROCESS_OPTIONS: Named[] = Object.values(PROCESSES).map((p) => ({ code: p.code, name: p.name }));

interface ApprovalRec {
  approvalNo: string;
  processCode: string;
  subject: ApprovalRow["subject"];
  fields: Record<string, boolean>;
  facts: ApprovalView["facts"];
  evidence: ApprovalView["evidence"];
  status: ApprovalStatus;
  stepIdx: number;
  round: number;
  requestedBy: { ref: string; displayName: string };
  requestedAtMs: number;
  stepStartedAtMs: number;
  finishedAtMs?: number;
  /** 当前任务号：每次进入新步 / 新轮都会换，旧号提交即 409 */
  taskNo: string;
  decisions: { step: number; round: number; by: string; atMs: number }[];
  rounds: ApprovalRound[];
}

let approvals: ApprovalRec[] = [];
let taskSeq = 0;
const newTask = () => `TK${String(++taskSeq).padStart(6, "0")}`;

// ── 审核队列 ────────────────────────────────────────────────────────────
interface QueueRec extends Omit<QueueTask, "claimedBy" | "claimExpiresAtMs" | "evidenceSummary"> {
  evidence: string;
  claimedBy?: { ref: string; displayName: string };
  claimedAtMs?: number;
  done?: "APPROVED" | "REJECTED";
  log: string[];
}
let queueTasks: QueueRec[] = [];
const QUEUES: Named[] = [
  { code: "merchant", name: "商户资质" }, { code: "goods", name: "商品" }, { code: "factory", name: "验厂" },
];

export function resetApprovalDb(now: number = Date.now()) {
  taskSeq = 0;
  const sub = (type: string, ref: string, title: string) => ({ type, ref, display: { title } });
  const rec = (p: Omit<ApprovalRec, "taskNo" | "decisions" | "rounds" | "stepStartedAtMs"> & Partial<ApprovalRec>): ApprovalRec => ({
    taskNo: newTask(), decisions: [], rounds: [], stepStartedAtMs: p.requestedAtMs, ...p,
  });
  approvals = [
    rec({ approvalNo: "AP2609270001", processCode: "LOC_CONTRACT", subject: sub("contract", "CT0042", "CT0042 万达广场 · 分成 35%"),
      fields: { needsCosign: true }, status: "PENDING", stepIdx: 0, round: 2,
      requestedBy: { ref: "ali", displayName: "Ali（BD）" }, requestedAtMs: now - 4.1 * HOUR,
      facts: [
        { label: "合同", value: "CT0042 · 万达广场进场合同" }, { label: "场地方", value: "万达广场（VN0311）" },
        { label: "分成比例", value: "35%", hint: "触发财务会签阈值 30%" }, { label: "期限", value: "2026-10-01 起 12 个月" },
        { label: "进场费", value: "AED 12,000" }, { label: "保底", value: "无" },
      ],
      evidence: [{ name: "场地方确认函.pdf" }, { name: "现场照片", count: 3 }],
      rounds: [
        { round: 1, status: "REJECTED", atMs: now - 27 * HOUR, by: "运营 Omar", comment: "分成比例与场地方口头约定不符，请核实后重提" },
        { round: 2, status: "IN_PROGRESS", atMs: now - 4.1 * HOUR, by: "Ali", comment: "修改分成比例后重新提交" },
      ] }),
    rec({ approvalNo: "AP2609270008", processCode: "FIN_WITHDRAWAL", subject: sub("withdrawal", "WD001392", "WD001392 · AG0007 · AED 4,200"),
      fields: {}, status: "PENDING", stepIdx: 0, round: 1, requestedBy: { ref: "system", displayName: "系统（代理发起）" }, requestedAtMs: now - 2.5 * HOUR,
      facts: [{ label: "提现单", value: "WD001392" }, { label: "代理", value: "AG0007 迪拜城运" }, { label: "金额", value: "AED 4,200" }],
      evidence: [{ name: "收款账户核验" }],
      rounds: [{ round: 1, status: "IN_PROGRESS", atMs: now - 2.5 * HOUR, by: "系统" }] }),
    rec({ approvalNo: "AP2609260031", processCode: "AGT_APPLY", subject: sub("apply", "APL0917", "APL0917 · Deira 五金店"),
      fields: {}, status: "APPROVED", stepIdx: 1, round: 1, requestedBy: { ref: "yusuf", displayName: "Yusuf（BD）" },
      requestedAtMs: now - 22 * HOUR, finishedAtMs: now - 18.8 * HOUR,
      facts: [{ label: "申请", value: "APL0917" }, { label: "门店", value: "Deira 五金店" }], evidence: [{ name: "营业执照" }],
      rounds: [{ round: 1, status: "APPROVED", atMs: now - 18.8 * HOUR, by: "运营 Omar" }] }),
    rec({ approvalNo: "AP2609260019", processCode: "LOC_CONTRACT", subject: sub("contract", "CT0041", "CT0041 · Marina Mall"),
      fields: { needsCosign: false }, status: "REJECTED", stepIdx: 0, round: 1, requestedBy: { ref: "ali", displayName: "Ali（BD）" },
      requestedAtMs: now - 27.5 * HOUR, finishedAtMs: now - 27.2 * HOUR,
      facts: [{ label: "合同", value: "CT0041 · Marina Mall" }], evidence: [],
      rounds: [{ round: 1, status: "REJECTED", atMs: now - 27.2 * HOUR, by: "运营 Omar", comment: "缺场地方确认函" }] }),
    rec({ approvalNo: "AP2609250090", processCode: "CS_REFUND", subject: sub("refund", "RF260925", "RF260925 · 订单 OD…8871"),
      fields: {}, status: "WITHDRAWN", stepIdx: 0, round: 1, requestedBy: { ref: "lina", displayName: "Lina（客服）" },
      requestedAtMs: now - 40 * HOUR, finishedAtMs: now - 39 * HOUR,
      facts: [{ label: "退款单", value: "RF260925" }], evidence: [],
      rounds: [{ round: 1, status: "WITHDRAWN", atMs: now - 39 * HOUR, by: "Lina", comment: "用户已线下协商" }] }),
    // 提交人是我的一张（用来演示「不能审自己的单」与撤回）
    rec({ approvalNo: "AP2609270015", processCode: "CS_REFUND", subject: sub("refund", "RF260927", "RF260927 · 订单 OD…9102"),
      fields: {}, status: "PENDING", stepIdx: 0, round: 1, requestedBy: { ref: ME.ref, displayName: ME.displayName },
      requestedAtMs: now - 50 * MIN, facts: [{ label: "退款单", value: "RF260927" }, { label: "金额", value: "AED 36" }], evidence: [],
      rounds: [{ round: 1, status: "IN_PROGRESS", atMs: now - 50 * MIN, by: ME.displayName }] }),
  ];
  // 填充：让列表与筛选有东西可看
  const who = [{ ref: "ali", displayName: "Ali（BD）" }, { ref: "yusuf", displayName: "Yusuf（BD）" }, { ref: "lina", displayName: "Lina（客服）" }];
  const codes = ["LOC_CONTRACT", "FIN_WITHDRAWAL", "AGT_APPLY", "CS_REFUND"];
  for (let i = 0; i < 14; i++) {
    const code = codes[i % codes.length];
    const done = i % 3 !== 0;
    const at = now - (5 + i * 7) * HOUR;
    approvals.push(rec({
      approvalNo: `AP26092${String(40 + i).padStart(4, "0")}`, processCode: code,
      subject: sub("biz", `BZ${1000 + i}`, `${PROCESSES[code].name} · BZ${1000 + i}`), fields: { needsCosign: false },
      status: done ? (i % 2 ? "APPROVED" : "REJECTED") : "PENDING", stepIdx: 0, round: 1,
      requestedBy: who[i % who.length], requestedAtMs: at, ...(done ? { finishedAtMs: at + (1 + i % 4) * HOUR } : {}),
      facts: [{ label: "业务单", value: `BZ${1000 + i}` }], evidence: [],
      rounds: [{ round: 1, status: done ? (i % 2 ? "APPROVED" : "REJECTED") : "IN_PROGRESS", atMs: at, by: who[i % who.length].displayName }],
    }));
  }

  const task = (queue: string, ref: string, title: string, prio: string, age: number, evidence: string, claimed?: { by: { ref: string; displayName: string }; ago: number }): QueueRec => ({
    taskNo: newTask(), queue: QUEUES.find((q) => q.code === queue)!, subject: sub("merchant-apply", ref, title), priority: PRIO[prio],
    createdAtMs: now - age, evidence, log: [], ...(claimed ? { claimedBy: claimed.by, claimedAtMs: now - claimed.ago } : {}),
  });
  queueTasks = [
    task("merchant", "MA0501", "商户主体申请 · 迪拜 Marina 果汁店", "HIGH", 2.1 * HOUR, "营业执照 · 店面照片 ×4", { by: { ref: ME.ref, displayName: ME.displayName }, ago: 5.5 * MIN }),
    task("merchant", "MA0498", "商户主体申请 · Deira 五金店", "HIGH", 3.4 * HOUR, "营业执照"),
    task("merchant", "MA0502", "商户主体申请 · Yas Island 咖啡车", "NORMAL", 1.2 * HOUR, "营业执照 · 车辆照片 ×2", { by: { ref: "sara", displayName: "Sara" }, ago: 10 * MIN }),
    task("merchant", "MA0505", "商户主体申请 · Corniche 便利店", "NORMAL", 50 * MIN, "营业执照"),
    task("merchant", "MA0490", "商户主体申请 · JLT 花店", "NORMAL", 5 * HOUR, "营业执照", { by: { ref: "omar", displayName: "Omar" }, ago: 45 * MIN }),
    ...Array.from({ length: 9 }, (_, i) => task("merchant", `MA06${10 + i}`, `商户主体申请 · 门店 ${i + 1}`, i % 3 ? "NORMAL" : "HIGH", (20 + i * 15) * MIN, "营业执照")),
    ...Array.from({ length: 34 }, (_, i) => task("goods", `GD${700 + i}`, `商品上架 · SKU ${700 + i}`, "NORMAL", (10 + i * 7) * MIN, "商品图 ×3")),
    ...Array.from({ length: 3 }, (_, i) => task("factory", `FC${30 + i}`, `验厂报告 · 工厂 ${i + 1}`, "HIGH", (60 + i * 40) * MIN, "验厂报告.pdf")),
  ];
}
resetApprovalDb();

// ── 审批：读 ─────────────────────────────────────────────────────────────
function def(r: ApprovalRec) { return PROCESSES[r.processCode]; }

function row(r: ApprovalRec): ApprovalRow {
  const d = def(r);
  const step = d.steps[r.stepIdx];
  return {
    approvalNo: r.approvalNo, process: { code: d.code, name: d.name }, subject: r.subject, status: r.status,
    ...(r.status === "PENDING" && step ? { currentStep: { code: step.code, name: step.name } } : {}),
    round: r.round, requestedBy: r.requestedBy, requestedAtMs: r.requestedAtMs,
    ...(r.finishedAtMs ? { finishedAtMs: r.finishedAtMs } : {}),
    ...(r.status === "PENDING" ? { stepStartedAtMs: r.stepStartedAtMs, stepDueAtMs: r.stepStartedAtMs + 8 * HOUR } : {}),
  };
}

export function queryApprovals(q: ApprovalQuery): Page<ApprovalRow> {
  requirePerm("flow:approval:read");
  const k = q.keyword?.trim().toLowerCase();
  const list = approvals
    .filter((r) => !q.status?.length || q.status.includes(r.status))
    .filter((r) => !q.processCode || r.processCode === q.processCode)
    .filter((r) => !q.requestedBy || r.requestedBy.ref === q.requestedBy)
    .filter((r) => q.fromMs == null || r.requestedAtMs >= q.fromMs)
    .filter((r) => !k || [r.approvalNo, r.subject.ref, r.subject.display.title].join(" ").toLowerCase().includes(k))
    .sort((a, b) => b.requestedAtMs - a.requestedAtMs)
    .map(row);
  const from = (q.page - 1) * q.size;
  return { list: list.slice(from, from + q.size), total: list.length };
}

export function countApprovals(): Record<ApprovalStatus, number> {
  const c = { PENDING: 0, APPROVED: 0, REJECTED: 0, WITHDRAWN: 0 };
  for (const r of approvals) c[r.status]++;
  return c;
}

export const REQUESTER_OPTIONS: Named[] = [
  { code: "ali", name: "Ali（BD）" }, { code: "yusuf", name: "Yusuf（BD）" }, { code: "lina", name: "Lina（客服）" },
  { code: "system", name: "系统（代理发起）" }, { code: ME.ref, name: `${ME.displayName}（我）` },
];

function rec(no: string): ApprovalRec {
  const r = approvals.find((x) => x.approvalNo === no);
  if (!r) notFound("审批单", "Approval", no);
  return r;
}

/** 为什么我不能决定这张单（null = 能） */
function whyNotDecide(r: ApprovalRec): string | null {
  // 与服务端同形：whyNot 是错误键（与 403 的 error 同键），文案由前端按键做三语（flow.common.err.*）
  if (r.status !== "PENDING") return null;
  if (r.requestedBy.ref === ME.ref) return "opskit.approval.self_review";
  if (def(r).crossStepExclusive && r.decisions.some((d) => d.round === r.round && d.by === ME.ref)) {
    return "opskit.approval.cross_step";
  }
  return null;
}

export function getApproval(no: string): ApprovalView {
  requirePerm("flow:approval:read");
  const r = rec(no);
  const d = def(r);
  const steps: ApprovalStep[] = d.steps.map((s, i) => {
    const skipped = !!s.condition && !r.fields[s.condition.field];
    const decided = r.decisions.find((x) => x.step === i && x.round === r.round);
    const state: ApprovalStep["state"] = skipped ? "SKIPPED"
      : r.status === "APPROVED" || i < r.stepIdx ? "DONE"
      : r.status === "PENDING" && i === r.stepIdx ? "CURRENT" : "TODO";
    return { code: s.code, name: s.name, approverText: s.approver, ...(s.condition ? { conditionText: s.condition.label } : {}), state,
      ...(decided ? { decidedBy: decided.by, decidedAtMs: decided.atMs } : {}) };
  });
  const why = whyNotDecide(r);
  // 深拷贝：浅拷贝时轮次对象与 mock 内部同一引用，写操作原地改它会「穿透」进页面缓存里的旧数据
  // （真服务是 JSON 不会这样，但会让界面在重新取数前就显示一半新状态，端到端场景 S2-2 抓到过）
  return structuredClone({
    ...row(r), facts: r.facts, steps, rounds: [...r.rounds].sort((a, b) => a.round - b.round), evidence: r.evidence,
    mine: { ...(r.status === "PENDING" ? { taskNo: r.taskNo } : {}), canDecide: r.status === "PENDING" && !why && (r.processCode !== "inventory_count_audit" || hasBackendPerm("device:inventory:audit")), ...(why ? { whyNot: why } : {}),
      canWithdraw: r.status === "PENDING" && r.requestedBy.ref === ME.ref },
    rules: { crossStepExclusive: d.crossStepExclusive },
  });
}

// ── 审批：写 ─────────────────────────────────────────────────────────────
/** POST /approvals/{no}/decide {taskNo, decision, reason?}：固定顺序 409（任务号过期）→ 403（规则）→ 422（原因） */
export function decide(no: string, taskNo: string, decision: "APPROVE" | "REJECT", reason: string | undefined, now: number = Date.now()): ApprovalView {
  requirePerm("flow:approval:decide");
  const r = rec(no);
  if (r.status !== "PENDING" || r.taskNo !== taskNo) {
    conflict("这张单已被他人处理，请刷新后再看", "This approval was already handled by someone else — refresh and try again");
  }
  if (r.processCode === "inventory_count_audit") requireBackendPerm("device:inventory:audit");
  const why = whyNotDecide(r);
  if (why) forbidKey(why);
  if (decision === "REJECT" && !reason?.trim()) {
    invalid("reason", "驳回必须填写原因 —— 只说「已驳回」，提交人无从改正后重提", "A reason is required to reject — otherwise the requester cannot fix and resubmit");
  }
  r.decisions.push({ step: r.stepIdx, round: r.round, by: ME.ref, atMs: now });
  const cur = r.rounds.find((x) => x.round === r.round);
  if (decision === "REJECT") {
    r.status = "REJECTED"; r.finishedAtMs = now;
    if (cur) Object.assign(cur, { status: "REJECTED", atMs: now, by: ME.displayName, comment: reason!.trim() });
    deliverBusinessDecision(r);
    return getApproval(no);
  }
  // 通过：找下一个进入条件满足的步
  let next = r.stepIdx + 1;
  while (next < def(r).steps.length && def(r).steps[next].condition && !r.fields[def(r).steps[next].condition!.field]) next++;
  if (next >= def(r).steps.length) {
    r.status = "APPROVED"; r.finishedAtMs = now; r.stepIdx = def(r).steps.length;
    if (cur) Object.assign(cur, { status: "APPROVED", atMs: now, by: ME.displayName, comment: reason?.trim() || undefined });
  } else {
    r.stepIdx = next; r.stepStartedAtMs = now; r.taskNo = newTask();
  }
  deliverBusinessDecision(r);
  return getApproval(no);
}

/** POST /approvals/{no}/withdraw：仅提交人、仅待审 */
export function withdraw(no: string, now: number = Date.now()): ApprovalView {
  const r = rec(no);
  if (r.requestedBy.ref !== ME.ref) denied("只有提交人可以撤回", "Only the requester can withdraw");
  if (r.status !== "PENDING") conflict("只能撤回待审的单", "Only pending approvals can be withdrawn");
  r.status = "WITHDRAWN"; r.finishedAtMs = now; r.taskNo = newTask();
  const cur = r.rounds.find((x) => x.round === r.round);
  if (cur) Object.assign(cur, { status: "WITHDRAWN", atMs: now, by: ME.displayName });
  deliverBusinessDecision(r);
  return getApproval(no);
}

// ── 审核队列 ────────────────────────────────────────────────────────────
/** 读时放回超时的领取（留痕） */
function sweep(now: number) {
  for (const t of queueTasks) {
    if (t.claimedAtMs != null && !t.done && now - t.claimedAtMs > CLAIM_TIMEOUT_MS) {
      t.log.push(`领取超时，自动放回（原领取人 ${t.claimedBy?.displayName}）`);
      t.claimedBy = undefined; t.claimedAtMs = undefined;
    }
  }
}

const qview = (t: QueueRec): QueueTask => ({
  taskNo: t.taskNo, queue: t.queue, subject: t.subject, priority: t.priority, createdAtMs: t.createdAtMs,
  ...(t.evidence ? { evidenceSummary: t.evidence } : {}),
  ...(t.claimedBy ? { claimedBy: t.claimedBy, claimExpiresAtMs: t.claimedAtMs! + CLAIM_TIMEOUT_MS } : {}),
});

/** POST /workspaces/{ws}/tasks/queue-counts：每个队列组的待领数与我已领数 */
export function queueCounts(now: number = Date.now()): QueueCount[] {
  sweep(now);
  const open = queueTasks.filter((t) => !t.done);
  return QUEUES.map((queue) => ({
    queue,
    unclaimed: open.filter((t) => t.queue.code === queue.code && !t.claimedBy).length,
    claimedByMe: open.filter((t) => t.queue.code === queue.code && t.claimedBy?.ref === ME.ref).length,
  }));
}

/** 队列里未办结的任务：按优先级 → 等待时长排序 */
export function queueTasksOf(queue: string, page: number, size: number, now: number = Date.now()): Page<QueueTask> {
  sweep(now);
  const list = queueTasks
    .filter((t) => t.queue.code === queue && !t.done && t.claimedBy?.ref !== ME.ref)
    .sort((a, b) => (a.priority?.ordinal ?? 9) - (b.priority?.ordinal ?? 9) || a.createdAtMs - b.createdAtMs)
    .map(qview);
  const from = (page - 1) * size;
  return { list: list.slice(from, from + size), total: list.length };
}

export function myClaimed(now: number = Date.now()): QueueTask[] {
  sweep(now);
  return queueTasks.filter((t) => !t.done && t.claimedBy?.ref === ME.ref).map(qview);
}

function qrec(taskNo: string): QueueRec {
  const t = queueTasks.find((x) => x.taskNo === taskNo);
  if (!t) notFound("任务", "Task", taskNo);
  return t;
}

/** POST /tasks/{no}/claim：已被他人领取 → 409 */
export function claimTask(taskNo: string, now: number = Date.now()): QueueTask {
  requirePerm("flow:queue:claim");
  sweep(now);
  const t = qrec(taskNo);
  if (t.done) conflict("这项已经办结", "This task is already done");
  if (t.claimedBy && t.claimedBy.ref !== ME.ref) conflict(`已被 ${t.claimedBy.displayName} 领取，刷新后再看`, `Already claimed by ${t.claimedBy.displayName} — refresh`);
  t.claimedBy = { ref: ME.ref, displayName: ME.displayName }; t.claimedAtMs = now;
  return qview(t);
}

/** POST /tasks/{no}/release：只能放回自己领的 */
export function releaseTask(taskNo: string): QueueTask {
  requirePerm("flow:queue:claim");
  const t = qrec(taskNo);
  if (t.claimedBy?.ref !== ME.ref) denied("只能放回自己领取的任务", "You can only release tasks you claimed");
  t.claimedBy = undefined; t.claimedAtMs = undefined;
  t.log.push("主动放回");
  return qview(t);
}

/** POST /tasks/{no}/complete：领取后才能决定；驳回必填原因 */
export function completeTask(taskNo: string, decision: "APPROVE" | "REJECT", reason: string | undefined, now: number = Date.now()): void {
  requirePerm("flow:queue:claim");
  sweep(now);
  const t = qrec(taskNo);
  if (t.claimedBy?.ref !== ME.ref) conflict("领取后才能决定（可能已超时自动放回，刷新后再领）", "Claim it before deciding (it may have timed out and been released — refresh)");
  if (decision === "REJECT" && !reason?.trim()) invalid("reason", "驳回必须填写原因", "A reason is required to reject");
  t.done = decision === "APPROVE" ? "APPROVED" : "REJECTED";
}

/** 某流程的在途（待审）单数 —— 流程配置页的「在途单」列 */
/**
 * 运营台「发起审批」（POST /instances，按审批模板）：与 OpsKit 同口径 —— 发起即进入审批、带审批单号；
 * 发起表单逐字段校验，一次给全，路径 fields.<代号>。mock 按模板动态登记一个流程（步骤名 / 审批人描述）。
 */
export function startFromTemplate(tpl: ApprovalTemplate, values: Record<string, unknown>, now: number = Date.now()): StartedApproval {
  requirePerm("flow:approval:submit");
  const name = (t: I18nText) => t.zh || t.en || t.ar || "";
  const errors: FieldError[] = [];
  const facts: ApprovalView["facts"] = [];
  for (const f of tpl.fields ?? []) {
    const v = values[f.code];
    const empty = v == null || v === "";
    const path = `fields.${f.code}`;
    if (empty) {
      if (f.required) errors.push({ path, error: pickLang("必填", "Required") });
      continue;
    }
    if (f.type === "NUMBER" && typeof v !== "number") errors.push({ path, error: pickLang("应为数字", "Should be a number") });
    else if (f.type === "DATE" && !/^\d{4}-\d{2}-\d{2}$/.test(String(v))) errors.push({ path, error: pickLang("日期格式应为 yyyy-MM-dd", "Date must be yyyy-MM-dd") });
    else if (f.type === "SELECT" && !(f.options ?? []).some((o) => o.code === v)) errors.push({ path, error: pickLang("取值不在允许范围内", "Not an allowed value") });
    else if ((f.type === "TEXT" && String(v).length > 200) || String(v).length > 2000) errors.push({ path, error: pickLang("太长了", "Too long") });
    else {
      const shown = f.type === "SELECT" ? name((f.options ?? []).find((o) => o.code === v)!.name) : String(v);
      facts.push({ label: name(f.name), value: shown });
    }
  }
  for (const k of Object.keys(values)) {
    if (!(tpl.fields ?? []).some((f) => f.code === k)) errors.push({ path: `fields.${k}`, error: pickLang("模板没有这个字段", "Unknown field") });
  }
  if (errors.length) throw new FlowValidationError(pickLang(`发起被拒：有 ${errors.length} 处需要修改`, `Rejected: ${errors.length} field(s) to fix`), errors);

  PROCESSES[tpl.code] ??= {
    code: tpl.code, name: name(tpl.name), crossStepExclusive: tpl.rules.crossStepExclusive,
    steps: tpl.steps.map((st, i) => ({ code: st.code || `s${i + 1}`, name: name(st.name),
      approver: `${st.approver.kind} ${[st.approver.value].flat().join(" / ")}` })),
  };
  const d = new Date(now);
  const day = `${String(d.getFullYear()).slice(2)}${String(d.getMonth() + 1).padStart(2, "0")}${String(d.getDate()).padStart(2, "0")}`;
  let n = 1;
  while (approvals.some((a) => a.approvalNo === `AP${day}${String(800 + n)}`)) n++;
  const approvalNo = `AP${day}${String(800 + n)}`;
  const first = facts[0]?.value;
  approvals.push({
    approvalNo, processCode: tpl.code,
    subject: { type: "manual", ref: approvalNo, display: { title: first ? `${name(tpl.name)} · ${first}` : name(tpl.name) } },
    fields: {}, facts, evidence: [], status: "PENDING", stepIdx: 0, round: 1,
    requestedBy: { ref: ME.ref, displayName: ME.displayName }, requestedAtMs: now, stepStartedAtMs: now,
    taskNo: newTask(), decisions: [], rounds: [{ round: 1, status: "IN_PROGRESS", atMs: now, by: ME.displayName }],
  });
  return { instanceNo: `PI${approvalNo.slice(2)}`, approvalNo };
}

export function pendingCountOf(processCode: string): number {
  return approvals.filter((r) => r.processCode === processCode && r.status === "PENDING").length;
}

/** 只读快照（流程实例 / 历史 / 对账 / 统计的 mock 从这里派生，与审批页同一份数据；深拷贝，改不到内部） */
export function approvalSnapshots() {
  return approvals.map((r) => structuredClone({
    approvalNo: r.approvalNo, process: { code: def(r).code, name: def(r).name }, subject: r.subject, status: r.status,
    round: r.round, requestedBy: r.requestedBy, requestedAtMs: r.requestedAtMs, finishedAtMs: r.finishedAtMs,
    currentStep: r.status === "PENDING" ? def(r).steps[r.stepIdx]?.name : undefined,
    decisions: r.decisions.map((d) => ({ ...d, stepName: def(r).steps[d.step]?.name ?? "" })), rounds: r.rounds,
  }));
}


// Host business submissions enter the same approval store; callbacks only apply the already recorded decision.
const businessCallbacks = new Map<string, (decision: "APPROVED" | "REJECTED" | "WITHDRAWN") => void>();
let businessSequence = 0;
function deliverBusinessDecision(r: ApprovalRec) {
  if (r.status !== "PENDING") businessCallbacks.get(r.approvalNo)?.(r.status);
}
export function startBusinessApproval(subject: ApprovalRow["subject"], facts: ApprovalView["facts"],
  callback: (decision: "APPROVED" | "REJECTED" | "WITHDRAWN") => void): string {
  const approvalNo = `APINV${Date.now()}${++businessSequence}`;
  const now = Date.now();
  approvals.push({ approvalNo, processCode: "inventory_count_audit", subject, fields: {}, facts, evidence: [],
    status: "PENDING", stepIdx: 0, round: 1, requestedBy: { ref: ME.ref, displayName: ME.displayName },
    requestedAtMs: now, stepStartedAtMs: now, taskNo: newTask(), decisions: [],
    rounds: [{ round: 1, status: "IN_PROGRESS", atMs: now, by: ME.displayName }],
  });
  businessCallbacks.set(approvalNo, callback);
  return approvalNo;
}
