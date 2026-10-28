// 付款任务（V158，商家一期 B4）—— mock 与后端 PayoutTaskServiceImpl 同口径。
//
// 规则（与后端一一对应）：
//  · 只纳入「已确认、商家、未纳入其他提现单、账期结束日不晚于截止日、金额 > 0」的结算单，一个商家一笔；
//  · 同一时刻至多一个未结束的任务；复核人不得是发起人；驳回必填原因、整任务撤回；
//  · 确认到账：成功的结算单置已付，失败的放回可付款范围；没有在途的笔则任务完成。
// 「结算单纳入了哪张提现单」后端存在 stl_settlement.withdraw_no；mock 不改 Settlement 类型，用一张旁表记。
import type {
  PageResult, PayoutPreview, PayoutPreviewItem, PayoutTask, PayoutTaskItem, PayoutTaskStatus,
  PayoutConfirmPayload, PayoutConfirmItem, PayoutBlockedReason, Settlement, Withdrawal,
} from "../../types";
import { fail, notFound } from "@/lib/biz-error";
import { paginate, nextNo } from "./helpers";
import { settlements, withdrawals, payoutAccounts } from "./finance";
import { agents } from "./agent";

type TaskRow = Omit<PayoutTask, "items"> & { withdrawNos: string[] };

export const payoutTasks: TaskRow[] = [];
/** settleNo → withdrawNo（后端 stl_settlement.withdraw_no）。 */
export const settlementWithdraw = new Map<string, string>();
/** withdrawNo → taskNo（后端 stl_withdrawal.task_no）。 */
const withdrawTask = new Map<string, string>();

const OPEN: PayoutTaskStatus[] = ["PENDING_REVIEW", "PENDING_TRANSFER"];

const periodEnd = (period: string): string | null => {
  const m = /^(\d{4})-(\d{2})$/.exec(period);
  if (!m) return null;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]), 0));
  return d.toISOString().slice(0, 10);
};

function plan(cutoff: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(cutoff)) fail("请选择付款截止日", "Pick a cutoff date");
  const due = settlements.filter((s) => s.status === "CONFIRMED" && s.payeeType === "AGENT"
    && !settlementWithdraw.has(s.settleNo) && s.totalAmount > 0
    && (periodEnd(s.period) ?? "9999") <= cutoff);
  const byPayee = new Map<string, Settlement[]>();
  for (const s of due) byPayee.set(s.payeeNo, [...(byPayee.get(s.payeeNo) ?? []), s]);

  const items: PayoutPreviewItem[] = [];
  for (const [payeeNo, group] of [...byPayee.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    group.sort((a, b) => a.period.localeCompare(b.period));
    const acct = payoutAccounts.find((a) => a.payeeType === "AGENT" && a.payeeNo === payeeNo && a.isDefault && a.status === "ACTIVE");
    const agent = agents.find((a) => a.agentNo === payeeNo);
    let blocked: PayoutBlockedReason | null = null;
    if (group.some((s) => s.currency !== group[0].currency)) blocked = "MIXED_CURRENCY";
    else if (!acct) blocked = "NO_ACCOUNT";
    // mock 不存完整账号：一律视为已补录（真后端会检查密文是否存在）
    else if (agent?.status === "SUSPENDED") blocked = "SUSPENDED";
    items.push({
      payeeNo, payeeName: group[0].payeeName, settleNos: group.map((s) => s.settleNo), periods: group.map((s) => s.period),
      amount: Number(group.reduce((n, s) => n + s.totalAmount, 0).toFixed(2)), currency: group[0].currency,
      accountMasked: acct?.accountMasked ?? null, blockedReason: blocked,
    });
  }
  const payable = items.filter((i) => !i.blockedReason);
  const preview: PayoutPreview = {
    cutoffDate: cutoff, currency: payable[0]?.currency ?? "AED", payableCount: payable.length,
    payableTotal: Number(payable.reduce((n, i) => n + i.amount, 0).toFixed(2)), items,
  };
  return { preview, payable };
}

export const previewPayoutTask = (cutoff: string): PayoutPreview => plan(cutoff).preview;

function requireTask(taskNo: string) {
  const t = payoutTasks.find((x) => x.taskNo === taskNo);
  if (!t) notFound("付款任务", "Payout task", taskNo);
  return t;
}

