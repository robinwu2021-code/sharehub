// 覆盖范围：认证登录 + 工作台首页统计。
import { client } from "../http-client";
import type { DashboardApi } from "../contracts/dashboard";

export const dashboardHttp: DashboardApi = {
  // AGENT 走手机号 + 验证码：后端字段名是 phone/otp（identifier 对 STAFF 仍是 username）。
  // 两条路只差一个 otp —— 合成一个端点，免得前端先判断「这是哪种登录」，
  // 那个判断迟早和后端不一致。
  login: (realm, identifier, password, otp) =>
    client.post("/api/auth/login", otp
      ? { realm, phone: identifier, otp }
      : { realm, identifier, username: identifier, password }),
  sendLoginOtp: (phone) => client.post("/api/auth/otp", { phone }),
  changePassword: (oldPassword, newPassword) => client.post("/api/auth/password", { oldPassword, newPassword }),
  listOperators: () => client.get("/api/auth/operators"),
  switchOperator: (agentNo) => client.post(`/api/auth/operators/${agentNo}/switch`, {}),
  me: () => client.get("/api/auth/me"),
  getMenus: () => client.get("/api/auth/menus"),
  logout: () => client.post("/api/auth/logout"),
  getDashboard: () => client.get("/api/ops/dashboard"),
  getOpsFlowMetrics: (q) => client.get("/api/ops/ops-flow-metrics", q),
};
