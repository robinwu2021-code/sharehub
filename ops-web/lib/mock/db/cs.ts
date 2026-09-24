// 客服 / 售后域：工单式客服 csTickets / 会话 csSessions /
// 投诉订单 orderComplaints（可转工单）/ 退款记录 refundRecords（独立审批队列 + 幂等键 + PSP 流水号）。
// orderNo / userNo 一律取自 order.ts 的 orders mock，保证列表间可互相搜到同一单。
import type {
  CsTicket, CsSession, CsMessage, OrderComplaint, RefundRecord,
  ComplaintIssueType, ComplaintResolution, PageQuery,
  ComplaintCreatePayload, RefundApplyPayload,
} from "../../types";
import { OPERATORS, p, iso } from "./internal";
import { notFound, fail } from "@/lib/biz-error";
import { paginate, kwHit, upsert, nextNo } from "./helpers";
import { cabNo } from "./device";
import { orders } from "./order";

// —— 客服域 ——
// orderNo 取自 orders mock：转退款要落到真实存在的订单上，否则退款记录里挂着查不到的单号。
// 每 5 条留一条无订单的（现场设备类报障常常没有订单），用来验「无订单不给转退款」这条分支。
export const csTickets: CsTicket[] = Array.from({ length: 24 }, (_, i) => ({
  ticketNo: `TK${8000 + i}`, userNo: `U${3000 + (i % 40)}`,
  orderNo: i % 5 === 4 ? null : p(orders, i * 3).orderNo,
  cabinetNo: cabNo(i), problemNo: `PRB${900 + (i % 8)}`,
  issue: p(["充电宝未弹出", "扣费异常", "归还后仍计费", "设备离线", "押金未退", "APP闪退"], i),
  channel: p(["APP", "WhatsApp", "电话", "邮件"], i), status: p(["OPEN", "PROCESSING", "CLOSED"] as const, i),
  handlerNo: i % 3 === 0 ? null : p(OPERATORS, i),
  // 预置一部分「已转出」的单，让幂等分支（按钮换成单号）在 mock 下就能看到
  woNo: i % 6 === 1 ? `WO${70000 + i}` : null,
  refundNo: i % 6 === 2 ? `RFD${80000 + (i % 13)}` : null,
  createdAt: iso(i * 3600_000),
}));
export const csSessions: CsSession[] = Array.from({ length: 20 }, (_, i) => ({
  sessionNo: `CS${9000 + i}`, userNo: `U${3000 + (i % 40)}`, agentName: p(["Sara Ahmed", "Noura K.", "客服机器人"], i),
  lastMessage: p(["好的，已为您处理退款", "请提供订单号", "问题已解决，感谢反馈", "正在为您查询…"], i),
  status: i % 3 === 0 ? "CLOSED" : "ACTIVE", updatedAt: iso(i * 1800_000),
}));

// 会话消息：每个会话 3 条（用户问 → 客服答 → 用户追问），
// 最后一条客服消息与会话的 lastMessage 对齐 —— 列表摘要和时间线不能自相矛盾。
export const csMessages: CsMessage[] = csSessions.flatMap((s, i) => {
  const base = (i + 1) * 100;
  const ask = p(["扫码后柜机没弹出充电宝", "已经还了为什么还在计费", "押金什么时候退回来", "APP 一直转圈打不开"], i);
  return [
    { id: base + 1, sessionNo: s.sessionNo, senderType: "USER" as const, senderNo: s.userNo, content: ask, attach: null, createdAt: iso(i * 1800_000 + 600_000) },
    { id: base + 2, sessionNo: s.sessionNo, senderType: "AGENT" as const, senderNo: "admin", content: s.lastMessage, attach: null, createdAt: iso(i * 1800_000 + 300_000) },
    { id: base + 3, sessionNo: s.sessionNo, senderType: "USER" as const, senderNo: s.userNo, content: p(["好的，麻烦您了", "我再试一下", "收到，谢谢"], i), attach: i % 4 === 0 ? `https://placehold.co/720x1280?text=${s.sessionNo}` : null, createdAt: iso(i * 1800_000) },
  ];
});

