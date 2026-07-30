// 信用分调整单测（S2 的防复发机制）。
//
// 背景：风控页长期只有一张只读名单 —— 权限码 user:risk:update 早就定义好了，
// 却没有任何调分动作，「信用分」这一列既改不了也查不到是谁改的。
// 本文件钉住四件事：
//   ① 调分真的改了用户分数（cUsers 是唯一真相），并同步到风控名单
//   ② 上下限在 mock 层强制 —— 越界**抛错**而不是静默截断
//   ③ 每次调分落一条 CreditScoreChange 留痕（before/after/delta/原因/操作人齐全）
//   ④ 风险等级按阈值重算；掉到门槛以下的自动进观察名单，回升**不自动移出**
import { describe, it, expect } from "vitest";
import { CREDIT_SCORE_MIN, CREDIT_SCORE_MAX, RISK_MEDIUM_BELOW, riskLevelOf } from "../../types";
import {
  cUsers, userRisks, creditScoreChanges,
  adjustCreditScore, listCreditScoreChanges, CreditScoreError,
} from "./user";

const scoreOf = (no: string) => cUsers.find((u) => u.cUserNo === no)!.creditScore;
const riskOf = (no: string) => userRisks.find((r) => r.userNo === no);
const changesOf = (no: string) => listCreditScoreChanges({ cUserNo: no, size: 100 }).list;

/** 把某个用户的分数摆到指定值（直接改档案，绕开调分入口——测试夹具允许，业务代码不允许）。 */
function withScore(cUserNo: string, score: number) {
  const u = cUsers.find((x) => x.cUserNo === cUserNo)!;
  u.creditScore = score;
  const r = riskOf(cUserNo);
  if (r) r.creditScore = score;
  return u;
}

const REASON = "单测：风控人工调分";

describe("信用分阈值定义", () => {
  it("等级按阈值划档，与风控名单现有分布一致", () => {
    expect(riskLevelOf(0)).toBe("HIGH");
    expect(riskLevelOf(559)).toBe("HIGH");
    expect(riskLevelOf(560)).toBe("MEDIUM");
    expect(riskLevelOf(RISK_MEDIUM_BELOW - 1)).toBe("MEDIUM");
    expect(riskLevelOf(RISK_MEDIUM_BELOW)).toBe("LOW");
    expect(riskLevelOf(CREDIT_SCORE_MAX)).toBe("LOW");
  });
});

describe("调分真落库并留痕", () => {
  it("减分：用户分数真的降了，风控名单同步，并落一条留痕", () => {
    const no = "U3044";
    withScore(no, 600);
    const before = changesOf(no).length;
    const r = adjustCreditScore(no, { delta: -50, reason: REASON, operatorName: "Sara Ahmed" });

    expect(r.user.creditScore).toBe(550);
    expect(scoreOf(no)).toBe(550); // 档案是唯一真相，真的改了
    expect(riskOf(no)!.creditScore).toBe(550); // 名单是它的投影，同步了
    expect(r.change).toMatchObject({
      cUserNo: no, before: 600, after: 550, delta: -50, reason: REASON, operatorName: "Sara Ahmed",
    });
    expect(r.change.changeNo).toMatch(/^CS\d{4}$/);
    expect(r.change.createdAt).toBeTruthy();
    expect(changesOf(no).length).toBe(before + 1);
    expect(changesOf(no)[0].changeNo).toBe(r.change.changeNo); // 最新在前
  });

  it("加分：delta 为正，分数上调", () => {
    const no = "U3046";
    withScore(no, 600);
    const r = adjustCreditScore(no, { delta: 25, reason: "申诉成立" });
    expect(r.change.delta).toBe(25);
    expect(scoreOf(no)).toBe(625);
    expect(r.change.operatorName).toBe("admin"); // 未传操作人时缺省 admin
  });

  it("多次调分：留痕逐条累积，before/after 首尾相接", () => {
    const no = "U3045";
    withScore(no, 700);
    adjustCreditScore(no, { delta: -30, reason: "逾期一次" });
    adjustCreditScore(no, { delta: -20, reason: "逾期两次" });
    const [last, prev] = changesOf(no); // 最新在前
    expect(prev.after).toBe(last.before);
    expect(last.after).toBe(650);
    expect(scoreOf(no)).toBe(650);
  });
});

