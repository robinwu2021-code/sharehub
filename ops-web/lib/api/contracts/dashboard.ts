// 覆盖范围：认证登录 + 工作台首页统计。
import type { DashboardStats } from "../../types";

/** 一个人在某运营主体下的成员关系（ADR-030）。仅 AGENT realm 返回。 */
export interface OperatorRef { operatorNo: string; name: string; isOwner: boolean; isPrimary: boolean; }

export interface LoginResp {
  token: string;
  subjectNo: string;
  username: string;
  role: string;
  /** **后端下发的权限码**，判权唯一依据（D6a）。`["*"]` = 超管通配。 */
  perms: string[];
  /** 仅 AGENT realm：这个人可进的全部主体。 */
  operators?: OperatorRef[];
  /** 默认进哪个主体（取 isPrimary 那一行）。 */
  currentOperatorNo?: string;
}

export interface DashboardApi {
  /**
   * 登录。
   *
   * - `realm` **只选账号池，不是权限**（v4/06 §2.6）：STAFF=员工、AGENT=合作伙伴。
   * - `identifier` 是**一个**字段：手机号**或**邮箱，含 `@` 走邮箱。
   *   分两个字段的话，前端要先判断用户输的是什么，那个判断迟早和后端不一致。
   * - **不再传 role** —— 角色由账号决定，客户端说了不算。
   */
  login(realm: "STAFF" | "AGENT", identifier: string, password: string): Promise<LoginResp>;
  // 登出：**让后端真正吊销 token**。只清本地状态等于没登出 —— 令牌在服务端一直有效到过期，
  // 共用电脑上点完"退出"走人，下一个人拿 localStorage 里的旧令牌仍能调接口。
  logout(): Promise<void>;
  // 工作台
  getDashboard(): Promise<DashboardStats>;
}