function requireStatus(t: TaskRow, want: PayoutTaskStatus) {
  if (t.status !== want) fail(`付款任务 ${t.taskNo} 当前状态为 ${t.status}，不能执行此操作`, `Payout task ${t.taskNo} is ${t.status}`);
}

const wdOf = (no: string) => withdrawals.find((w) => w.withdrawNo === no)!;

function release(withdrawNo: string) {
  for (const [settleNo, wd] of [...settlementWithdraw.entries()]) if (wd === withdrawNo) settlementWithdraw.delete(settleNo);
}

function toVO(t: TaskRow, withItems: boolean): PayoutTask {
  const { withdrawNos, ...rest } = t;
  const items: PayoutTaskItem[] = withItems ? withdrawNos.map((no, i) => {
    const w = wdOf(no);
    return {
      seq: i + 1, withdrawNo: no, payeeNo: w.payeeNo, payeeName: w.payeeName, amount: w.amount, currency: w.currency,
      accountName: payoutAccounts.find((a) => a.accountNo === w.accountNo)?.accountName ?? null,
      accountMasked: payoutAccounts.find((a) => a.accountNo === w.accountNo)?.accountMasked ?? null,
      status: w.status, payRef: w.payRef, paidAt: w.paidAt, failReason: w.failReason ?? w.rejectReason ?? null,
      settleNos: [...settlementWithdraw.entries()].filter(([, wd]) => wd === no).map(([s]) => s),
    };
  }) : [];
  return { ...rest, items };
}

export function createPayoutTask(cutoff: string, who: string): PayoutTask {
  if (payoutTasks.some((t) => OPEN.includes(t.status))) {
    fail("已有一个付款任务在复核或转账中，处理完再生成新的", "A payout task is already in progress");
  }
  const { preview, payable } = plan(cutoff);
  if (!payable.length) fail(`截止 ${cutoff} 没有可付款的结算单`, `No settlements are payable up to ${cutoff}`);
  const taskNo = nextNo("PT", payoutTasks, 900, "taskNo");
  const withdrawNos: string[] = [];
  for (const it of payable) {
    const acct = payoutAccounts.find((a) => a.payeeType === "AGENT" && a.payeeNo === it.payeeNo && a.isDefault && a.status === "ACTIVE")!;
    const withdrawNo = nextNo("WD", withdrawals, 9000, "withdrawNo");
    const w: Withdrawal = {
      withdrawNo, payeeType: "AGENT", payeeNo: it.payeeNo, payeeName: it.payeeName, amount: it.amount, fee: 0,
      currency: it.currency, status: "APPLY", appliedAt: new Date().toISOString(),
      accountNo: acct.accountNo, bankCode: acct.bankCode, applicantNo: who,
      payChannel: null, payRef: null, payerName: null, failReason: null, sameOperator: false, paidAt: null,
      auditorName: null, auditedAt: null, rejectReason: null,
    } as Withdrawal;
    withdrawals.unshift(w);
    withdrawTask.set(withdrawNo, taskNo);
    for (const s of it.settleNos) settlementWithdraw.set(s, withdrawNo);
    withdrawNos.push(withdrawNo);
  }
  const t: TaskRow = {
    taskNo, cutoffDate: cutoff, currency: preview.currency, itemCount: payable.length, totalAmount: preview.payableTotal,
    status: "PENDING_REVIEW", createdBy: who, createdName: who, createdAt: new Date().toISOString(),
    reviewedName: null, reviewedAt: null, rejectReason: null, bankBatchRef: null, transferDate: null,
    confirmedAt: null, exportCount: 0, withdrawNos,
  };
  payoutTasks.unshift(t);
  return toVO(t, true);
}

export function listPayoutTasks(q: { page?: number; size?: number; status?: PayoutTaskStatus } = {}): PageResult<PayoutTask> {
  const r = paginate(payoutTasks, q.page, q.size, (t) => !q.status || t.status === q.status);
  return { ...r, list: r.list.map((t) => toVO(t, false)) };
}

export const getPayoutTask = (taskNo: string): PayoutTask => toVO(requireTask(taskNo), true);

