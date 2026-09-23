// 覆盖范围：认证登录 + 工作台首页统计。
import * as db from "../../mock/db";
import type { DashboardApi } from "../contracts/dashboard";
import { wait } from "./_wait";
import { permsOf } from "../../permissions";
import type { Role } from "../../auth";

export const dashboardMock: DashboardApi = {
  /*
   * mock 登录：用**用户名**当角色开关（admin/ops/cs/finance/bd/viewer/agent），
   * 真后端下用户名与角色无关 —— 这里只是离线开发时挑身份的手段。
   * perms 按 BACKEND_ROLE_PERMS 展开：**mock 也走「判权读 perms」那条路**，
   * 否则 mock 与真后端两套判权逻辑，mock 下测不出权限问题。
   */
  login: (realm, identifier, _password) => {
    const guess = identifier.split("@")[0].toUpperCase();
    const role = (realm === "AGENT" ? "AGENT"
      : (["ADMIN", "OPS", "CS", "FINANCE", "BD", "VIEWER"] as const).find((r) => r === guess) ?? "ADMIN");
    const operators = realm === "AGENT"
      ? [{ operatorNo: "AG001", name: "示例代理商甲", isOwner: true, isPrimary: true },
         { operatorNo: "AG002", name: "示例代理商乙", isOwner: false, isPrimary: false }]
      : undefined;
    return wait({
      token: `mock-${role}`, subjectNo: identifier, username: identifier, role,
      perms: permsOf(role as Role),
      operators, currentOperatorNo: operators?.[0].operatorNo,
    });
  },
  // mock 没有服务端会话可吊销，但**必须存在** —— 契约测试要求 mock 与 http 同形，
  // 缺一个方法会让 mock 模式在点登出时直接 TypeError。
  logout: () => wait(undefined as void),
  getDashboard: () => wait(db.dashboard),
};
