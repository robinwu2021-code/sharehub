import { describe, it, expect, beforeEach } from "vitest";
import { withdrawals, payWithdrawal, auditWithdrawal, applyWithdrawal, WithdrawalError } from "./finance";
import { bizRules } from "./system";
import { canPayWithdrawal, payReceiptError } from "../../types";
import type { Withdrawal } from "../../types";

/**
 * 打款回执（⑮）的 mock 层回归。
 *
 * 钉的是**与后端 `WithdrawalServiceImpl.pay()` 的一致性**：mock 放行而后端拒绝的话，
 * 页面在 mock 下看着是通的，切后端当场崩 —— 这类分叉只有把两边的规则逐条对齐才防得住。
 */
const mk = (no: string, status: Withdrawal["status"]): Withdrawal => {
  const w: Withdrawal = {
    withdrawNo: no, payeeName: "测试对象", amount: 1000, fee: 6, currency: "AED",
    status, appliedAt: new Date().toISOString(),
    auditorName: "Sara Ahmed", auditedAt: new Date().toISOString(), rejectReason: null,
    payChannel: null, payRef: null, payerName: null, failReason: null, paidAt: null,
  };
  withdrawals.push(w);
  return w;
};

let seq = 0;
const freshNo = () => `WD-TEST-${Date.now()}-${seq++}`;

beforeEach(() => {
  // 用例之间互不干扰：只清自己造的行，别人的种子数据不动
  for (let i = withdrawals.length - 1; i >= 0; i--) {
    if (withdrawals[i].withdrawNo.startsWith("WD-TEST-")) withdrawals.splice(i, 1);
  }
});

describe("状态机：只有出款在途能登记回执", () => {
  it("PAYING 可以登记，落到 PAID 并记下到账时间", () => {
    const w = mk(freshNo(), "PAYING");
    const r = payWithdrawal(w.withdrawNo, { success: true, channel: "MANUAL", payRef: "REF-1" });
    expect(r.status).toBe("PAID");
    expect(r.paidAt).toBeTruthy();
    expect(r.payChannel).toBe("MANUAL");
  });

  it.each(["APPLY", "AUDIT"] as const)("%s 不能直接登记到账——那等于绕开审批", (status) => {
    const w = mk(freshNo(), status);
    expect(() => payWithdrawal(w.withdrawNo, { success: true, channel: "MANUAL", payRef: "R" }))
      .toThrow(WithdrawalError);
    expect(w.status).toBe(status);   // 被拒之后不能把单子改坏
  });

  it.each(["PAID", "FAILED"] as const)("%s 是终态，不能再记一次", (status) => {
    const w = mk(freshNo(), status);
    expect(() => payWithdrawal(w.withdrawNo, { success: true, channel: "MANUAL", payRef: "R" }))
      .toThrow(/只有出款在途/);
  });

  it("canPayWithdrawal 与 mock 的判据是同一个", () => {
    // 页面按钮用它决定显不显示。两边分叉 = 按钮点得动但提交必失败
    expect(canPayWithdrawal("PAYING")).toBe(true);
    (["APPLY", "AUDIT", "PAID", "FAILED"] as const).forEach((s) =>
      expect(canPayWithdrawal(s)).toBe(false));
  });
});

describe("证据：说了钱出去了，就得拿得出凭据", () => {
  it("登记到账必须有渠道流水号", () => {
    const w = mk(freshNo(), "PAYING");
    expect(() => payWithdrawal(w.withdrawNo, { success: true, channel: "MANUAL" }))
      .toThrow(/流水号/);
    expect(w.status).toBe("PAYING");
  });

  it("登记失败必须有原因", () => {
    const w = mk(freshNo(), "PAYING");
    expect(() => payWithdrawal(w.withdrawNo, { success: false, channel: "MANUAL" }))
      .toThrow(/失败原因/);
  });

  it("空白字符不算填了", () => {
    const w = mk(freshNo(), "PAYING");
    expect(() => payWithdrawal(w.withdrawNo, { success: true, channel: "MANUAL", payRef: "   " }))
      .toThrow(/流水号/);
  });

  it("payReceiptError 是页面与 mock 共用的那一份判据", () => {
    expect(payReceiptError({ success: true, channel: "MANUAL", payRef: "R" })).toBeNull();
    expect(payReceiptError({ success: true, channel: "MANUAL" })).toMatch(/流水号/);
    expect(payReceiptError({ success: false, channel: "MANUAL", failReason: "销户" })).toBeNull();
    expect(payReceiptError({ success: false, channel: "MANUAL" })).toMatch(/失败原因/);
  });
});

describe("失败原因与审批驳回原因分列", () => {
  it("打款失败落 failReason，不污染 rejectReason", () => {
    const w = mk(freshNo(), "PAYING");
    const r = payWithdrawal(w.withdrawNo, { success: false, channel: "MANUAL", failReason: "收款账号已销户" });
    expect(r.status).toBe("FAILED");
    expect(r.failReason).toBe("收款账号已销户");
    expect(r.rejectReason).toBeNull();
  });

  it("审批驳回落 rejectReason，不产生 failReason", () => {
    const w = mk(freshNo(), "APPLY");
    const r = auditWithdrawal(w.withdrawNo, false, "材料不全");
    expect(r.status).toBe("FAILED");
    expect(r.rejectReason).toBe("材料不全");
    expect(r.failReason).toBeNull();
  });
});

