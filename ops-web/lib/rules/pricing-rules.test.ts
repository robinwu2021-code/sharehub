import { describe, it, expect } from "vitest";
import {
  planSummary, simulate, validatePricePlan, validateAdjustment, canTransitAdjust, adjustSummary,
} from "./pricing-rules";
import type { PricePlan, PriceAdjustment } from "../types";

const plan = (x: Partial<PricePlan> = {}): PricePlan => ({
  planNo: "PP001", name: "标准", freeMinutes: 5, unitMinutes: 30, unitPrice: 3,
  capDaily: 30, buyoutPrice: 60, currency: "AED", scope: "默认", status: "ACTIVE", archivedAt: null, ...x,
});

describe("计费摘要", () => {
  it("一句话说清怎么收钱；没有免费时长/封顶时不硬凑", () => {
    // money() 用 Intl 格式化，币种与数字之间是不换行空格（U+00A0），断言里统一成普通空格再比
    const norm = (s: string) => s.replace(/\u00a0/g, " ");
    expect(norm(planSummary(plan()))).toBe("前 5 分钟免费，每 30 分钟 AED 3.00，日封顶 AED 30.00，买断 AED 60.00");
    expect(norm(planSummary(plan({ freeMinutes: 0, capDaily: 0, buyoutPrice: 0 })))).toBe("每 30 分钟 AED 3.00");
  });
});

describe("试算", () => {
  it("不足一个计费单位按一个算", () => {
    // 20 分钟：免 5，剩 15 分钟 → 1 个单位 → 3
    expect(simulate(plan(), 20).total).toBe(3);
    // 35 分钟：剩 30 → 1 个单位；36 分钟：剩 31 → 2 个单位
    expect(simulate(plan(), 35).total).toBe(3);
    expect(simulate(plan(), 36).total).toBe(6);
  });

  it("全程免费时长内不收费", () => {
    const r = simulate(plan(), 5);
    expect(r.total).toBe(0);
  });

  it("日封顶按自然天数累加", () => {
    // 24 小时：免 5，剩 1435 → 48 个单位 × 3 = 144 → 封顶 30
    const oneDay = simulate(plan({ buyoutPrice: 0 }), 24 * 60);
    expect(oneDay.total).toBe(30);
    // 2 天：封顶 60
    expect(simulate(plan({ buyoutPrice: 0 }), 48 * 60).total).toBe(60);
  });

  it("达到买断价即买断，不再继续计费", () => {
    const r = simulate(plan(), 72 * 60); // 3 天 → 封顶 90 → 超过买断 60
    expect(r.total).toBe(60);
    expect(r.buyout).toBe(true);
    expect(r.segments.some((s) => s.label === "买断")).toBe(true);
  });

  it("时段倍率作用在计费段上", () => {
    expect(simulate(plan({ capDaily: 0, buyoutPrice: 0 }), 36, 1.5).total).toBe(9); // 2 单位 × 3 × 1.5
  });

  it("每段都给出可核对的说明", () => {
    const r = simulate(plan(), 100);
    expect(r.segments[0]).toMatchObject({ label: "免费时长", amount: 0 });
    expect(r.segments[1].detail).toContain("95 分钟 ÷ 30 分钟 = 4 个计费单位");
  });
});

describe("方案校验", () => {
  it("名称必填且不重名；数值边界；买断价不能低于日封顶", () => {
    const all = [plan(), plan({ planNo: "PP002", name: "机场" })];
    expect(validatePricePlan({ ...plan(), name: "" }, plan(), all).join()).toContain("名称");
    expect(validatePricePlan({ ...plan(), planNo: "PP003", name: "机场" }, undefined, all).join()).toContain("已存在");
    expect(validatePricePlan({ ...plan(), unitMinutes: 0 }, plan(), all).join()).toContain("计费单位");
    expect(validatePricePlan({ ...plan(), buyoutPrice: 20 }, plan(), all).join()).toContain("买断价不能低于日封顶");
    expect(validatePricePlan(plan(), plan(), all)).toEqual([]);
  });
});

