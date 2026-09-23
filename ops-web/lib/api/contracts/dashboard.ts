// 覆盖范围：认证登录 + 工作台首页统计。
import type { DashboardStats } from "../../types";

export interface LoginResp { token: string; username: string; role: string; agentNo: string; }

export interface DashboardApi {
  // 认证：登录换后端 token（后端据 token 角色鉴权，不认客户端 X-Roles）
  login(username: string, password: string, role: string, agentNo?: string): Promise<LoginResp>;
  // 登出：**让后端真正吊销 token**。只清本地状态等于没登出 —— 令牌在服务端一直有效到过期，
  // 共用电脑上点完"退出"走人，下一个人拿 localStorage 里的旧令牌仍能调接口。
  logout(): Promise<void>;
  // 工作台
  getDashboard(): Promise<DashboardStats>;
}
