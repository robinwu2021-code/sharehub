// 覆盖范围：认证登录 + 工作台首页统计。
import * as db from "../../mock/db";
import type { DashboardApi } from "../contracts/dashboard";
import { wait } from "./_wait";

export const dashboardMock: DashboardApi = {
  login: (username, _password, role, agentNo) => wait({ token: `mock-${role}`, username, role, agentNo: role === "AGENT" ? (agentNo ?? "AG001") : "" }),
  // mock 没有服务端会话可吊销，但**必须存在** —— 契约测试要求 mock 与 http 同形，
  // 缺一个方法会让 mock 模式在点登出时直接 TypeError。
  logout: () => wait(undefined as void),
  getDashboard: () => wait(db.dashboard),
};
