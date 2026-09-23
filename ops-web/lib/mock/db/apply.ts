// 覆盖范围：运营主体入驻申请（ADR-030 §三）。
//
// 「条件相同」在 mock 层的含义与后端一致：**商家自助与运营代建落同一份数据、
// 同一个状态机、同一套校验**，只有 source 与 submittedBy 两个字段不同。
//
// ⚠️ 状态机在本层强制、非法迁移抛错（本仓既有约定）——不这么做的话，
// 前端在 mock 下能点通一切，联调时才发现后端拒绝，而那时页面逻辑已经照"能点通"写完了。
import type { AgentApply, ApplyStatus, OperatorType } from "../../types";
import { fail, notFound } from "@/lib/biz-error";
import { paginate, kwHit } from "./helpers";
import { agents } from "./agent";

/** 在途状态。**必须与后端 V48 生成列 `active_key` 的 CASE 分支一致**，改一处要改两处。 */
const IN_FLIGHT: ApplyStatus[] = ["DRAFT", "SUBMITTED", "REVIEWING"];

/** 掩码：与服务端 IdentifierNormalizer.mask 同形（保留前 3 后 4）。 */
function maskPhone(raw: string): string {
  const d = raw.replace(/\D/g, "");
  return d.length < 7 ? "***" : `${d.slice(0, 3)}****${d.slice(-4)}`;
}
function maskEmail(raw: string): string {
  const t = raw.trim().toLowerCase();
  const at = t.indexOf("@");
  return at <= 0 ? "***" : `${t[0]}***${t.slice(at)}`;
}
/** mock 侧用规范化后的明文当"hash"——真实现是 HMAC，这里只要同一个号算出同一个键。 */
function phoneKey(raw: string): string {
  return raw.replace(/\D/g, "").replace(/^0+/, "");
}

interface ApplyRow extends AgentApply {
  /** mock 内部：代替服务端的 phone_hash，用于「同手机号至多一张在途」与多主体识别。 */
  _phoneKey: string;
}

export const applies: ApplyRow[] = [
  {
    applyNo: "AP001", source: "SELF_SERVICE", status: "SUBMITTED",
    operatorName: "迪拜湾畔科技", operatorType: "AGENT",
    phoneMask: "971****4567", emailMask: "m***@bayside.ae",
    regionScope: "迪拜 · 商业湾", shareRate: 0.3, payload: "营业执照 · 法人身份证",
    principalNo: null, rejectReason: null,
    submittedBy: "971501234567", submittedAt: "2026-09-21T09:12:00Z",
    reviewedBy: null, reviewedAt: null, operatorNo: null,
    phoneAlreadyKnown: false, knownEmailMask: null,
    _phoneKey: "971501234567",
  },
  {
    // 多主体申请：同一个人已经有 AG001，再开第二个主体。审核台要显眼标出来
    applyNo: "AP002", source: "SELF_SERVICE", status: "SUBMITTED",
    operatorName: "阿布扎比港务合伙", operatorType: "CITY_PARTNER",
    phoneMask: "971****8899", emailMask: "l***@ad-port.ae",
    regionScope: "阿布扎比", shareRate: 0.25, payload: "营业执照",
    principalNo: "PR001", rejectReason: null,
    submittedBy: "971509998899", submittedAt: "2026-09-22T03:40:00Z",
    reviewedBy: null, reviewedAt: null, operatorNo: null,
    phoneAlreadyKnown: true, knownEmailMask: "l***@old-mail.com",
    _phoneKey: "971509998899",
  },
  {
    applyNo: "AP003", source: "OPS_CREATED", status: "REVIEWING",
    operatorName: "沙迦仓储服务", operatorType: "AGENT",
    phoneMask: "971****1122", emailMask: "w***@shj-store.ae",
    regionScope: "沙迦", shareRate: 0.28, payload: "线下已签约，材料补齐中",
    principalNo: null, rejectReason: null,
    submittedBy: "E1001", submittedAt: "2026-09-22T07:05:00Z",
    reviewedBy: "E1001", reviewedAt: null, operatorNo: null,
    phoneAlreadyKnown: false, knownEmailMask: null,
    _phoneKey: "971501111122",
  },
  {
    applyNo: "AP004", source: "SELF_SERVICE", status: "REJECTED",
    operatorName: "测试商户甲", operatorType: "AGENT",
    phoneMask: "971****0000", emailMask: "t***@example.com",
    regionScope: null, shareRate: null, payload: null,
    principalNo: null, rejectReason: "营业执照与主体名称不一致，请补正后重新提交",
    submittedBy: "971500000000", submittedAt: "2026-09-20T11:00:00Z",
    reviewedBy: "E1002", reviewedAt: "2026-09-20T15:30:00Z", operatorNo: null,
    phoneAlreadyKnown: false, knownEmailMask: null,
    _phoneKey: "971500000000",
  },
  {
    applyNo: "AP005", source: "OPS_CREATED", status: "APPROVED",
    operatorName: "示例代理商甲", operatorType: "AGENT",
    phoneMask: "971****1234", emailMask: "a***@agent-one.ae",
    regionScope: "迪拜", shareRate: 0.3, payload: null,
    principalNo: "PR001", rejectReason: null,
    submittedBy: "E1001", submittedAt: "2026-09-18T02:00:00Z",
    reviewedBy: "E1001", reviewedAt: "2026-09-18T02:05:00Z", operatorNo: "AG001",
    phoneAlreadyKnown: false, knownEmailMask: null,
    _phoneKey: "971509991234",
  },
];