describe("预约调价", () => {
  const now = new Date("2026-09-23T12:00:00Z");
  const adj = (x: Partial<PriceAdjustment> = {}): PriceAdjustment => ({
    adjustNo: "PA001", planNo: "PP001", planName: "标准", name: "国庆涨价",
    patch: { unitPrice: 5 }, beforeSnapshot: null,
    effectiveAt: "2026-10-01T00:00:00Z", revertAt: "2026-10-08T00:00:00Z", reason: "节假日",
    status: "SCHEDULED", appliedAt: null, revertedAt: null, failReason: null,
    createdBy: "admin", createdAt: "2026-09-23T10:00:00Z", ...x,
  });

  it("状态机：待生效 → 已生效 → 已恢复；撤销只能在待生效", () => {
    expect(canTransitAdjust("SCHEDULED", "APPLIED")).toBe(true);
    expect(canTransitAdjust("SCHEDULED", "CANCELLED")).toBe(true);
    expect(canTransitAdjust("APPLIED", "CANCELLED")).toBe(false);
    expect(canTransitAdjust("REVERTED", "APPLIED")).toBe(false);
  });

  it("生效时间必须比现在晚至少 5 分钟；恢复时间要晚于生效时间", () => {
    const ctx = { siblings: [], plan: plan(), now };
    expect(validateAdjustment(adj({ effectiveAt: "2026-09-23T12:02:00Z" }), undefined, ctx).join()).toContain("至少要比现在晚");
    expect(validateAdjustment(adj({ revertAt: "2026-09-30T00:00:00Z" }), undefined, ctx).join()).toContain("恢复时间必须晚于生效时间");
    expect(validateAdjustment(adj(), undefined, ctx)).toEqual([]);
  });

  it("至少要改一个字段：与方案现值相同的改动不算", () => {
    const ctx = { siblings: [], plan: plan(), now };
    expect(validateAdjustment(adj({ patch: { unitPrice: 3 } }), undefined, ctx).join()).toContain("至少要改动一个字段");
    expect(validateAdjustment(adj({ patch: {} }), undefined, ctx).join()).toContain("至少要改动一个字段");
  });

  it("同一方案的时间窗不能重叠", () => {
    const existing = adj({ adjustNo: "PA000", effectiveAt: "2026-10-05T00:00:00Z", revertAt: "2026-10-12T00:00:00Z" });
    const ctx = { siblings: [existing], plan: plan(), now };
    expect(validateAdjustment(adj(), undefined, ctx).join()).toContain("时间窗重叠");
    // 错开就没问题
    expect(validateAdjustment(adj({ effectiveAt: "2026-10-13T00:00:00Z", revertAt: null }), undefined, ctx)).toEqual([]);
    // 已撤销的不占用时间窗
    const cancelled = { ...existing, status: "CANCELLED" as const };
    expect(validateAdjustment(adj(), undefined, { ...ctx, siblings: [cancelled] })).toEqual([]);
  });

  it("已生效的调价单不能再改；停用的方案不能安排调价", () => {
    const ctx = { siblings: [], plan: plan(), now };
    expect(validateAdjustment(adj(), adj({ status: "APPLIED" }), ctx).join()).toContain("不能再修改");
    expect(validateAdjustment(adj(), undefined, { ...ctx, plan: plan({ status: "DISABLED" }) }).join()).toContain("已停用");
  });

  it("调整摘要只列真正变化的字段", () => {
    expect(adjustSummary({ unitPrice: 5, capDaily: 30 }, plan()).replace(/\u00a0/g, " ")).toBe("单价 AED 3.00 → AED 5.00");
    expect(adjustSummary({ unitPrice: 3 }, plan())).toBe("（没有任何字段变化）");
  });
});
