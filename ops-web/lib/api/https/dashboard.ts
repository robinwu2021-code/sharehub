// 覆盖范围：认证登录 + 工作台首页统计。
import { client } from "../http-client";
import type { DashboardApi, LoginResp, OperatorRef } from "../contracts/dashboard";

/**
 * 后端 AuthController 的主体行是 `OperatorMembership{agentNo, agentName, isOwner, isPrimary}`，
 * 登录 / 切换出参用 `agentNo` 表示当前主体；前端契约是 `OperatorRef{operatorNo, name}` 与 `currentOperatorNo`。
 * 在这里一处翻译 —— 2026-10-29 真后端联调发现：不翻的话顶栏主体号显示「-」、主体切换器一项都列不出，
 * 而 mock 下一切正常（mock 直接按前端契约造数）。
 */
interface MembershipRaw { agentNo: string; agentName: string; isOwner?: boolean; isPrimary?: boolean; }
const toRef = (m: MembershipRaw): OperatorRef => ({
  operatorNo: m.agentNo, name: m.agentName, isOwner: !!m.isOwner, isPrimary: !!m.isPrimary,
});
const toLogin = (r: LoginResp & { agentNo?: string; operators?: unknown[] }): LoginResp => ({
  ...r,
  operators: r.operators ? (r.operators as MembershipRaw[]).map(toRef) : undefined,
  currentOperatorNo: r.currentOperatorNo ?? (r.agentNo || undefined),
});

export const dashboardHttp: DashboardApi = {
  // AGENT 走「邮箱或手机号 + 验证码」（商家一期 B2，R8）：标识放 identifier，由后端判是哪种。
  // 两条路只差一个 otp —— 合成一个端点，免得前端先判断「这是哪种登录」，
  // 那个判断迟早和后端不一致。
  login: async (realm, identifier, password, otp) =>
    toLogin(await client.post("/api/auth/login", otp
      ? { realm, identifier, otp }
      : { realm, identifier, username: identifier, password })),
  sendLoginOtp: (identifier) => client.post("/api/auth/otp", { identifier }),
  changePassword: (oldPassword, newPassword) => client.post("/api/auth/password", { oldPassword, newPassword }),
  listOperators: async () => ((await client.get("/api/auth/operators")) as MembershipRaw[]).map(toRef),
  switchOperator: async (agentNo) => toLogin(await client.post(`/api/auth/operators/${agentNo}/switch`, {})),
  me: () => client.get("/api/auth/me"),
  getMenus: () => client.get("/api/auth/menus"),
  logout: () => client.post("/api/auth/logout"),
  getDashboard: () => client.get("/api/ops/dashboard"),
  getOpsFlowMetrics: (q) => client.get("/api/ops/ops-flow-metrics", q),
  getRevenueSummary: (q) => client.get("/api/ops/merchant-revenue/summary", q),
  getRevenueTrend: (q) => client.get("/api/ops/merchant-revenue/trend", q),
  getRevenueByDevice: (q) => client.get("/api/ops/merchant-revenue/by-device", q),
  getRevenueAlerts: () => client.get("/api/ops/merchant-revenue/alerts"),
};
