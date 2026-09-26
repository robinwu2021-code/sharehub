// 代理清退（F3）与运维月度考核（F5）的 mock，对齐后端 AgentExitServiceImpl / AgentOpsAssessmentServiceImpl。
//
// 三条与后端逐条一致的规则（mock 放行而后端拒绝，页面在 mock 下看着通、切后端当场崩）：
//  1. 发起即停用：代理转 SUSPENDED，名下未完结工单改派平台（F2），同一代理至多一张在途清退单；
//  2. 每一步推进前**重算当前步门禁**，未全过拒绝 —— 前端按钮置灰只是体验，不是防线；
//  3. 最后一步关闭：停用该代理全部登录账号 + 归档代理。
//
// 门禁计数直接数各域 mock 表（同后端「直接 SQL 读」的理由：不为几个 COUNT 让域之间互相依赖）。
// fixHref 照抄后端给的**原样字符串**（包括它指向的并不存在的路由），前端在渲染前统一改写 ——
// 这样 mock 下走的也是同一条改写逻辑，而不是一条只在真后端才被执行到的分支。
import type {
  AgentExit, AgentExitStatus, AgentOpsAssessment, Checklist, ChecklistItem, WorkOrderStatus, WithdrawalStatus,
} from "../../types";
import { AGENT_EXIT_TRANSITIONS, agentExitActionOf } from "../../types";
import { agents, agentAccounts, siteAgents, listSiteAgents } from "./agent";
import { sites } from "./location";
import { cabinets } from "./device";
import { workOrders } from "./workorder";
import { shareRecords, settlements, withdrawals } from "./finance";
import { nextNo } from "./helpers";
import { fail, notFound } from "@/lib/biz-error";

export const agentExits: AgentExit[] = [];

const OPEN_EXIT: AgentExitStatus[] = ["RECLAIMING", "SETTLING", "CLOSING"];
/** 工单「未完结」口径，与后端 gate / reassignFromAgent 的 status IN (...) 相同。 */
const OPEN_WO: WorkOrderStatus[] = ["CREATED", "DISPATCHED", "ACCEPTED", "PROCESSING"];
/** 提现「在途」口径（后端 gate：status IN ('APPLY','AUDIT','PAYING')）。 */
const IN_FLIGHT_WD: WithdrawalStatus[] = ["APPLY", "AUDIT", "PAYING"];

const requireExit = (exitNo: string) => agentExits.find((e) => e.exitNo === exitNo) ?? notFound("清退单", "Agent exit", exitNo);

/**
 * 代理停用的联动（F2）：名下未完结工单改派平台运维。
 *
 * 后端由 `AgentSuspendedListener` 监听 `AgentStatusChangedEvent` 执行；mock 里在状态落地处直接调。
 * 返回改派张数，供调用方提示。
 */
export function onAgentSuspended(agentNo: string): number {
  let n = 0;
  for (const w of workOrders) {
    if (w.assigneeName === agentNo && OPEN_WO.includes(w.status)) {
      w.assigneeName = "平台运维";
      n++;
    }
  }
  return n;
}

export function startAgentExit(agentNo: string, reason: string, operator = "admin"): AgentExit {
  if (!reason?.trim()) fail("请填写清退原因——它会写进停用记录，事后追溯靠它", "Reason is required", "السبب مطلوب");
  const a = agents.find((x) => x.agentNo === agentNo) ?? notFound("代理商", "Agent", agentNo);
  if (agentExits.some((e) => e.agentNo === agentNo && OPEN_EXIT.includes(e.status))) {
    fail(`代理 ${agentNo} 已有一张在途清退单，不能重复发起`, `Agent ${agentNo} already has an exit in progress`);
  }
  const e: AgentExit = {
    exitNo: nextNo("AX", agentExits, 1001, "exitNo"),
    agentNo, status: "RECLAIMING", reason: reason.trim(),
    startedBy: operator, startedAt: new Date().toISOString(),
    reclaimedAt: null, settledAt: null, closedAt: null, closedBy: null,
  };
  agentExits.unshift(e);
  // 发起即停用：不再派新单、不能新划拨、提现冻结（结清步例外）、名下未完结工单改派平台
  if (a.status !== "SUSPENDED") {
    a.status = "SUSPENDED";
    onAgentSuspended(agentNo);
  }
  return e;
}

export const getAgentExit = (exitNo: string): AgentExit => requireExit(exitNo);

/** 该代理当前在途的清退单；没有则 `null`（「没有在清退」是正常状态，不是错误）。 */
export const getAgentOpenExit = (agentNo: string): AgentExit | null =>
  agentExits.find((e) => e.agentNo === agentNo && OPEN_EXIT.includes(e.status)) ?? null;

function item(key: string, label: string, n: number, what: string, href: string): ChecklistItem {
  return { key, label, passed: n === 0, detail: n === 0 ? "已完成" : `还有 ${n} ${what}`, fixHref: href };
}

