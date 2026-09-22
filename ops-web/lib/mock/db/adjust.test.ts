import { describe, it, expect, beforeEach } from "vitest";
import { priceAdjustments, tickAdjustments, savePriceAdjustment, cancelPriceAdjustment, revertPriceAdjustment, retryPriceAdjustment } from "./adjust";
import { pricePlans } from "./pricing";
import type { PriceAdjustment } from "../../types";

/** 用一个独立的方案与调价单跑，避免动到种子数据影响其它用例。 */
const PLAN = "PPT01";
function reset() {
  const i = pricePlans.findIndex((p) => p.planNo === PLAN);
  const plan = { planNo: PLAN, name: "测试方案", freeMinutes: 5, unitMinutes: 30, unitPrice: 3, capDaily: 30, buyoutPrice: 60, currency: "AED", scope: "测试", status: "ACTIVE" as const, archivedAt: null };
  if (i >= 0) pricePlans[i] = plan; else pricePlans.push(plan);
  for (let k = priceAdjustments.length - 1; k >= 0; k--) if (priceAdjustments[k].planNo === PLAN) priceAdjustments.splice(k, 1);
  return plan;
}
const at = (ms: number) => new Date(Date.now() + ms).toISOString();
const MIN = 60_000;

describe("预约调价 mock：惰性执行", () => {
  beforeEach(reset);

  it("到点自动生效，并记录变更前快照", () => {
    const a = savePriceAdjustment({ planNo: PLAN, name: "涨价", patch: { unitPrice: 5 }, effectiveAt: at(10 * MIN), reason: "测试" } as Partial<PriceAdjustment>);
    expect(a.status).toBe("SCHEDULED");
    expect(pricePlans.find((p) => p.planNo === PLAN)!.unitPrice).toBe(3);

    tickAdjustments(new Date(Date.now() + 11 * MIN));
    const after = priceAdjustments.find((x) => x.adjustNo === a.adjustNo)!;
    expect(after.status).toBe("APPLIED");
    expect(after.beforeSnapshot).toEqual({ unitPrice: 3 });
    expect(pricePlans.find((p) => p.planNo === PLAN)!.unitPrice).toBe(5);
  });

  it("重复 tick 不会重复改价（幂等）", () => {
    const a = savePriceAdjustment({ planNo: PLAN, name: "涨价", patch: { unitPrice: 5 }, effectiveAt: at(10 * MIN), reason: "测试" } as Partial<PriceAdjustment>);
    const later = new Date(Date.now() + 11 * MIN);
    expect(tickAdjustments(later)).toContain(a.adjustNo);
    expect(tickAdjustments(later)).not.toContain(a.adjustNo);
    expect(priceAdjustments.find((x) => x.adjustNo === a.adjustNo)!.beforeSnapshot).toEqual({ unitPrice: 3 });
  });

  it("到恢复时间自动还原；错过多次 tick 也能补执行（服务重启场景）", () => {
    const a = savePriceAdjustment({ planNo: PLAN, name: "限时", patch: { unitPrice: 9 }, effectiveAt: at(10 * MIN), revertAt: at(20 * MIN), reason: "测试" } as Partial<PriceAdjustment>);
    // 直接跳到恢复时间之后：生效与恢复在同一次 tick 里补齐
    tickAdjustments(new Date(Date.now() + 30 * MIN));
    const after = priceAdjustments.find((x) => x.adjustNo === a.adjustNo)!;
    expect(after.status).toBe("REVERTED");
    expect(pricePlans.find((p) => p.planNo === PLAN)!.unitPrice).toBe(3);
  });

  it("调价期间方案被人工改过 → 恢复转失败，不覆盖人工修改", () => {
    const a = savePriceAdjustment({ planNo: PLAN, name: "限时", patch: { unitPrice: 9 }, effectiveAt: at(10 * MIN), revertAt: at(20 * MIN), reason: "测试" } as Partial<PriceAdjustment>);
    tickAdjustments(new Date(Date.now() + 11 * MIN));
    pricePlans.find((p) => p.planNo === PLAN)!.unitPrice = 7; // 人工改价
    tickAdjustments(new Date(Date.now() + 21 * MIN));
    const after = priceAdjustments.find((x) => x.adjustNo === a.adjustNo)!;
    expect(after.status).toBe("FAILED");
    expect(after.failReason).toContain("人工修改");
    expect(pricePlans.find((p) => p.planNo === PLAN)!.unitPrice).toBe(7);
  });

  it("目标方案停用后，待生效的调价自动撤销", () => {
    const a = savePriceAdjustment({ planNo: PLAN, name: "涨价", patch: { unitPrice: 5 }, effectiveAt: at(10 * MIN), reason: "测试" } as Partial<PriceAdjustment>);
    pricePlans.find((p) => p.planNo === PLAN)!.status = "DISABLED";
    tickAdjustments(new Date(Date.now() + 11 * MIN));
    const after = priceAdjustments.find((x) => x.adjustNo === a.adjustNo)!;
    expect(after.status).toBe("CANCELLED");
    expect(after.failReason).toContain("已停用或归档");
  });

  it("撤销只能在待生效；撤销要填原因", () => {
    const a = savePriceAdjustment({ planNo: PLAN, name: "涨价", patch: { unitPrice: 5 }, effectiveAt: at(10 * MIN), reason: "测试" } as Partial<PriceAdjustment>);
    expect(() => cancelPriceAdjustment(a.adjustNo, "")).toThrow(/原因/);
    cancelPriceAdjustment(a.adjustNo, "活动取消");
    expect(priceAdjustments.find((x) => x.adjustNo === a.adjustNo)!.status).toBe("CANCELLED");
    expect(() => cancelPriceAdjustment(a.adjustNo, "再撤一次")).toThrow();
  });

  it("提前恢复：立即还原；已恢复的不能再恢复", () => {
    const a = savePriceAdjustment({ planNo: PLAN, name: "限时", patch: { unitPrice: 9 }, effectiveAt: at(10 * MIN), revertAt: at(99 * MIN), reason: "测试" } as Partial<PriceAdjustment>);
    tickAdjustments(new Date(Date.now() + 11 * MIN));
    revertPriceAdjustment(a.adjustNo);
    expect(pricePlans.find((p) => p.planNo === PLAN)!.unitPrice).toBe(3);
    expect(() => revertPriceAdjustment(a.adjustNo)).toThrow(/已生效/);
  });

  it("重试：失败的恢复可以再试一次", () => {
    const a = savePriceAdjustment({ planNo: PLAN, name: "限时", patch: { unitPrice: 9 }, effectiveAt: at(10 * MIN), revertAt: at(20 * MIN), reason: "测试" } as Partial<PriceAdjustment>);
    tickAdjustments(new Date(Date.now() + 11 * MIN));
    pricePlans.find((p) => p.planNo === PLAN)!.unitPrice = 7;
    tickAdjustments(new Date(Date.now() + 21 * MIN));
    expect(priceAdjustments.find((x) => x.adjustNo === a.adjustNo)!.status).toBe("FAILED");
    // 人工把方案改回调价值后重试，恢复就能成功
    pricePlans.find((p) => p.planNo === PLAN)!.unitPrice = 9;
    retryPriceAdjustment(a.adjustNo);
    expect(priceAdjustments.find((x) => x.adjustNo === a.adjustNo)!.status).toBe("REVERTED");
    expect(pricePlans.find((p) => p.planNo === PLAN)!.unitPrice).toBe(3);
  });

  it("时间窗重叠的新建被拒", () => {
    savePriceAdjustment({ planNo: PLAN, name: "A", patch: { unitPrice: 5 }, effectiveAt: at(60 * MIN), revertAt: at(120 * MIN), reason: "测试" } as Partial<PriceAdjustment>);
    expect(() => savePriceAdjustment({ planNo: PLAN, name: "B", patch: { unitPrice: 6 }, effectiveAt: at(90 * MIN), reason: "测试" } as Partial<PriceAdjustment>)).toThrow(/重叠/);
  });
});
