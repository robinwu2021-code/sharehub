// 流程实例 · 历史 · 对账 · 统计：与审批同源，口径互相对得上（后端 O2-10 的约定）。
import { beforeEach, describe, expect, it } from "vitest";
import { setMockOperatorPerms } from "./mock-auth";
import { getApproval, queryApprovals, resetApprovalDb, withdraw } from "./mock-approvals";
import { instanceHistory, processStats, queryInstances, subjectHistory } from "./mock-instances";

const NOW = new Date("2026-09-27T12:00:00").getTime();
beforeEach(() => { setMockOperatorPerms(["*"]); resetApprovalDb(NOW); });

describe("流程实例", () => {
  it("每张审批单一个实例；按流程 / 未结筛选与统计同口径", () => {
    const all = queryInstances({ page: 1, size: 200 });
    expect(all.total).toBe(queryApprovals({ page: 1, size: 200 }).total);
    for (const s of processStats({})) {
      const list = queryInstances({ processCode: s.process.code, page: 1, size: 200 });
      const open = queryInstances({ processCode: s.process.code, open: true, page: 1, size: 200 });
      expect(s.total, s.process.code).toBe(list.total);
      expect(s.open, s.process.code).toBe(open.total);
      expect(Object.values(s.byCategory).reduce((a, b) => a + (b ?? 0), 0)).toBe(s.total);
    }
  });

  it("在审批里撤回，实例立刻回到草稿、历史多一条系统迁移", () => {
    const mine = queryApprovals({ status: ["PENDING"], page: 1, size: 200 }).list.map((r) => getApproval(r.approvalNo)).find((v) => v.mine.canWithdraw)!;
    const no = `IN${mine.approvalNo.slice(2)}`;
    const before = instanceHistory(no).length;
    withdraw(mine.approvalNo, NOW);
    const row = queryInstances({ subjectRef: mine.subject.ref, page: 1, size: 10 }).list[0];
    expect(row).toMatchObject({ instanceNo: no, status: { code: "DRAFT" }, statusCategory: "NEW" });
    const h = instanceHistory(no);
    expect(h.length).toBeGreaterThan(before);
    // 词表与后端一致：实例迁移代号自定义、名称在 actionName / toName；审批办结与任务取消都有条目
    expect(h.find((x) => x.entityKind === "INSTANCE" && x.action === "withdraw")).toMatchObject({ to: "DRAFT", toName: "草稿", actorText: "system" });
    expect(h.find((x) => x.entityKind === "APPROVAL" && x.action === "completed")).toMatchObject({ from: "PENDING", to: "WITHDRAWN" });
    expect(h.find((x) => x.entityKind === "TASK" && x.action === "cancelled")).toMatchObject({ to: "CANCELLED" });
    expect(h.every((x) => x.action === x.action.toLowerCase())).toBe(true);
    expect(h.map((x) => x.seq)).toEqual(h.map((_, i) => i + 1));
  });

  it("对账：按业务对象取全部实例与审批；没有就是空，不报错", () => {
    const a = queryApprovals({ page: 1, size: 1 }).list[0];
    const s = subjectHistory(a.subject.type, a.subject.ref);
    expect(s.total).toBeGreaterThanOrEqual(1);
    expect(s.instances[0].approvals[0].approvalNo).toBe(a.approvalNo);
    expect(subjectHistory("contract", "NOPE")).toMatchObject({ total: 0, instances: [] });
  });

  it("统计要 flow:process:manage；实例要 flow:approval:read", () => {
    setMockOperatorPerms(["flow:approval:read"]);
    expect(queryInstances({ page: 1, size: 5 }).total).toBeGreaterThan(0);
    expect(() => processStats({})).toThrow(/flow:process:manage/);
    setMockOperatorPerms(["flow:todo:read"]);
    expect(() => queryInstances({ page: 1, size: 5 })).toThrow(/flow:approval:read/);
  });
});
