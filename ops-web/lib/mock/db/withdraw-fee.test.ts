// 提现手续费口径（S7）：费率/封顶只有「系统设置 · 业务规则」一处来源。
//
// 页面上原先写死 0.6%，同时又写着「以业务规则为唯一来源」——两处并存就是口径分叉：
// 规则页改了费率，提现页照旧按 0.6% 放款，对账永远差钱。本测试钉住三件事：
//  ① 口径函数只认业务规则的 feeRate/feeCap（改费率必须改出不同的费）；
//  ② 种子数据与规则自洽（fee === min(amount × feeRate, feeCap)），不是各写一个数；
//  ③ 展示口径：未审批的按现行费率实时算，已审批的按落库值——事后调费率不能改写历史放款额。
import { describe, expect, it } from "vitest";
import { computeWithdrawFee, withdrawFeeOf, withdrawNetOf, WITHDRAW_FEE_PENDING } from "../../types";
import type { Withdrawal } from "../../types";
import { withdrawals } from "./finance";
import { bizRules } from "./system";

const RULE = { feeRate: bizRules.withdraw.feeRate, feeCap: bizRules.withdraw.feeCap };
const wd = (over: Partial<Withdrawal>): Withdrawal => ({
  withdrawNo: "WD9999", payeeName: "测试对象", amount: 1000, fee: 6, currency: "AED",
  status: "AUDIT", appliedAt: "2026-07-01T00:00:00.000Z",
  auditorName: null, auditedAt: null, rejectReason: null, ...over,
});

describe("手续费口径来自业务规则", () => {
  it("费率变了，算出来的手续费就变（不再是写死的 0.6%）", () => {
    expect(computeWithdrawFee(1000, { feeRate: 0.006, feeCap: 25 })).toBe(6);
    expect(computeWithdrawFee(1000, { feeRate: 0.012, feeCap: 25 })).toBe(12);
    expect(computeWithdrawFee(1000, { feeRate: 0, feeCap: 25 })).toBe(0);
  });

  it("封顶生效：大额提现的手续费不超过 feeCap", () => {
    expect(computeWithdrawFee(100_000, { feeRate: 0.006, feeCap: 25 })).toBe(25);
    // 封顶调高，同一笔就该扣更多——封顶也必须取自配置，不能另存
    expect(computeWithdrawFee(100_000, { feeRate: 0.006, feeCap: 400 })).toBe(400);
  });

  it("没有下限：业务规则里没有「最低手续费」这个字段，就不许凭空加一个", () => {
    expect(computeWithdrawFee(10, { feeRate: 0.006, feeCap: 25 })).toBe(0.06);
  });
});

describe("种子提现单与业务规则自洽", () => {
  it("每一笔的 fee 都等于按现行规则算出来的费", () => {
    expect(withdrawals.length).toBeGreaterThan(0);
    for (const w of withdrawals) {
      expect(w.fee).toBe(computeWithdrawFee(w.amount, RULE));
    }
  });

  it("实发金额 = 金额 − 手续费，且不为负", () => {
    for (const w of withdrawals) {
      expect(withdrawNetOf(w, RULE)).toBe(Number((w.amount - w.fee).toFixed(2)));
      expect(withdrawNetOf(w, RULE)).toBeGreaterThan(0);
    }
  });
});

describe("展示口径：未审批实时算 / 已审批按落库值", () => {
  it("未审批（APPLY/AUDIT）跟随现行费率", () => {
    for (const status of WITHDRAW_FEE_PENDING) {
      const w = wd({ status, amount: 1000, fee: 6 });
      expect(withdrawFeeOf(w, { feeRate: 0.02, feeCap: 100 })).toBe(20);
      expect(withdrawNetOf(w, { feeRate: 0.02, feeCap: 100 })).toBe(980);
    }
  });

  it("已审批（PAYING/PAID/FAILED）保持落库值，改费率不改写历史放款额", () => {
    for (const status of ["PAYING", "PAID", "FAILED"] as const) {
      const w = wd({ status, amount: 1000, fee: 6 });
      expect(withdrawFeeOf(w, { feeRate: 0.02, feeCap: 100 })).toBe(6);
      expect(withdrawNetOf(w, { feeRate: 0.02, feeCap: 100 })).toBe(994);
    }
  });

  it("规则取不到时退回落库值，不猜一个费率出来", () => {
    const w = wd({ status: "AUDIT", amount: 1000, fee: 6 });
    expect(withdrawFeeOf(w, undefined)).toBe(6);
    expect(withdrawNetOf(w, undefined)).toBe(994);
  });
});
