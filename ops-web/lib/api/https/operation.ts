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
};
