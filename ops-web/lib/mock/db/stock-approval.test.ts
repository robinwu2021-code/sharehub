import { afterEach, beforeEach, expect, it } from "vitest";
import { mockApi as api } from "@/lib/api/mock";
import * as stock from "./stock";
import * as approval from "@/lib/flow/mock-approvals";
import { ME } from "@/lib/flow/mock-db";
import { setMockOperatorPerms } from "@/lib/flow/mock-auth";

const actor = ME.ref;
beforeEach(() => { setMockOperatorPerms(["*"]); approval.resetApprovalDb(); });
afterEach(() => { ME.ref = actor; setMockOperatorPerms(null); });
it("direct count approval cannot bypass unified approval", async () => {
  const d = stock.openStockCount("WH001");
  await api.submitStockCount(d.count.countNo);
  await expect(api.approveStockCount(d.count.countNo)).rejects.toThrow();
  await expect(api.postStockCount(d.count.countNo)).rejects.toThrow();
  expect(stock.getStockCount(d.count.countNo).count.status).toBe("SUBMITTED");
});
it("count submission appears in unified approvals and a different reviewer posts it", async () => {
  const d = stock.openStockCount("WH001");
  const sent = await api.submitStockCount(d.count.countNo);
  const row = approval.queryApprovals({ page: 1, size: 200 }).list.find((r) => r.subject.ref === d.count.countNo);
  expect(row).toBeDefined();
  expect((sent as typeof sent & { review?: { approvalNo: string } }).review?.approvalNo).toBe(row!.approvalNo);
  let view = approval.getApproval(row!.approvalNo);
  expect(() => approval.decide(row!.approvalNo, view.mine.taskNo!, "APPROVE", undefined)).toThrow();
  ME.ref = "different.inventory.reviewer";
  view = approval.getApproval(row!.approvalNo);
  approval.decide(row!.approvalNo, view.mine.taskNo!, "APPROVE", undefined);
  expect(stock.getStockCount(d.count.countNo).count.status).toBe("POSTED");
});
it("withdrawing through unified approval reopens the original count", async () => {
  const d = stock.openStockCount("WH001");
  await api.submitStockCount(d.count.countNo);
  const row = approval.queryApprovals({ page: 1, size: 200 }).list.find((r) => r.subject.ref === d.count.countNo);
  expect(row).toBeDefined();
  approval.withdraw(row!.approvalNo);
  expect(stock.getStockCount(d.count.countNo).count.status).toBe("OPEN");
});
