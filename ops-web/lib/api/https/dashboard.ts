// 覆盖范围：认证登录 + 工作台首页统计。
import { client } from "../http-client";
import type { DashboardApi } from "../contracts/dashboard";

export const dashboardHttp: DashboardApi = {
  // AGENT 走「邮箱或手机号 + 验证码」（商家一期 B2，R8）：标识放 identifier，由后端判是哪种。
  // 两条路只差一个 otp —— 合成一个端点，免得前端先判断「这是哪种登录」，
  // 那个判断迟早和后端不一致。
  login: (realm, identifier, password, otp) =>
    client.post("/api/auth/login", otp
      ? { realm, identifier, otp }
      : { realm, identifier, username: identifier, password }),
  sendLoginOtp: (identifier) => client.post("/api/auth/otp", { identifier }),
  changePassword: (oldPassword, newPassword) => client.post("/api/auth/password", { oldPassword, newPassword }),
  listOperators: () => client.get("/api/auth/operators"),
  switchOperator: (agentNo) => client.post(`/api/auth/operators/${agentNo}/switch`, {}),
  me: () => client.get("/api/auth/me"),
  getMenus: () => client.get("/api/auth/menus"),
  logout: () => client.post("/api/auth/logout"),
  getDashboard: () => client.get("/api/ops/dashboard"),
  getOpsFlowMetrics: (q) => client.get("/api/ops/ops-flow-metrics", q),
  getRevenueSummary: (q) => client.get("/api/ops/merchant-revenue/summary", q),
  getRevenueTrend: (q) => client.get("/api/ops/merchant-revenue/trend", q),
  getRevenueByDevice: (q) => client.get("/api/ops/merchant-revenue/by-device", q),
};
