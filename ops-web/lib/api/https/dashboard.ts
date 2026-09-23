// 覆盖范围：认证登录 + 工作台首页统计。
import { client } from "../http-client";
import type { DashboardApi } from "../contracts/dashboard";

export const dashboardHttp: DashboardApi = {
  login: (realm, identifier, password) => client.post("/api/auth/login", { realm, identifier, password }),
  logout: () => client.post("/api/auth/logout"),
  getDashboard: () => client.get("/api/ops/dashboard"),
};