const strip = (r: ApplyRow): AgentApply => {
  const { _phoneKey, ...rest } = r;
  void _phoneKey;
  return rest;
};

const mustFind = (applyNo: string): ApplyRow => {
  const r = applies.find((x) => x.applyNo === applyNo);
  if (!r) notFound("申请单", "Application", applyNo);
  return r;
};

export function listAgentApplies(q: { page?: number; size?: number; keyword?: string; status?: string; from?: string; to?: string } = {}) {
  return paginate(
    [...applies].sort((a, b) => (a.submittedAt ?? "").localeCompare(b.submittedAt ?? "")),
    q.page, q.size,
    (r) => {
      // 缺省 = 待办队列。列全部历史要显式传 status
      if (q.status) { if (r.status !== q.status) return false; }
      else if (!IN_FLIGHT.includes(r.status)) return false;
      // 只搜主体名与单号 —— 掩码搜不得（前缀模糊会捞出不相干的人）
      if (!kwHit(q.keyword, r.operatorName, r.applyNo)) return false;
      if (q.from && (r.submittedAt ?? "") < q.from) return false;
      if (q.to && (r.submittedAt ?? "") > q.to) return false;
      return true;
    },
  );
}

export function acceptAgentApply(applyNo: string, operatorName?: string): AgentApply {
  const r = mustFind(applyNo);
  // 状态机：非法迁移抛错，不静默跳过
  if (r.status !== "SUBMITTED") {
    fail(`只有「待受理」的申请可以受理，当前状态：${r.status}`,
         `Only SUBMITTED applications can be accepted, current: ${r.status}`);
  }
  r.status = "REVIEWING";
  r.reviewedBy = operatorName ?? "OPS";
  return strip(r);
}

export function auditAgentApply(x: {
  applyNo: string; approve: boolean; rejectReason?: string;
  shareRate?: number; regionScope?: string; operatorName?: string;
}): AgentApply {
  const r = mustFind(x.applyNo);
  if (r.status !== "SUBMITTED" && r.status !== "REVIEWING") {
    fail(`只有待审核或审核中的申请可以处理，当前状态：${r.status}`,
         `Only SUBMITTED/REVIEWING can be audited, current: ${r.status}`);
  }
  r.reviewedBy = x.operatorName ?? "OPS";
  r.reviewedAt = new Date().toISOString();

  if (!x.approve) {
    if (!x.rejectReason?.trim()) fail("驳回必须填原因", "A reason is required to reject");
    r.status = "REJECTED";
    r.rejectReason = x.rejectReason.trim();
    return strip(r);
  }

  /*
   * 审核通过 = 激活派生。mock 侧也**真的建出主体**，否则代理商档案 tab 里看不到,
   * 而「审核通过了但列表里没有」正是最容易在联调时才发现的一类不一致。
   *
   * 服务端那边同一个事务里还会建自然人与属主账号；mock 只做能被界面看见的那部分。
   */
  const agentNo = `AG${String(agents.length + 1).padStart(3, "0")}`;
  agents.push({
    agentNo, name: r.operatorName, contact: r.phoneMask,
    regionScope: x.regionScope ?? r.regionScope ?? "",
    // A1：受理入驻建出来的默认是代理商；城市合伙人由运营在档案页改（ADR-027）
    agentType: "AGENT",
    shareRate: x.shareRate ?? r.shareRate ?? 0,
    cabinetCount: 0, status: "ENABLED", archivedAt: null,
  });
  r.status = "APPROVED";
  r.operatorNo = agentNo;
  r.rejectReason = null;
  if (x.shareRate !== undefined) r.shareRate = x.shareRate;
  if (x.regionScope !== undefined) r.regionScope = x.regionScope;
  return strip(r);
}

export function createAgentApply(x: {
  phone: string; email: string; operatorName: string; operatorType: OperatorType;
  regionScope?: string; shareRate?: number; payload?: string;
}): AgentApply {
  if (!x.operatorName?.trim()) fail("主体名称必填", "Operator name is required");
  if (!x.phone?.trim()) fail("手机号必填", "Phone is required");
  if (!x.email?.trim()) fail("邮箱必填", "Email is required");

  const key = phoneKey(x.phone);
  // 同手机号至多一张在途 —— 服务端靠生成列 active_key 强制，这里给出人话
  if (applies.some((r) => r._phoneKey === key && IN_FLIGHT.includes(r.status))) {
    fail("该手机号已有一张在途申请，请先等待审核结果",
         "This phone already has an application in progress");
  }
  // 手机号已有主体**不是重复注册**，是多主体申请：带出已知信息给审核人，不拦
  const known = applies.find((r) => r._phoneKey === key && r.status === "APPROVED");

  const row: ApplyRow = {
    applyNo: `AP${String(applies.length + 1).padStart(3, "0")}`,
    source: "OPS_CREATED",              // 本入口只给运营；自助入口在公开页（D5）
    status: "SUBMITTED",
    operatorName: x.operatorName.trim(), operatorType: x.operatorType,
    phoneMask: maskPhone(x.phone), emailMask: maskEmail(x.email),
    regionScope: x.regionScope ?? null,
    shareRate: x.shareRate ?? null,
    payload: x.payload ?? null,
    principalNo: known?.principalNo ?? null,
    rejectReason: null,
    submittedBy: "OPS", submittedAt: new Date().toISOString(),
    reviewedBy: null, reviewedAt: null, operatorNo: null,
    phoneAlreadyKnown: !!known,
    knownEmailMask: known && known.emailMask !== maskEmail(x.email) ? known.emailMask : null,
    _phoneKey: key,
  };
  applies.push(row);
  return strip(row);
}
