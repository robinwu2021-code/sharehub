// 计费域：计费模板 pricePlans / 场景差异化定价 pricingDiffs / 时段策略 pricingSchedules。
import type { PricePlan, PricingDiff, PricingSchedule, PageQuery } from "../../types";
import { LOCS, p } from "./internal";
import { paginate, kwHit, upsert, nextNo } from "./helpers";

export const pricePlans: PricePlan[] = [
  { planNo: "PP001", name: "标准（默认）", freeMinutes: 5, unitMinutes: 30, unitPrice: 3, capDaily: 30, capTotal: 60, currency: "AED", scope: "默认", status: "ACTIVE" },
  { planNo: "PP002", name: "机场高价", freeMinutes: 3, unitMinutes: 30, unitPrice: 5, capDaily: 50, capTotal: 99, currency: "AED", scope: "机场点位", status: "ACTIVE" },
  { planNo: "PP003", name: "商场优惠", freeMinutes: 10, unitMinutes: 60, unitPrice: 2, capDaily: 20, capTotal: 49, currency: "AED", scope: "商场点位", status: "ACTIVE" },
  { planNo: "PP004", name: "旧活动价", freeMinutes: 15, unitMinutes: 30, unitPrice: 2, capDaily: 20, capTotal: 40, currency: "AED", scope: "活动", status: "DISABLED" },
];

export const pricingDiffs: PricingDiff[] = Array.from({ length: 12 }, (_, i) => ({
  ruleNo: `PD${400 + i}`, scene: p(["机场", "商场", "餐饮", "地铁", "写字楼"], i), locationName: p(LOCS, i),
  freeMins: p([3, 5, 10], i), unitPrice: p([2, 3, 5], i), dayCap: p([20, 30, 50], i),
  priority: (i % 3) + 1, currency: "AED",
}));
export const pricingSchedules: PricingSchedule[] = Array.from({ length: 12 }, (_, i) => ({
  ruleNo: `PS${500 + i}`, name: p(["周末上浮", "节假日上浮", "夜间优惠", "斋月特惠", "早高峰"], i),
  period: p(["周六-周日", "公共假日", "22:00-06:00", "斋月全月", "07:00-09:00"], i),
  multiplier: Number((0.8 + (i % 5) * 0.15).toFixed(2)), active: i % 6 !== 0,
}));

export const listPricingDiffs = (q: PageQuery = {}) => paginate(pricingDiffs, q.page, q.size, (x) => kwHit(q.keyword, x.ruleNo, x.scene, x.locationName));
export const listPricingSchedules = (q: PageQuery = {}) => paginate(pricingSchedules, q.page, q.size, (x) => kwHit(q.keyword, x.ruleNo, x.name, x.period));

export const savePricePlan = (x: Partial<PricePlan>) => upsert(pricePlans, x, "planNo", () => nextNo("PP", pricePlans));
export const savePricingDiff = (x: Partial<PricingDiff>) => upsert(pricingDiffs, x, "ruleNo", () => nextNo("PD", pricingDiffs));
export const savePricingSchedule = (x: Partial<PricingSchedule>) => upsert(pricingSchedules, x, "ruleNo", () => nextNo("PS", pricingSchedules));
