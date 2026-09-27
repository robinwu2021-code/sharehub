// 审批与审核队列的规则 = 对后端的期望。
import { beforeEach, describe, expect, it } from "vitest";
import { setMockOperatorPerms } from "./mock-auth";
import {
  claimTask, completeTask, countApprovals, decide, getApproval, myClaimed, queryApprovals, queueSummaries,
  queueTasksOf, releaseTask, resetApprovalDb, withdraw, CLAIM_TIMEOUT_MS,
} from "./mock-approvals";

const NOW = new Date("2026-09-27T12:00:00").getTime();
beforeEach(() => { setMockOperatorPerms(["*"]); resetApprovalDb(NOW); });

describe("审批", () => {
  it("状态页签的角标与各自列表条数一致", () => {
    const c = countApprovals();
    for (const s of ["PENDING", "APPROVED", "REJECTED", "WITHDRAWN"] as const) {
      expect(queryApprovals({ status: [s], page: 1, size: 200 }).total, s).toBe(c[s]);
    }
  });

  it("通过后进入条件步：分成 > 30% → 财务会签", () => {
    const a = getApproval("AP2609270001");
    const v = decide(a.approvalNo, a.mine.taskNo!, "APPROVE", undefined, NOW);
    expect(v.status).toBe("PENDING");
    expect(v.currentStep?.name).toBe("财务会签");
  });

  it("跨步互斥：我审过运营步，就不能再审财务会签", () => {
    const a = getApproval("AP2609270001");
    const v = decide(a.approvalNo, a.mine.taskNo!, "APPROVE", undefined, NOW);
    expect(v.mine.canDecide).toBe(false);
    expect(v.mine.whyNot).toMatch(/跨步互斥/);
    expect(() => decide(v.approvalNo, v.mine.taskNo!, "APPROVE", undefined, NOW)).toThrow(/跨步互斥/);
  });

  it("旧任务号提交 → 请刷新（他人已处理）", () => {
    const a = getApproval("AP2609270001");
    decide(a.approvalNo, a.mine.taskNo!, "APPROVE", undefined, NOW);
    expect(() => decide(a.approvalNo, a.mine.taskNo!, "APPROVE", undefined, NOW)).toThrow(/刷新/);
  });

  it("驳回必须填原因；驳回后本轮记下原因，历史轮次不被覆盖", () => {
    const a = getApproval("AP2609270008");
    expect(() => decide(a.approvalNo, a.mine.taskNo!, "REJECT", "  ", NOW)).toThrow(/原因/);
    const v = decide(a.approvalNo, a.mine.taskNo!, "REJECT", "收款账户与合同主体不一致", NOW);
    expect(v.status).toBe("REJECTED");
    expect(v.rounds.at(-1)?.comment).toBe("收款账户与合同主体不一致");
    const c = getApproval("AP2609270001");
    expect(c.rounds.map((r) => r.round)).toEqual([1, 2]);
  });

  it("提交人不能审批自己的单，但能撤回；非提交人不能撤回", () => {
    const mine = getApproval("AP2609270015");
    expect(mine.mine.canDecide).toBe(false);
    expect(mine.mine.whyNot).toMatch(/提交人/);
    expect(() => decide(mine.approvalNo, mine.mine.taskNo!, "APPROVE", undefined, NOW)).toThrow(/提交人/);
    expect(() => withdraw("AP2609270008", NOW)).toThrow(/只有提交人/);
    expect(withdraw(mine.approvalNo, NOW).status).toBe("WITHDRAWN");
  });
});

describe("审核队列", () => {
  it("队列角标 = 未领取的数量", () => {
    const s = queueSummaries(NOW).find((q) => q.code === "merchant")!;
    const all = queueTasksOf("merchant", 1, 200, NOW).list;
    expect(s.unclaimed).toBe(all.filter((t) => !t.claimedBy).length);
  });

  it("领取超时 30 分钟读时自动放回", () => {
    const later = NOW + CLAIM_TIMEOUT_MS;
    expect(myClaimed(NOW)).toHaveLength(1);
    expect(myClaimed(later)).toHaveLength(0);
  });

  it("领取后才能决定；被他人领着的不能抢", () => {
    const list = queueTasksOf("merchant", 1, 50, NOW).list;
    const free = list.find((t) => !t.claimedBy)!;
    const taken = list.find((t) => t.claimedBy)!;
    expect(() => completeTask(free.taskNo, "APPROVE", undefined, NOW)).toThrow(/领取后/);
    expect(() => claimTask(taken.taskNo, NOW)).toThrow(/已被/);
    claimTask(free.taskNo, NOW);
    expect(() => completeTask(free.taskNo, "REJECT", "", NOW)).toThrow(/原因/);
    completeTask(free.taskNo, "APPROVE", undefined, NOW);
    expect(queueTasksOf("merchant", 1, 200, NOW).list.map((t) => t.taskNo)).not.toContain(free.taskNo);
  });

  it("放回后回到队列、角标 +1", () => {
    const before = queueSummaries(NOW).find((q) => q.code === "merchant")!.unclaimed;
    releaseTask(myClaimed(NOW)[0].taskNo);
    expect(queueSummaries(NOW).find((q) => q.code === "merchant")!.unclaimed).toBe(before + 1);
  });
});
