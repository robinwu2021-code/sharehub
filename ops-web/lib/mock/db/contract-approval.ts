import type {
  Contract, ContractStatus, ContractLogEvent, ContractSummary, ContractLogItem, ContractFlow, ContractTermination,
} from "../../types";
import { CONTRACT_TRANSITIONS } from "../../types";
import { fail } from "../../biz-error";
import { contracts, attachFiles } from "./location";

/**
 * 合同审批的 mock。
 *
 * <h3>状态机在 mock 层也要强制</h3>
 * 与后端同规则，**非法迁移抛错**。mock 放行而后端拒绝，离线调一路顺、
 * 切后端当场 500 —— 本仓库踩过四次。
 *
 * <h3>两段式审批：状态只说「在审批中」，是谁的活由 auditStage 说</h3>
 * PENDING 里含两个环节：运营审条款（OPS）→ 财务会签（FINANCE）。
 * 合并成一个状态的话「待我审批」这个数分不出两边，运营和财务互相等。
 *
 * <h3>没有「直接生效」的口子</h3>
 * `SIGNED → ACTIVE` 与 `ACTIVE → EXPIRED` 是**系统边**（定时任务按生效日/到期日推进），
 * 这里不提供方法。手工点「生效」会让合同生效日与实际计费口径对不上。
 */

const logs = new Map<string, ContractLogItem[]>();
let seq = 7000;

const now = () => new Date().toISOString();
const today = () => now().slice(0, 10);
/** 两种形态都认：纯日期原样返回，ISO 串取前 10 位。 */
const dateOnly = (v: string): string => (v ?? "").slice(0, 10);

function find(contractNo: string): Contract {
  const c = contracts.find((x) => x.contractNo === contractNo);
  if (!c) fail(`合同不存在：${contractNo}`, `Contract not found: ${contractNo}`, `العقد غير موجود: ${contractNo}`);
  return c;
}

/** 确保有 flow 子对象（列表种子里没有，详情才给）。 */
function flowOf(c: Contract): ContractFlow {
  if (!c.flow) {
    c.flow = {
      signedAt: null, submittedBy: null, submittedAt: null, auditedBy: null, auditedAt: null,
      auditNote: null, activatedAt: null, endedAt: null, endReason: null, prevContractNo: null,
      sourceLeadNo: null, contractKind: "MAIN", parentContractNo: null, auditStage: null,
      financeAuditedBy: null, financeAuditedAt: null, financeAuditNote: null, termination: null,
    };
  }
  return c.flow;
}

function log(c: Contract, event: ContractLogEvent, from: ContractStatus | null, to: ContractStatus | null, note?: string) {
  const list = logs.get(c.contractNo) ?? [];
  // 最新在前：时间线从上往下读就是倒序，与详情抽屉的渲染顺序一致
  list.unshift({ event, fromStatus: from, toStatus: to, operator: "admin", note: note ?? null, at: now() });
  logs.set(c.contractNo, list);
}

/** 状态机闸：不在 from 集合里就拒，并说清当前在哪一步。 */
function must(c: Contract, action: keyof typeof CONTRACT_TRANSITIONS): ContractStatus {
  const t = CONTRACT_TRANSITIONS[action];
  if (!t.from.includes(c.status)) {
    fail(
      `合同 ${c.contractNo} 当前是「${c.status}」，不能执行「${action}」（允许：${t.from.join("/")}）`,
      `Contract ${c.contractNo} is ${c.status}, cannot ${action}`,
      `العقد ${c.contractNo} في حالة ${c.status}`,
    );
  }
  return t.to;
}

export const getContract = (no: string): Contract => {
  const c = find(no);
  flowOf(c);
  /*
   * remainingDays 由服务端算；mock 也算一份，但按**日期**减，不按毫秒 —— 差一天正是时区坑。
   *
   * ⚠️ endAt 有两种形态：种子里是完整 ISO 串（2027-07-01T12:00:00.000Z），
   * 而新建的合同是纯日期（2027-07-01）。第一版写死 `${c.endAt}T00:00:00`，
   * 拼到 ISO 串上就成了非法日期 → remainingDays 恒为 null，
   * 界面上「距到期 N 天」**一次都没显示过**，而它不报错。
   */
  const end = Date.parse(`${dateOnly(c.endAt)}T00:00:00`);
  c.remainingDays = Number.isNaN(end) ? null : Math.ceil((end - Date.parse(`${today()}T00:00:00`)) / 86_400_000);
  return c;
};

export const listContractLogs = (no: string): ContractLogItem[] => {
  find(no);
  return logs.get(no) ?? [];
};

