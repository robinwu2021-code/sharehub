// 客服 / 售后域：工单式客服 csTickets / 会话 csSessions /
// 投诉订单 orderComplaints（可转工单）/ 退款记录 refundRecords（独立审批队列 + 幂等键 + PSP 流水号）。
// orderNo / userNo 一律取自 order.ts 的 orders mock，保证列表间可互相搜到同一单。
import type {
  CsTicket, CsSession, OrderComplaint, RefundRecord,
  ComplaintIssueType, ComplaintResolution, PageQuery,
} from "../../types";
import { OPERATORS, p, iso } from "./internal";
import { paginate, kwHit, upsert, nextNo } from "./helpers";
import { cabNo } from "./device";
import { orders } from "./order";

// —— 客服域 ——
export const csTickets: CsTicket[] = Array.from({ length: 24 }, (_, i) => ({
  ticketNo: `TK${8000 + i}`, userNo: `U${3000 + (i % 40)}`, cabinetNo: cabNo(i),
  issue: p(["充电宝未弹出", "扣费异常", "归还后仍计费", "设备离线", "押金未退", "APP闪退"], i),
  channel: p(["APP", "WhatsApp", "电话", "邮件"], i), status: p(["OPEN", "PROCESSING", "CLOSED"] as const, i),
  createdAt: iso(i * 3600_000),
}));
export const csSessions: CsSession[] = Array.from({ length: 20 }, (_, i) => ({
  sessionNo: `CS${9000 + i}`, userNo: `U${3000 + (i % 40)}`, agentName: p(["Sara Ahmed", "Noura K.", "客服机器人"], i),
  lastMessage: p(["好的，已为您处理退款", "请提供订单号", "问题已解决，感谢反馈", "正在为您查询…"], i),
  status: i % 3 === 0 ? "CLOSED" : "ACTIVE", updatedAt: iso(i * 1800_000),
}));

export const listCsTickets = (q: PageQuery = {}) => paginate(csTickets, q.page, q.size, (x) => kwHit(q.keyword, x.ticketNo, x.userNo, x.cabinetNo, x.issue));
export const listCsSessions = (q: PageQuery = {}) => paginate(csSessions, q.page, q.size, (x) => kwHit(q.keyword, x.sessionNo, x.userNo, x.agentName));
export const saveCsTicket = (x: Partial<CsTicket>) => upsert(csTickets, x, "ticketNo", () => nextNo("TK", csTickets));

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
    psgTxnNo: executed ? `PSP${202607000000 + i * 137}` : null,
  };
});

export const listOrderComplaints = (q: PageQuery & { status?: string } = {}) =>
  paginate(orderComplaints, q.page, q.size, (x) =>
    kwHit(q.keyword, x.complaintNo, x.orderNo, x.userNo, x.description, x.handlerName, x.workOrderNo) &&
    (!q.status || x.status === q.status));
export const listRefundRecords = (q: PageQuery & { status?: string } = {}) =>
  paginate(refundRecords, q.page, q.size, (x) =>
    kwHit(q.keyword, x.refundNo, x.orderNo, x.userNo, x.applicantName, x.auditorName, x.psgTxnNo, x.idempotencyKey) &&
    (!q.status || x.status === q.status));

export const saveOrderComplaint = (x: Partial<OrderComplaint>) =>
  upsert(orderComplaints, x, "complaintNo", () => nextNo("CPL", orderComplaints, 60000));

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

/** 退款申请（mock）：订单详情抽屉「申请退款」的落库入口，幂等键相同则复用既有申请。 */
export function applyRefund(orderNo: string, reason = "客服代客申请退款"): RefundRecord {
  const key = `RF-${orderNo}-manual`;
  const exist = refundRecords.find((x) => x.idempotencyKey === key);
  if (exist) return exist;
  const o = orders.find((x) => x.orderNo === orderNo);
  const created: RefundRecord = {
    refundNo: nextNo("RFD", refundRecords, 80000), orderNo,
    userNo: o?.cUserNo ?? "-", amount: o?.feeAmount ?? 0, currency: o?.currency ?? "AED",
    reason, applicantName: "admin", appliedAt: new Date().toISOString(), status: "PENDING",
    auditorName: null, auditedAt: null, rejectReason: null, idempotencyKey: key, psgTxnNo: null,
  };
  refundRecords.unshift(created);
  return created;
}

/** 退款审批（mock）：通过→APPROVED 并模拟 PSP 执行落 EXECUTED；驳回→REJECTED 并记原因。 */
export function auditRefund(refundNo: string, approve: boolean, rejectReason?: string): RefundRecord {
  const r = refundRecords.find((x) => x.refundNo === refundNo)!;
  r.auditorName = "admin";
  r.auditedAt = new Date().toISOString();
  if (approve) {
    r.status = "EXECUTED";
    r.rejectReason = null;
    r.psgTxnNo = r.psgTxnNo ?? `PSP${202607000000 + refundRecords.length * 137}`;
  } else {
    r.status = "REJECTED";
    r.rejectReason = rejectReason ?? "";
  }
  return r;
}
