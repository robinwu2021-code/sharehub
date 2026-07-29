// 覆盖范围：计费定价 —— 价格方案、差异化定价规则、分时定价规则。
// 端点前缀：/api/trade/**
import { client } from "../http-client";
import type { PricingApi } from "../contracts/pricing";
import type { PageQ } from "../query";

export const pricingHttp: PricingApi = {
  listPricePlans: (q?: PageQ) => client.get("/api/trade/price-plans", q),

  // 定价扩展
  listPricingDiffs: (q?: PageQ) => client.get("/api/trade/pricing-diffs", q),
  listPricingSchedules: (q?: PageQ) => client.get("/api/trade/pricing-schedules", q),
  savePricePlan: (x) => client.post(x.planNo ? `/api/trade/price-plans/${x.planNo}` : "/api/trade/price-plans", x),
  savePricingDiff: (x) => client.post(x.ruleNo ? `/api/trade/pricing-diffs/${x.ruleNo}` : "/api/trade/pricing-diffs", x),
  savePricingSchedule: (x) => client.post(x.ruleNo ? `/api/trade/pricing-schedules/${x.ruleNo}` : "/api/trade/pricing-schedules", x),
};