export const listCsTickets = (q: PageQuery = {}) => paginate(csTickets, q.page, q.size, (x) => kwHit(q.keyword, x.ticketNo, x.userNo, x.cabinetNo, x.issue));
export const listCsSessions = (q: PageQuery = {}) => paginate(csSessions, q.page, q.size, (x) => kwHit(q.keyword, x.sessionNo, x.userNo, x.agentName));
export const saveCsTicket = (x: Partial<CsTicket>) => upsert(csTickets, x, "ticketNo", () => nextNo("TK", csTickets));

/**
 * 报障转退款（mock）。**幂等**：refundNo 非空直接返回，这道闸门与后端
 * `CsTicketServiceImpl.toRefund` 一一对应——重复退款是资金事故，mock 也不能少。
 * 复用 applyRefund 落一条真实退款记录，让「退款记录」列表能查到这笔的来源。
 */
export function refundCsTicket(ticketNo: string): CsTicket {
  const tk = csTickets.find((x) => x.ticketNo === ticketNo);
  if (!tk) throw notFound("报障单", "Support ticket", ticketNo);
  if (tk.refundNo) return tk;
  if (!tk.orderNo) fail(`报障单 ${ticketNo} 未关联订单，无法转退款`,
    `Ticket ${ticketNo} is not linked to an order, so it cannot be turned into a refund`,
    `التذكرة ${ticketNo} غير مرتبطة بطلب، لذا لا يمكن تحويلها إلى استرداد`);

  tk.refundNo = applyRefund(tk.orderNo, `报障 ${ticketNo} 转退款：${tk.issue}`).refundNo;
  if (tk.status === "OPEN") tk.status = "PROCESSING"; // 已 CLOSED 的单不复活，与后端 advanceToProcessing 同
  return tk;
}

/**
 * 报障转维修工单（mock）。以 ticketNo 为幂等键，与后端 CsTicketService#toWorkOrder 一致：
 * 已转过的直接回原单，**不开第二张** —— 页面此前是 `alert("...（mock）")` 伪实现，
 * 点几次都只弹提示、什么都没发生，看起来能用其实完全没接。
 */
export function woCsTicket(ticketNo: string): CsTicket {
  const tk = csTickets.find((x) => x.ticketNo === ticketNo);
  if (!tk) throw notFound("报障单", "Support ticket", ticketNo);
  if (tk.woNo) return tk;

  // 工单号基数 70400：告警转单用 70200、投诉转单用 70300，三个来源各占一段，
  // 否则 nextNo 只在各自集合内取 max，号段会互相撞上。
  tk.woNo = nextNo("WO", csTickets.filter((x) => x.woNo), 70400);
  if (tk.status === "OPEN") tk.status = "PROCESSING"; // 与 refundCsTicket 同：CLOSED 的单不复活
  return tk;
}

/** 会话消息（mock）：按时间正序，limit 默认 200、上限 500，与后端取值一致。 */
export const listCsMessages = (sessionNo: string, limit?: number): CsMessage[] =>
  csMessages
    .filter((m) => m.sessionNo === sessionNo)
    .sort((a, b) => a.id - b.id)
    .slice(0, limit && limit > 0 ? Math.min(limit, 500) : 200);

/**
 * 客服回复（mock）。senderType 恒为 AGENT、senderNo 恒取当前登录人，
 * **不接受调用方指定** —— 与后端「发送人取自登录态」保持同一条审计约束。
 */
export function replyCsSession(sessionNo: string, content: string, attach?: string): CsMessage {
  const s = csSessions.find((x) => x.sessionNo === sessionNo);
  if (!s) throw notFound("会话", "Session", sessionNo);
  if (s.status === "CLOSED") fail(`会话已关闭，不能回复：${sessionNo}`,
    `Session ${sessionNo} is closed and cannot be replied to`,
    `الجلسة ${sessionNo} مغلقة ولا يمكن الرد عليها`);

  const m: CsMessage = {
    id: Math.max(0, ...csMessages.map((x) => x.id)) + 1,
    sessionNo, senderType: "AGENT", senderNo: "admin",
    content, attach: attach?.trim() ? attach.trim() : null,
    createdAt: new Date().toISOString(),
  };
  csMessages.push(m);
  // 冗余摘要同步：会话列表直出这两列，漏更就显示成上一条
  s.lastMessage = content.slice(0, 500);
  s.updatedAt = m.createdAt;
  return m;
}