export function submitContract(no: string): Contract {
  const c = find(no);
  const to = must(c, "submit");
  const f = flowOf(c);
  const from = c.status;
  c.status = to;
  f.submittedBy = "admin";
  f.submittedAt = now();
  f.auditStage = "OPS";      // 进第一个环节
  log(c, "SUBMIT", from, to);
  return c;
}

export function withdrawContract(no: string, note?: string): Contract {
  const c = find(no);
  const to = must(c, "withdraw");
  const from = c.status;
  c.status = to;
  flowOf(c).auditStage = null;
  log(c, "WITHDRAW", from, to, note);
  return c;
}

/** 运营审批。APPROVE 不直接到 SIGNED，而是推进到财务会签环节。 */
export function auditContract(no: string, result: "APPROVE" | "REJECT", reason?: string): Contract {
  const c = find(no);
  const f = flowOf(c);
  if (c.status !== "PENDING" || f.auditStage !== "OPS") {
    fail(
      `合同 ${c.contractNo} 不在运营审批环节（当前 ${c.status} / ${f.auditStage ?? "-"}）`,
      `Contract not in OPS audit stage`, `العقد ليس في مرحلة مراجعة التشغيل`,
    );
  }
  if (result === "REJECT" && !reason?.trim()) {
    fail("驳回必须写原因——不说理由，提交人只能猜", "Reject requires a reason", "الرفض يتطلب سببًا");
  }
  f.auditedBy = "admin";
  f.auditedAt = now();
  f.auditNote = reason ?? null;
  if (result === "REJECT") {
    const from = c.status;
    c.status = must(c, "reject");
    f.auditStage = null;
    log(c, "REJECT", from, c.status, reason);
    return c;
  }
  // 通过 → 进财务会签，**状态仍是 PENDING**
  f.auditStage = "FINANCE";
  log(c, "APPROVE", "PENDING", "PENDING", reason);
  return c;
}

/** 财务会签。通过才到 SIGNED。 */
export function cosignContract(no: string, result: "APPROVE" | "REJECT", reason?: string): Contract {
  const c = find(no);
  const f = flowOf(c);
  if (c.status !== "PENDING" || f.auditStage !== "FINANCE") {
    fail(
      `合同 ${c.contractNo} 不在财务会签环节（当前 ${c.status} / ${f.auditStage ?? "-"}）`,
      `Contract not in FINANCE audit stage`, `العقد ليس في مرحلة المراجعة المالية`,
    );
  }
  if (result === "REJECT" && !reason?.trim()) {
    fail("驳回必须写原因", "Reject requires a reason", "الرفض يتطلب سببًا");
  }
  f.financeAuditedBy = "admin";
  f.financeAuditedAt = now();
  f.financeAuditNote = reason ?? null;
  const from = c.status;
  c.status = result === "APPROVE" ? must(c, "approve") : must(c, "reject");
  f.auditStage = null;
  log(c, result === "APPROVE" ? "COSIGN" : "COSIGN_REJECT", from, c.status, reason);
  return c;
}

export function signContract(no: string, signedAt: string, fileNos: string[]): Contract {
  const c = find(no);
  if (c.status !== "SIGNED") {
    fail(
      `只有已签批（SIGNED）的合同可以登记签署件，当前是「${c.status}」`,
      `Only SIGNED contracts can register signature files`, `فقط العقود الموقعة`,
    );
  }
  const f = flowOf(c);
  f.signedAt = signedAt || today();
  // 签署件就是附件：挂到合同上（TEMP → BOUND），与后端 sign → attachFiles 同一条路径
  if (fileNos?.length) attachFiles(c, fileNos);
  log(c, "SIGN", c.status, c.status, `签署日 ${f.signedAt}`);
  return c;
}

/** 申请提前终止。**不改合同状态** —— 审批期间照常生效。 */
export function terminateContract(no: string, reason: string, effectiveAt?: string): Contract {
  const c = find(no);
  if (!CONTRACT_TRANSITIONS.terminate.from.includes(c.status)) {
    fail(
      `只有生效中（ACTIVE）的合同可以申请终止，当前是「${c.status}」`,
      `Only ACTIVE contracts can request termination`, `فقط العقود النشطة`,
    );
  }
  if (!reason?.trim()) fail("终止原因必填", "Termination reason required", "سبب الإنهاء مطلوب");
  const f = flowOf(c);
  if (f.termination?.status === "PENDING") {
    fail("已有一份待审批的终止申请", "A termination request is already pending", "يوجد طلب إنهاء قيد المراجعة");
  }
  const t: ContractTermination = {
    status: "PENDING", reason, effectiveAt: effectiveAt ?? null,
    requestedBy: "admin", requestedAt: now(), auditedBy: null, auditedAt: null, auditNote: null,
  };
  f.termination = t;
  log(c, "TERM_REQUEST", c.status, c.status, reason);
  return c;
}

