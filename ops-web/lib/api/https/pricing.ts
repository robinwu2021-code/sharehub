// 覆盖范围：计费定价 —— 价格方案、差异化定价规则、分时定价规则。
// 端点前缀：/api/trade/**
import { client } from "../http-client";
import type { PricingApi } from "../contracts/pricing";
import type { PageQ, ArchiveQ } from "../query";

export const pricingHttp: PricingApi = {
  listPricePlans: (q?: ArchiveQ) => client.get("/api/trade/price-plans", q),

  // 定价扩展
  listPricingDiffs: (q?: PageQ) => client.get("/api/trade/pricing-diffs", q),
  listPricingSchedules: (q?: PageQ) => client.get("/api/trade/pricing-schedules", q),
  savePricePlan: (x) => client.post(x.planNo ? `/api/trade/price-plans/${x.planNo}` : "/api/trade/price-plans", x),
  savePricingDiff: (x) => client.post(x.ruleNo ? `/api/trade/pricing-diffs/${x.ruleNo}` : "/api/trade/pricing-diffs", x),
  savePricingSchedule: (x) => client.post(x.ruleNo ? `/api/trade/pricing-schedules/${x.ruleNo}` : "/api/trade/pricing-schedules", x),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  archivePricePlan: (no) => client.post(`/api/trade/price-plans/${no}/archive`, {}),
  unarchivePricePlan: (no) => client.post(`/api/trade/price-plans/${no}/unarchive`, {}),
};
