// 覆盖范围：计费定价 —— 收费方案、适用范围（取价唯一依据）、分时倍率。
// 端点前缀：/api/trade/**
import { client } from "../http-client";
import type { PricingApi } from "../contracts/pricing";
import type { PageQ, ArchiveQ } from "../query";

export const pricingHttp: PricingApi = {
  listPricePlans: (q?: ArchiveQ) => client.get("/api/trade/price-plans", q),

  // 适用范围：取价的唯一依据（ADR-028 / V49）。三个端点 2026-09-23 随取价引擎重写一并补齐。
  listPlanScopes: (planNo) => client.get(`/api/trade/price-plans/${planNo}/scopes`),
  savePlanScope: (planNo, x) => client.post(`/api/trade/price-plans/${planNo}/scopes`, x),
  removePlanScope: (planNo, id) => client.post(`/api/trade/price-plans/${planNo}/scopes/${id}/remove`, {}),
  listPricingSchedules: (q?: PageQ) => client.get("/api/trade/pricing-schedules", q),
  savePricePlan: (x) => client.post(x.planNo ? `/api/trade/price-plans/${x.planNo}` : "/api/trade/price-plans", x),
  savePricingSchedule: (x) => client.post(x.ruleNo ? `/api/trade/pricing-schedules/${x.ruleNo}` : "/api/trade/pricing-schedules", x),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  // ⚠️ 后端缺口：这两个端点后端尚未实现（scripts/check-backend-parity.py 列为 pricing 的 2 个缺口）。
  archivePricePlan: (no) => client.post(`/api/trade/price-plans/${no}/archive`, {}),
  unarchivePricePlan: (no) => client.post(`/api/trade/price-plans/${no}/unarchive`, {}),
};