export function auditContractTermination(no: string, result: "APPROVE" | "REJECT", reason?: string): Contract {
  const c = find(no);
  const f = flowOf(c);
  if (f.termination?.status !== "PENDING") {
    fail("没有待审批的终止申请", "No pending termination request", "لا يوجد طلب إنهاء قيد المراجعة");
  }
  if (result === "REJECT" && !reason?.trim()) {
    fail("驳回必须写原因", "Reject requires a reason", "الرفض يتطلب سببًا");
  }
  f.termination = { ...f.termination, status: result === "APPROVE" ? "APPROVED" : "REJECTED",
    auditedBy: "admin", auditedAt: now(), auditNote: reason ?? null };
  if (result === "APPROVE") {
    /*
     * 获批**不等于**立刻终止：到 effectiveAt 由定时任务推。没填生效日才是立即。
     * 这里照后端口径处理，否则离线看到的是「一点就终止」，上线后运营会以为坏了。
     */
    if (!f.termination.effectiveAt || f.termination.effectiveAt <= today()) {
      const from = c.status;
      c.status = must(c, "terminate");
      f.endedAt = now();
      f.endReason = f.termination.reason;
      log(c, "TERMINATE", from, c.status, f.termination.reason ?? undefined);
      return c;
    }
  }
  log(c, result === "APPROVE" ? "TERM_APPROVE" : "TERM_REJECT", c.status, c.status, reason);
  return c;
}

/** 续签：生成一份新草稿，prevContractNo 指回原合同。 */
export function renewContract(no: string): Contract {
  const c = find(no);
  if (!["ACTIVE", "EXPIRED"].includes(c.status)) {
    fail(
      `只有生效中或已到期的合同可以续签，当前是「${c.status}」`,
      `Only ACTIVE or EXPIRED contracts can be renewed`, `فقط العقود النشطة أو المنتهية`,
    );
  }
  const draft: Contract = {
    ...c,
    contractNo: `CT${seq++}`,
    status: "DRAFT",
    attachments: [],
    flow: { ...flowOf(c), prevContractNo: c.contractNo, contractKind: "MAIN", signedAt: null,
      submittedBy: null, submittedAt: null, auditedBy: null, auditedAt: null, auditNote: null,
      activatedAt: null, endedAt: null, endReason: null, auditStage: null,
      financeAuditedBy: null, financeAuditedAt: null, financeAuditNote: null, termination: null },
  };
  contracts.unshift(draft);
  log(draft, "RENEW", null, "DRAFT", `续签自 ${c.contractNo}`);
  return draft;
}

/** 补充协议：挂在主合同下，到期日跟原合同。 */
export function supplementContract(no: string, startAt?: string): Contract {
  const c = find(no);
  if (c.status !== "ACTIVE") {
    fail(
      `只有生效中的合同可以签补充协议，当前是「${c.status}」`,
      `Only ACTIVE contracts can have supplements`, `فقط العقود النشطة`,
    );
  }
  const tomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);
  const draft: Contract = {
    ...c,
    contractNo: `CT${seq++}`,
    status: "DRAFT",
    startAt: startAt || tomorrow,
    attachments: [],
    flow: { ...flowOf(c), contractKind: "SUPPLEMENT", parentContractNo: c.contractNo, signedAt: null,
      submittedBy: null, submittedAt: null, auditedBy: null, auditedAt: null, auditNote: null,
      activatedAt: null, endedAt: null, endReason: null, auditStage: null,
      financeAuditedBy: null, financeAuditedAt: null, financeAuditNote: null, termination: null },
  };
  contracts.unshift(draft);
  log(draft, "SUPPLEMENT", null, "DRAFT", `补充协议，主合同 ${c.contractNo}`);
  return draft;
}

/** 摘要条：六个都是要人动手的事。 */
export function contractSummary(): ContractSummary {
  const in60 = new Date(Date.now() + 60 * 86_400_000).toISOString().slice(0, 10);
  const t = today();
  return {
    pendingMine: contracts.filter((c) => c.status === "PENDING" && (c.flow?.auditStage ?? "OPS") === "OPS").length,
    pendingCosign: contracts.filter((c) => c.status === "PENDING" && c.flow?.auditStage === "FINANCE").length,
    terminationPending: contracts.filter((c) => c.flow?.termination?.status === "PENDING").length,
    expiring60: contracts.filter((c) => c.status === "ACTIVE" && c.endAt > t && c.endAt <= in60).length,
    expiredNotRenewed: contracts.filter((c) => c.status === "EXPIRED").length,
    missingScan: contracts.filter((c) => c.status !== "DRAFT" && (c.attachments?.length ?? 0) === 0).length,
  };
}