// ============================================================================
// 售后处置（对标简电云 B1/B2）：投诉订单 / 退款记录
// 关键：投诉可转工单（投诉-订单-工单串通）；退款走独立审批队列并带幂等键 + PSP 流水号。
// ============================================================================
const COMPLAINT_TYPES: ComplaintIssueType[] = ["BILLING_DISPUTE", "NOT_EJECTED", "NOT_RETURNED", "DEVICE_FAULT", "OTHER"];
const COMPLAINT_DESC: Record<ComplaintIssueType, string> = {
  BILLING_DISPUTE: "只借了 20 分钟却按 2 小时计费，要求核对账单",
  NOT_EJECTED: "扫码后柜机没有弹出充电宝，但订单已生成并开始计费",
  NOT_RETURNED: "已经把充电宝插回柜机，App 仍显示租借中",
  DEVICE_FAULT: "借到的充电宝无法充电，接口松动",
  OTHER: "机器屏幕不亮，现场无人可协助",
};
const COMPLAINT_RESOLUTIONS: ComplaintResolution[] = ["REFUND", "COMPENSATE", "REJECT", "EXPLAINED"];

export const orderComplaints: OrderComplaint[] = Array.from({ length: 12 }, (_, i) => {
  const o = p(orders, i * 7);
  const type = p(COMPLAINT_TYPES, i);
  const st = p(["PENDING", "PENDING", "PROCESSING", "RESOLVED", "RESOLVED", "REJECTED"] as const, i);
  const done = st === "RESOLVED" || st === "REJECTED";
  return {
    complaintNo: `CPL${60000 + i}`, orderNo: o.orderNo, userNo: o.cUserNo,
    issueType: type, description: COMPLAINT_DESC[type],
    // mock 截图统一走占位图服务，真实实现替换为对象存储签名 URL
    screenshotUrl: i % 4 === 3 ? null : `https://placehold.co/720x1280?text=CPL${60000 + i}`,
    submittedAt: iso(i * 7200_000), status: st,
    handlerName: st === "PENDING" ? null : p(OPERATORS, i + 1),
    handledAt: done ? iso(i * 7200_000 - 1800_000) : null,
    resolution: done ? (st === "REJECTED" ? "REJECT" : p(COMPLAINT_RESOLUTIONS, i)) : null,
    resolutionNote: done ? p(["已按实际时长重算并退差额", "补发 10 AED 优惠券作为补偿", "核对后计费无误，已向用户解释", "已远程弹仓并确认归还成功"], i) : "",
    // 设备类投诉默认已转工单：现场问题必须落到运维手上
    workOrderNo: type === "DEVICE_FAULT" || type === "NOT_EJECTED" ? `WO${70000 + (i % 64)}` : null,
  };
});

const REFUND_REASONS = ["计费争议，按实际时长重算", "未弹出充电宝，全额退回", "重复扣款", "设备故障导致无法使用", "超时买断后找回设备"];

export const refundRecords: RefundRecord[] = Array.from({ length: 13 }, (_, i) => {
  const o = p(orders, i * 5 + 2);
  const st = p(["PENDING", "PENDING", "APPROVED", "EXECUTED", "EXECUTED", "REJECTED", "FAILED"] as const, i);
  const audited = st !== "PENDING";
  const executed = st === "EXECUTED" || st === "FAILED";
  return {
    refundNo: `RFD${80000 + i}`, orderNo: o.orderNo, userNo: o.cUserNo,
    amount: Number((o.feeAmount > 0 ? o.feeAmount : 12 + (i % 5) * 3).toFixed(2)), currency: "AED",
    reason: p(REFUND_REASONS, i), applicantName: p(OPERATORS, i), appliedAt: iso(i * 10800_000),
    status: st,
    auditorName: audited ? p(["Sara Ahmed", "Omar Khan", "admin"], i) : null,
    auditedAt: audited ? iso(i * 10800_000 - 3600_000) : null,
    rejectReason: st === "REJECTED" ? p(["订单计费无误，用户已确认", "超出退款申请时效", "同一订单已退款，重复提交"], i) : null,
    // 幂等键 = 订单号 + 申请序号：同一订单重复申请只会落到同一笔退款
    idempotencyKey: `RF-${o.orderNo}-${String(i % 3)}`,
    pspTxnNo: executed ? `PSP${202607000000 + i * 137}` : null,
  };
});

