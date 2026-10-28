// 付款任务（V158）mock —— 与后端 PayoutTaskFlowTest 同口径。
// 结算单用 2001 年账期、商家用新造编号：截止日只有上限，只会碰到本文件造的数据。
import { beforeEach, describe, expect, it } from "vitest";
import { settlements, payoutAccounts } from "./finance";
import {
  payoutTasks, settlementWithdraw, previewPayoutTask, createPayoutTask, auditPayoutTask, exportPayoutTask, confirmPayoutTask,
} from "./payout-task";

let n = 0;
const settle = (payeeNo: string, period: string, totalAmount: number) => {
  const settleNo = `STLM${++n}`;
  settlements.push({ settleNo, payeeType: "AGENT", payeeNo, payeeName: `商家 ${payeeNo}`, period, totalAmount,
    recordCount: 1, currency: "AED", status: "CONFIRMED", createdAt: "2001-01-01", confirmedBy: "x", confirmedAt: "2001-01-01" });
  return settleNo;
};
const account = (payeeNo: string) => payoutAccounts.push({ accountNo: `PAM${++n}`, payeeType: "AGENT", payeeNo,
  bankCode: "ENBD", accountName: `Beneficiary ${payeeNo}`, accountMasked: "****1234", currency: "AED", isDefault: true, status: "ACTIVE" });

beforeEach(() => {
  // 上一条用例没付掉的 2001 年结算单退出可付款范围；未结束的任务撤回
  for (const s of settlements) if (s.period.startsWith("2001") && s.status === "CONFIRMED" && !settlementWithdraw.has(s.settleNo)) s.status = "PAID";
  for (const t of payoutTasks) if (t.status === "PENDING_REVIEW" || t.status === "PENDING_TRANSFER") t.status = "CANCELLED";
});

describe("付款任务", () => {
  it("★ 截止日只纳入到期的结算单，一个商家一笔；没收款账户的列为不能付", () => {
    const a = `AGMA${n}`, b = `AGMB${n}`;
    const s1 = settle(a, "2001-01", 100), s2 = settle(a, "2001-02", 200);
    settle(a, "2001-04", 400);
    settle(b, "2001-01", 50);
    account(a);
    const p = previewPayoutTask("2001-03-31");
    const ia = p.items.find((i) => i.payeeNo === a)!;
    expect(ia.settleNos).toEqual([s1, s2]);
    expect(ia.amount).toBe(300);
    expect(p.items.find((i) => i.payeeNo === b)!.blockedReason).toBe("NO_ACCOUNT");
  });

  it("★★ 全流程：自己复核被拒 → 另一人通过 → 导出 → 一笔失败一笔到账", () => {
    const a = `AGMA${n}`, c = `AGMC${n}`;
    const sa = settle(a, "2001-05", 120), sc = settle(c, "2001-05", 70);
    account(a); account(c);
    const t = createPayoutTask("2001-05-31", "alice");
    expect(t.items).toHaveLength(2);
    expect(() => createPayoutTask("2001-05-31", "alice")).toThrow();
    expect(() => auditPayoutTask(t.taskNo, true, undefined, "alice")).toThrow(/另一位同事/);
    expect(auditPayoutTask(t.taskNo, true, undefined, "bob").status).toBe("PENDING_TRANSFER");
    expect(exportPayoutTask(t.taskNo)).toContain(t.items[0].withdrawNo);

    const wa = t.items.find((i) => i.payeeNo === a)!.withdrawNo, wc = t.items.find((i) => i.payeeNo === c)!.withdrawNo;
    const done = confirmPayoutTask(t.taskNo, { bankBatchRef: "B1", transferDate: "2001-06-05",
      results: [{ withdrawNo: wa, success: true }, { withdrawNo: wc, success: false, failReason: "账号已销户" }] }, "alice");
    expect(done.status).toBe("DONE");
    expect(done.items.find((i) => i.withdrawNo === wa)!.payRef).toMatch(new RegExp(`^${t.taskNo}-`));
    expect(settlements.find((s) => s.settleNo === sa)!.status).toBe("PAID");
    expect(settlementWithdraw.has(sc)).toBe(false);
    expect(settlements.find((s) => s.settleNo === sc)!.status).toBe("CONFIRMED");
  });

  it("★ 驳回整任务撤回，结算单放回；驳回必须填原因", () => {
    const a = `AGMA${n}`;
    const s = settle(a, "2001-08", 90);
    account(a);
    const t = createPayoutTask("2001-08-31", "alice");
    expect(() => auditPayoutTask(t.taskNo, false, "", "bob")).toThrow();
    expect(auditPayoutTask(t.taskNo, false, "金额待核", "bob").status).toBe("CANCELLED");
    expect(settlementWithdraw.has(s)).toBe(false);
  });
});
