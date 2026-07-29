// 覆盖范围：认证登录 + 工作台首页统计。
import { client } from "../http-client";
import type { DashboardApi } from "../contracts/dashboard";

export const dashboardHttp: DashboardApi = {
  login: (username, role, agentNo) => client.post("/api/auth/login", { username, role, agentNo }),
  getDashboard: () => client.get("/api/ops/dashboard"),
};