export const listOrderComplaints = (q: PageQuery & { status?: string } = {}) =>
  paginate(orderComplaints, q.page, q.size, (x) =>
    kwHit(q.keyword, x.complaintNo, x.orderNo, x.userNo, x.description, x.handlerName, x.workOrderNo) &&
    (!q.status || x.status === q.status));
export const listRefundRecords = (q: PageQuery & { status?: string } = {}) =>
  paginate(refundRecords, q.page, q.size, (x) =>
    kwHit(q.keyword, x.refundNo, x.orderNo, x.userNo, x.applicantName, x.auditorName, x.pspTxnNo, x.idempotencyKey) &&
    (!q.status || x.status === q.status));

export const saveOrderComplaint = (x: Partial<OrderComplaint>) =>
  upsert(orderComplaints, x, "complaintNo", () => nextNo("CPL", orderComplaints, 60000));

/**
 * 客服代客登记投诉（mock，对应 POST /api/trade/complaints）。
 *
 * 补这个入口之前，投诉队列只能被动等 C 端自助提交 —— 电话/线下投诉根本进不了系统，
 * 客服只能处理「从别处冒出来」的投诉。
 *
 * 服务端口径（与后端 ComplaintServiceImpl 一致）：投诉号由服务端派、提交时间服务端打点、
 * 状态一律 PENDING、处理人/结果留空，随后走既有「处理 / 转工单」队列。
 */
export function createOrderComplaint(x: ComplaintCreatePayload): OrderComplaint {
  const orderNo = x?.orderNo?.trim();
  if (!orderNo) throw fail("投诉登记必须填写关联订单号", "A complaint must reference an order number", "يجب أن تشير الشكوى إلى رقم طلب");
  // 关联订单必须真实存在：挂在查不到的单上，后续退款/工单都无从核对
  const o = orders.find((r) => r.orderNo === orderNo);
  if (!o) throw notFound("订单", "Order", orderNo);
  const description = x.description?.trim();
  if (!description) throw fail("投诉登记必须填写用户描述", "A complaint must include what the user reported", "يجب أن تتضمن الشكوى وصف المستخدم");

  const created: OrderComplaint = {
    complaintNo: nextNo("CPL", orderComplaints, 60000, "complaintNo"),
    orderNo,
    // 用户号缺省取订单的下单人：客服现场往往只问到订单号
    userNo: x.userNo?.trim() || o.cUserNo,
    issueType: x.issueType ?? "OTHER",
    description,
    screenshotUrl: x.screenshotUrl?.trim() || null,
    submittedAt: new Date().toISOString(),
    status: "PENDING",
    handlerName: null, handledAt: null, resolution: null, resolutionNote: "", workOrderNo: null,
  };
  orderComplaints.unshift(created);
  return created;
}

/** 处理投诉（mock）：写入处理结果/说明/处理人/处理时间；驳回落 REJECTED，其余落 RESOLVED。 */
export function handleOrderComplaint(complaintNo: string, resolution: ComplaintResolution, note: string): OrderComplaint {
  const c = orderComplaints.find((x) => x.complaintNo === complaintNo)!;
  c.resolution = resolution;
  c.resolutionNote = note;
  c.status = resolution === "REJECT" ? "REJECTED" : "RESOLVED";
  c.handlerName = "admin";
  c.handledAt = new Date().toISOString();
  return c;
}

/** 投诉转工单（mock）：生成关联工单号并置为处理中；已转过的沿用原工单号（幂等）。 */
export function raiseComplaintWorkOrder(complaintNo: string): OrderComplaint {
  const c = orderComplaints.find((x) => x.complaintNo === complaintNo)!;
  c.workOrderNo = c.workOrderNo ?? nextNo("WO", orderComplaints.filter((x) => x.workOrderNo), 70300);
  if (c.status === "PENDING") c.status = "PROCESSING";
  return c;
}