export function auditPayoutTask(taskNo: string, approve: boolean, rejectReason: string | undefined, who: string): PayoutTask {
  const t = requireTask(taskNo);
  requireStatus(t, "PENDING_REVIEW");
  if (who && who === t.createdBy) fail(`付款任务 ${taskNo} 是你发起的，需要另一位同事复核`, `You created payout task ${taskNo}; another colleague must review it`);
  if (!approve && !rejectReason?.trim()) fail("驳回付款任务必须填写原因", "A reason is required to reject a payout task");
  for (const no of t.withdrawNos) {
    const w = wdOf(no);
    Object.assign(w, {
      status: approve ? "PAYING" : "FAILED", auditorName: who, auditedAt: new Date().toISOString(),
      rejectReason: approve ? null : rejectReason!.trim(),
    });
    if (!approve) release(no);
  }
  Object.assign(t, {
    status: approve ? "PENDING_TRANSFER" : "CANCELLED", reviewedName: who, reviewedAt: new Date().toISOString(),
    rejectReason: approve ? null : rejectReason!.trim(),
  });
  return toVO(t, true);
}

/** mock 的转账文件：结构与后端一致，账号用掩码（mock 不存完整账号）。 */
export function exportPayoutTask(taskNo: string): string {
  const t = requireTask(taskNo);
  requireStatus(t, "PENDING_TRANSFER");
  const lines = ["seq,withdraw_no,beneficiary_name,bank_code,iban,amount,currency,reference"];
  t.withdrawNos.forEach((no, i) => {
    const w = wdOf(no);
    if (w.status !== "PAYING") return;
    const acct = payoutAccounts.find((a) => a.accountNo === w.accountNo);
    lines.push([i + 1, no, `"${(acct?.accountName ?? "").replace(/"/g, '""')}"`, w.bankCode ?? "",
      acct?.accountMasked ?? "", w.amount.toFixed(2), w.currency, no].join(","));
  });
  t.exportCount += 1;
  return "﻿" + lines.join("\r\n") + "\r\n";
}

export function confirmPayoutTask(taskNo: string, body: PayoutConfirmPayload, who: string): PayoutTask {
  const t = requireTask(taskNo);
  requireStatus(t, "PENDING_TRANSFER");
  if (!body.bankBatchRef?.trim()) fail("请填写银行批次参考号", "Bank batch reference is required");
  if (!body.transferDate) fail("请填写转账日期", "Transfer date is required");
  const results: PayoutConfirmItem[] = body.results?.length ? body.results
    : t.withdrawNos.filter((no) => wdOf(no).status === "PAYING").map((withdrawNo) => ({ withdrawNo, success: true }));
  for (const r of results) {
    const seq = t.withdrawNos.indexOf(r.withdrawNo) + 1;
    if (!seq) fail(`提现单 ${r.withdrawNo} 不属于付款任务 ${taskNo}`, `Withdrawal ${r.withdrawNo} is not in task ${taskNo}`);
    const w = wdOf(r.withdrawNo);
    if (w.status !== "PAYING") fail(`提现单 ${r.withdrawNo} 不在出款中，不能登记结果`, `Withdrawal ${r.withdrawNo} is not in transit`);
    if (r.success) {
      Object.assign(w, { status: "PAID", payChannel: "MANUAL", payRef: r.payRef?.trim() || `${taskNo}-${seq}`,
        payerName: who, paidAt: new Date().toISOString(), failReason: null, sameOperator: who === w.auditorName });
      for (const [settleNo, wd] of settlementWithdraw) {
        if (wd !== r.withdrawNo) continue;
        const s = settlements.find((x) => x.settleNo === settleNo);
        if (s) s.status = "PAID";
      }
    } else {
      if (!r.failReason?.trim()) fail("登记打款失败必须填写失败原因", "A failure reason is required");
      Object.assign(w, { status: "FAILED", payChannel: "MANUAL", payerName: who, failReason: r.failReason!.trim() });
      release(r.withdrawNo);
    }
  }
  Object.assign(t, {
    bankBatchRef: body.bankBatchRef.trim(), transferDate: body.transferDate, confirmedAt: new Date().toISOString(),
    status: t.withdrawNos.some((no) => wdOf(no).status === "PAYING") ? "PENDING_TRANSFER" : "DONE",
  });
  return toVO(t, true);
}
