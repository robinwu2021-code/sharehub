// 运营管理域 http 切片。
// ⚠️ 这四个端点**后端尚未实现**（2026-09-22 核对），按 TDD 约定的路径先写好；
// 就绪度登记在 lib/backend-ready.ts，未就绪时页面不会调到这里。
import type { OperationApi } from "../contracts/operation";
import { client } from "../http-client";

export const operationHttp: OperationApi = {
  getOperationOverview: (q) => client.get("/api/ops/operation/overview", q),
  getSiteStats: (siteNo, q) => client.get(`/api/ops/sites/${siteNo}/stats`, q),
  pauseSite: (siteNo, reason) => client.post(`/api/ops/sites/${siteNo}/pause`, { reason }),
  resumeSite: (siteNo) => client.post(`/api/ops/sites/${siteNo}/resume`, {}),

  listPriceAdjustments: (q) => client.get("/api/trade/price-adjustments", q),
  savePriceAdjustment: (x) => client.post(x.adjustNo ? `/api/trade/price-adjustments/${x.adjustNo}` : "/api/trade/price-adjustments", x),
  cancelPriceAdjustment: (no, reason) => client.post(`/api/trade/price-adjustments/${no}/cancel`, { reason }),
  revertPriceAdjustment: (no) => client.post(`/api/trade/price-adjustments/${no}/revert`, {}),
  retryPriceAdjustment: (no) => client.post(`/api/trade/price-adjustments/${no}/retry`, {}),

  listSiteSharing: (q) => client.get("/api/trade/site-sharing", q),
  getSiteSharingStats: () => client.get("/api/trade/site-sharing/stats"),
  listPayeeSharing: (q) => client.get("/api/trade/payee-sharing", q),
};
