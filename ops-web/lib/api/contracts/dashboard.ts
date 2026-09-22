// 覆盖范围：认证登录 + 工作台首页统计。
import type { DashboardStats } from "../../types";

export interface LoginResp { token: string; username: string; role: string; agentNo: string; }

export interface DashboardApi {
  // 认证：登录换后端 token（后端据 token 角色鉴权，不认客户端 X-Roles）
  login(username: string, password: string, role: string, agentNo?: string): Promise<LoginResp>;
  // 工作台
  getDashboard(): Promise<DashboardStats>;
}