function gateOf(e: AgentExit): Checklist {
  const ag = e.agentNo;
  const of = (items: ChecklistItem[]): Checklist => ({ allPassed: items.every((i) => i.passed), items });
  switch (e.status) {
    case "RECLAIMING": {
      listSiteAgents("");   // 站点责任的种子是惰性铺的，先触发一次
      const now = Date.now();
      const siteN = sites.filter((s) => s.agentNo === ag && s.status !== "CLOSED").length;
      const roleN = siteAgents.filter((r) => r.agentNo === ag
        && (!r.effectiveTo || new Date(r.effectiveTo).getTime() >= now)).length;
      const cabN = cabinets.filter((c) => c.agentNo === ag && !c.archivedAt && c.status !== "RETIRED").length;
      const woN = workOrders.filter((w) => w.assigneeName === ag && OPEN_WO.includes(w.status)).length;
      return of([
        item("SITES", "站点已收回", siteN, "个站点仍归属该代理", `/agents/${ag}?tab=assign`),
        item("SITE_ROLES", "站点责任已解除", roleN, "条站点责任（运维 / 拓展）仍生效", `/agents/${ag}?tab=sites`),
        item("CABINETS", "设备已收回", cabN, "台机柜仍归属该代理", `/agents/${ag}?tab=assign`),
        item("WORK_ORDERS", "工单已转出", woN, "张未完结工单仍在它手上", `/work-orders?assignee=${ag}`),
      ]);
    }
    case "SETTLING": {
      const shareN = shareRecords.filter((r) => r.dimension === "AGENT" && r.payeeNo === ag && r.status === "PENDING").length;
      const stlN = settlements.filter((s) => s.payeeNo === ag && s.status !== "PAID").length;
      const wdN = withdrawals.filter((w) => w.payeeNo === ag && IN_FLIGHT_WD.includes(w.status)).length;
      return of([
        item("SHARES", "分润已出账", shareN, "条分润还没进结算单", `/finance/shares?payeeNo=${ag}`),
        item("SETTLEMENTS", "结算单已付清", stlN, "张结算单未付清", `/finance/settlements?payeeNo=${ag}`),
        item("WITHDRAWALS", "提现已办结", wdN, "笔提现在途", `/finance/withdrawals?payeeNo=${ag}`),
      ]);
    }
    case "CLOSING":
      return of([{ key: "READY", label: "可以关闭", passed: true, detail: "关闭将停用全部登录账号并归档代理", fixHref: null }]);
    case "CLOSED":
      return of([{ key: "CLOSED", label: "已清退", passed: true, detail: `清退完成于 ${e.closedAt}`, fixHref: null }]);
  }
}

export const agentExitGate = (exitNo: string): Checklist => gateOf(requireExit(exitNo));

/** 门禁全过 → 推进一步；最后一步关闭账号并归档代理。 */
export function advanceAgentExit(exitNo: string, operator = "admin"): AgentExit {
  const e = requireExit(exitNo);
  const action = agentExitActionOf(e.status);
  if (!action) fail(`清退单 ${exitNo} 已完成，没有下一步`, `Exit ${exitNo} is already closed`);
  const g = gateOf(e);
  if (!g.allPassed) {
    const keys = g.items.filter((i) => !i.passed).map((i) => i.label).join("、");
    fail(`当前步门禁未全部通过：${keys}——逐项处理后再推进`, `Gate not passed: ${g.items.filter((i) => !i.passed).map((i) => i.key).join(", ")}`);
  }
  const now = new Date().toISOString();
  e.status = AGENT_EXIT_TRANSITIONS[action].to;
  if (action === "reclaimed") e.reclaimedAt = now;
  else if (action === "settled") e.settledAt = now;
  else {
    e.closedAt = now;
    e.closedBy = operator;
    for (const acc of agentAccounts) if (acc.agentNo === e.agentNo) acc.status = "DISABLED";
    const a = agents.find((x) => x.agentNo === e.agentNo);
    if (a) { a.status = "SUSPENDED"; a.archivedAt = now; }
  }
  return e;
}

/** 该代理当前处于「结清中」—— 后端 `AgentBrief.settlingExit`：这一步允许提现，否则钱永远结不清。 */
export const isSettlingExit = (agentNo: string) =>
  agentExits.some((e) => e.agentNo === agentNo && e.status === "SETTLING");

// —— 运维月度考核（F5）——

export const agentOpsAssessments: AgentOpsAssessment[] = [];
let assessSeeded = false;

/** 与后端 `coefficientOf` 同一套分档（参数缺省值）：≥95% 不打折，≥80% 0.9，以下 0.8；无工单 1。 */
export function coefficientOf(sla: number | null): number {
  if (sla == null) return 1;
  if (sla >= 0.95) return 1;
  if (sla >= 0.8) return 0.9;
  return 0.8;
}

function seedAssessments() {
  if (assessSeeded) return;
  assessSeeded = true;
  const periods = ["2026-08", "2026-07", "2026-06"];
  let id = 1;
  agents.forEach((a, ai) => {
    periods.forEach((period, pi) => {
      const done = 6 + ((ai * 7 + pi * 3) % 14);
      const takenOver = (ai + pi) % 4 === 0 ? 2 : (ai + pi) % 3 === 0 ? 1 : 0;
      const total = done + takenOver;
      const miss = (ai * 5 + pi) % 4;
      const inSla = Math.max(0, done - miss);
      const sla = total === 0 ? null : Number((inSla / total).toFixed(4));
      const [y, m] = period.split("-").map(Number);
      const apply = `${m === 12 ? y + 1 : y}-${String(m === 12 ? 1 : m + 1).padStart(2, "0")}`;
      agentOpsAssessments.push({
        id: id++, agentNo: a.agentNo, period, applyPeriod: apply,
        woTotal: total, woInSla: inSla, slaRate: sla,
        onlineRate: ai % 5 === 4 ? null : Number((0.86 + ((ai + pi) % 6) * 0.02).toFixed(4)),
        complaints: (ai + pi * 2) % 5, takenOver,
        coefficient: coefficientOf(sla),
        computedAt: `${apply}-01T02:00:00`, createdAt: `${apply}-01T02:00:00`,
      });
    });
  });
}

/** 某代理的考核历史，按考核月倒序（后端 `orderByDesc(period)`）。 */
export function listAgentOpsAssessments(agentNo: string): AgentOpsAssessment[] {
  seedAssessments();
  return agentOpsAssessments.filter((x) => x.agentNo === agentNo).sort((a, b) => b.period.localeCompare(a.period));
}