describe("上下限在 mock 层强制", () => {
  it("减到负分被拒，分数保持不变、不留痕", () => {
    const no = "U3043";
    withScore(no, 10);
    const before = changesOf(no).length;
    expect(() => adjustCreditScore(no, { delta: -11, reason: REASON })).toThrow(CreditScoreError);
    expect(() => adjustCreditScore(no, { delta: -11, reason: REASON })).toThrow(/超出允许范围/);
    expect(scoreOf(no)).toBe(10); // 没有被静默截断成 0
    expect(changesOf(no).length).toBe(before);
  });

  it("加到超上限被拒", () => {
    const no = "U3043";
    withScore(no, CREDIT_SCORE_MAX - 5);
    expect(() => adjustCreditScore(no, { delta: 6, reason: REASON })).toThrow(/超出允许范围/);
    expect(scoreOf(no)).toBe(CREDIT_SCORE_MAX - 5);
  });

  it("刚好落在边界上是允许的（0 与 1000 都是合法分值）", () => {
    const no = "U3043";
    withScore(no, 10);
    expect(adjustCreditScore(no, { delta: -10, reason: REASON }).change.after).toBe(CREDIT_SCORE_MIN);
    withScore(no, CREDIT_SCORE_MAX - 5);
    expect(adjustCreditScore(no, { delta: 5, reason: REASON }).change.after).toBe(CREDIT_SCORE_MAX);
  });

  it("必填与取值校验：原因必填、0 分值/小数/非数字一律拒绝", () => {
    const no = "U3046";
    withScore(no, 600);
    expect(() => adjustCreditScore(no, { delta: -10, reason: "  " })).toThrow(/必须填写原因/);
    expect(() => adjustCreditScore(no, { delta: 0, reason: REASON })).toThrow(/非 0 的数字/);
    expect(() => adjustCreditScore(no, { delta: Number.NaN, reason: REASON })).toThrow(/非 0 的数字/);
    expect(() => adjustCreditScore(no, { delta: 1.5, reason: REASON })).toThrow(/必须是整数/);
    expect(scoreOf(no)).toBe(600);
  });

  it("用户不存在直接抛错", () => {
    expect(() => adjustCreditScore("U_NOT_EXIST", { delta: -1, reason: REASON })).toThrow(/不存在/);
  });
});

describe("风控等级与观察名单联动", () => {
  it("名单内用户：调分后等级按阈值重算", () => {
    const no = "U3044";
    withScore(no, 700);
    adjustCreditScore(no, { delta: -1, reason: REASON });
    expect(riskOf(no)!.riskLevel).toBe("LOW"); // 699 ≥ 640
    adjustCreditScore(no, { delta: -100, reason: REASON });
    expect(riskOf(no)!.riskLevel).toBe("MEDIUM"); // 599
    adjustCreditScore(no, { delta: -50, reason: REASON });
    expect(riskOf(no)!.riskLevel).toBe("HIGH"); // 549 < 560
  });

  it("名单外用户掉到门槛以下：自动补一条风控记录（进观察名单）", () => {
    const u = cUsers.find((x) => !riskOf(x.cUserNo))!;
    withScore(u.cUserNo, RISK_MEDIUM_BELOW + 5);
    const r = adjustCreditScore(u.cUserNo, { delta: -10, reason: "多次异常订单" });
    expect(r.risk).toBeTruthy();
    expect(r.risk!.userNo).toBe(u.cUserNo);
    expect(r.risk!.riskNo).toMatch(/^RK\d{4}$/);
    expect(r.risk!.creditScore).toBe(RISK_MEDIUM_BELOW - 5);
    expect(r.risk!.riskLevel).toBe("MEDIUM");
    expect(riskOf(u.cUserNo)).toBeTruthy(); // 真的进了名单
  });

  it("名单外用户仍在门槛以上：不进名单（不无端制造风控记录）", () => {
    const u = cUsers.find((x) => !riskOf(x.cUserNo))!;
    withScore(u.cUserNo, 800);
    const r = adjustCreditScore(u.cUserNo, { delta: -10, reason: "轻微违规" });
    expect(r.risk).toBeNull();
    expect(riskOf(u.cUserNo)).toBeUndefined();
  });

  it("分数回升不自动移出名单，只降级为 LOW（名单是审计痕迹，移出需人工）", () => {
    const no = "U3043";
    withScore(no, 500);
    adjustCreditScore(no, { delta: 400, reason: "申诉全部成立" });
    expect(riskOf(no)).toBeTruthy(); // 仍在名单里
    expect(riskOf(no)!.riskLevel).toBe("LOW");
    expect(scoreOf(no)).toBe(900);
  });
});

describe("留痕查询", () => {
  it("按 cUserNo 精确过滤，不串到别人头上", () => {
    withScore("U3045", 700);
    adjustCreditScore("U3045", { delta: -5, reason: "本人的记录" });
    expect(changesOf("U3045").every((c) => c.cUserNo === "U3045")).toBe(true);
    expect(creditScoreChanges.length).toBeGreaterThanOrEqual(changesOf("U3045").length);
  });

  it("关键字覆盖单号/用户/操作人/原因", () => {
    withScore("U3046", 700);
    adjustCreditScore("U3046", { delta: -7, reason: "夜间异常借还", operatorName: "Omar Khan" });
    expect(listCreditScoreChanges({ keyword: "Omar Khan", size: 100 }).list.length).toBeGreaterThan(0);
    expect(listCreditScoreChanges({ keyword: "夜间异常借还", size: 100 }).list.length).toBe(1);
  });
});
