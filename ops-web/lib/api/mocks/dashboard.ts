// 覆盖范围：认证登录 + 工作台首页统计。
import * as db from "../../mock/db";
import type { DashboardApi } from "../contracts/dashboard";
import { wait } from "./_wait";

export const dashboardMock: DashboardApi = {
  login: (username, role, agentNo) => wait({ token: `mock-${role}`, username, role, agentNo: role === "AGENT" ? (agentNo ?? "AG001") : "" }),
  getDashboard: () => wait(db.dashboard),
};