describe("幂等：同一笔渠道流水不能记两次", () => {
  it("记到另一张单上会被拒——否则对账时平白多一笔钱", () => {
    const a = mk(freshNo(), "PAYING");
    const b = mk(freshNo(), "PAYING");
    payWithdrawal(a.withdrawNo, { success: true, channel: "MANUAL", payRef: "REF-DUP" });
    expect(() => payWithdrawal(b.withdrawNo, { success: true, channel: "MANUAL", payRef: "REF-DUP" }))
      .toThrow(/已登记在另一张提现单上/);
    expect(b.status).toBe("PAYING");
  });

  it("不同渠道的同号流水互不冲突——唯一键是(渠道, 流水号)", () => {
    const a = mk(freshNo(), "PAYING");
    const b = mk(freshNo(), "PAYING");
    payWithdrawal(a.withdrawNo, { success: true, channel: "MANUAL", payRef: "REF-SAME" });
    expect(() => payWithdrawal(b.withdrawNo, { success: true, channel: "NEARPAY", payRef: "REF-SAME" }))
      .not.toThrow();
  });

  it("失败不填流水号时，多条失败之间不会互相撞键", () => {
    // pay_ref 为 NULL 的行本就不该互相排斥（与 share_record 那个缺陷方向相反）
    const a = mk(freshNo(), "PAYING");
    const b = mk(freshNo(), "PAYING");
    payWithdrawal(a.withdrawNo, { success: false, channel: "MANUAL", failReason: "销户" });
    expect(() => payWithdrawal(b.withdrawNo, { success: false, channel: "MANUAL", failReason: "拒收" }))
      .not.toThrow();
  });
});

describe("留痕", () => {
  it("登记人与审批人分开存，查得出是不是同一个人放的款", () => {
    const w = mk(freshNo(), "PAYING");
    const r = payWithdrawal(w.withdrawNo, { success: true, channel: "NEARPAY", payRef: "REF-X" }, "Omar Khan");
    expect(r.auditorName).toBe("Sara Ahmed");
    expect(r.payerName).toBe("Omar Khan");
  });

  it("种子里 FAILED 两种来源都有，否则页面上分列这件事看不出来", () => {
    const failed = withdrawals.filter((w) => w.status === "FAILED" && !w.withdrawNo.startsWith("WD-TEST-"));
    expect(failed.some((w) => w.rejectReason)).toBe(true);
    expect(failed.some((w) => w.failReason)).toBe(true);
  });
});

describe("代理自助申请提现", () => {
  const req = (amount: number) => ({
    payeeType: "AGENT" as const, payeeNo: "AG001", payeeName: "North Hub",
    amount, currency: "AED",
  });

  it("落 APPLY，且审批四件套全空——这张单还没人看过", () => {
    const w = applyWithdrawal(req(1000));
    expect(w.status).toBe("APPLY");
    expect(w.auditorName).toBeNull();
    expect(w.auditedAt).toBeNull();
    expect(w.paidAt).toBeNull();
    withdrawals.splice(withdrawals.indexOf(w), 1);
  });

  it("手续费服务端算，入参里根本没有这个字段", () => {
    const w = applyWithdrawal(req(1000));
    // 传不进去：WithdrawApplyPayload 里没有 fee。这里验的是它确实按业务规则算出来了
    expect(w.fee).toBeGreaterThan(0);
    expect(w.fee).toBeLessThan(w.amount);
    withdrawals.splice(withdrawals.indexOf(w), 1);
  });

  it("低于最低提现额被拒", () => {
    const below = bizRules.withdraw.minAmount - 1;
    expect(() => applyWithdrawal(req(below))).toThrow(/最低提现额/);
  });

  it("金额为 0 或负数被拒", () => {
    expect(() => applyWithdrawal(req(0))).toThrow(/大于 0/);
    expect(() => applyWithdrawal(req(-100))).toThrow(/大于 0/);
  });

  it("新单排在最前——追加到末尾的话，提交完在第一页看不见自己刚提的那张", () => {
    const w = applyWithdrawal(req(1000));
    expect(withdrawals[0].withdrawNo).toBe(w.withdrawNo);
    withdrawals.splice(0, 1);
  });

  it("单号不与既有的撞", () => {
    const a = applyWithdrawal(req(1000));
    const b = applyWithdrawal(req(1000));
    expect(a.withdrawNo).not.toBe(b.withdrawNo);
    expect(withdrawals.filter((x) => x.withdrawNo === a.withdrawNo)).toHaveLength(1);
    withdrawals.splice(0, 2);
  });

  it("走得完整条链：申请 → 审批 → 打款回执", () => {
    const w = applyWithdrawal(req(1000));
    expect(auditWithdrawal(w.withdrawNo, true).status).toBe("PAYING");
    const paid = payWithdrawal(w.withdrawNo, { success: true, channel: "MANUAL", payRef: `E2E-${Date.now()}` });
    expect(paid.status).toBe("PAID");
    withdrawals.splice(withdrawals.indexOf(paid), 1);
  });
});