/**
 * 退款落库的**唯一入口**（申请单号 / 申请人 / 申请时间 / 状态全在这里由「服务端」决定，
 * 调用方给什么都不采信）。幂等键相同直接返回已有单 —— 重复插入就是重复出款。
 */
function insertRefund(x: RefundApplyPayload): RefundRecord {
  const exist = refundRecords.find((r) => r.idempotencyKey === x.idempotencyKey);
  if (exist) return exist;
  const created: RefundRecord = {
    refundNo: nextNo("RFD", refundRecords, 80000), orderNo: x.orderNo,
    userNo: x.userNo, amount: x.amount, currency: x.currency ?? "AED",
    reason: x.reason, applicantName: "admin", appliedAt: new Date().toISOString(), status: "PENDING",
    auditorName: null, auditedAt: null, rejectReason: null, idempotencyKey: x.idempotencyKey, pspTxnNo: null,
  };
  refundRecords.unshift(created);
  return created;
}

/** 退款申请（mock）：订单详情抽屉「申请退款」的落库入口，幂等键相同则复用既有申请。 */
export function applyRefund(orderNo: string, reason = "客服代客申请退款"): RefundRecord {
  const o = orders.find((x) => x.orderNo === orderNo);
  return insertRefund({
    orderNo, userNo: o?.cUserNo ?? "-", amount: o?.feeAmount ?? 0, currency: o?.currency ?? "AED",
    reason, idempotencyKey: `RF-${orderNo}-manual`,
  });
}

/**
 * 新建退款申请（mock，对应 POST /api/trade/refunds）——客服在退款队列里直接开单，
 * 不必先绕到订单详情做一次「干预」。落 PENDING 进审批队列，审批通过才真正出款。
 *
 * 幂等键**必填**：没有键时双击提交 / 网络重试会各落一笔，接真后端就是真的退两次钱
 *（同 marketing 推送发送口径，键由前端在表单打开时生成一次并全程沿用）。
 */
export function createRefund(x: RefundApplyPayload): RefundRecord {
  const key = x?.idempotencyKey?.trim();
  if (!key) throw fail("退款申请必须携带幂等键（idempotencyKey）——重复提交会真的退两笔钱", "A refund request must carry an idempotencyKey — without it a double submit really refunds twice", "يجب أن يحمل طلب الاسترداد مفتاح idempotencyKey — بدونه يؤدي الإرسال المكرر إلى استرداد مزدوج فعلي");
  const orderNo = x.orderNo?.trim();
  if (!orderNo) throw fail("退款申请必须填写关联订单号", "A refund request must reference an order number", "يجب أن يشير طلب الاسترداد إلى رقم طلب");
  // 订单必须真实存在：退款要对得上原支付流水，挂空单号的退款审批时无从核对
  const o = orders.find((r) => r.orderNo === orderNo);
  if (!o) throw notFound("订单", "Order", orderNo);
  const amount = Number(x.amount);
  if (!(amount > 0)) throw fail("退款金额必须大于 0", "Refund amount must be greater than 0", "يجب أن يكون مبلغ الاسترداد أكبر من 0");
  if (!x.reason?.trim()) throw fail("退款申请必须填写退款原因", "A refund request must state a reason", "يجب ذكر سبب الاسترداد");

  return insertRefund({
    orderNo, userNo: x.userNo?.trim() || o.cUserNo, amount: Number(amount.toFixed(2)),
    // 币种跟随订单（服务端兜底 AED）：退款与原收款不同币种对不上账
    currency: x.currency?.trim() || o.currency,
    reason: x.reason.trim(), idempotencyKey: key,
  });
}

/** 退款审批（mock）：通过→APPROVED 并模拟 PSP 执行落 EXECUTED；驳回→REJECTED 并记原因。 */
export function auditRefund(refundNo: string, approve: boolean, rejectReason?: string): RefundRecord {
  const r = refundRecords.find((x) => x.refundNo === refundNo)!;
  r.auditorName = "admin";
  r.auditedAt = new Date().toISOString();
  if (approve) {
    r.status = "EXECUTED";
    r.rejectReason = null;
    r.pspTxnNo = r.pspTxnNo ?? `PSP${202607000000 + refundRecords.length * 137}`;
  } else {
    r.status = "REJECTED";
    r.rejectReason = rejectReason ?? "";
  }
  return r;
}
