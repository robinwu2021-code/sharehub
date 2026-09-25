// 覆盖范围：认证登录 + 工作台首页统计。
import type { DashboardStats, OpsFlowMetrics } from "../../types";

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

/**
 * `GET /api/auth/me` 的出参。
 *
 * ⚠️ 它**不是**登录响应的子集：没有 `token` / `operators` / `subjectNo`。
 * 只拿它来刷新 perms，别拿它重建身份 —— 缺的那几样会被写成空。
 */
export interface MeResp {
  authenticated: boolean;
  username?: string;
  role?: string;
  agentNo?: string;
  /** 后端**当场重算**的权限码（口径 B），不是登录那一刻的快照。 */
  perms?: string[];
}

/**
 * 服务端菜单节点。字段与 `NavSection`/`NavLeaf` **一一对应**（后端 V67 起刻意如此）——
 * 少一个前端就得为它留一份本地数据，那就又回到「两处真源」。
 *
 * ⚠️ 没有 `soon`：它由 `pageReady(page, useMock)` 算，取决于这份产物连的是
 * mock 还是真后端 —— **构建的属性，不是菜单的属性**，所以库里没有这一列。
 */
export interface MenuNode {
  menuNo: string;
  parentNo: string | null;
  name: string;
  nameEn: string | null;
  nameAr: string | null;
  /** MENU（分组）/ ITEM（叶子）。词表由后端 V68 钉在列注释上。 */
  type: "MENU" | "ITEM";
  path: string | null;
  icon: string | null;
  /** L2 分组标题（仅叶子）。 */
  group: string | null;
  sort: number;
  perm: string | null;
  phase: number | null;
  ready: boolean;
  module: string | null;
  modules: string[];
  match: string[];
  pinBottom: boolean;
  portalFor: string[];
  children: MenuNode[];
}

export interface DashboardApi {
  /**
   * 当前登录人**可见的**菜单树（服务端已按权限剪枝，前端拿到就渲染）。
   *
   * 规则见后端 MenuService：门户排他 · 叶子按 perm · section 看有没有可见叶子。
   * 前端只再施加两样：phase/ready 的分期门禁、soon 的就绪度 ——
   * 那两样是产品分期与构建属性，不是权限。
   */
  getMenus(): Promise<MenuNode[]>;
  /**
   * 登录。
   *
   * - `realm` **只选账号池，不是权限**（v4/06 §2.6）：STAFF=员工、AGENT=合作伙伴。
   * - `identifier` 是**一个**字段：手机号**或**邮箱，含 `@` 走邮箱。
   *   分两个字段的话，前端要先判断用户输的是什么，那个判断迟早和后端不一致。
   * - **不再传 role** —— 角色由账号决定，客户端说了不算。
   */
  login(realm: "STAFF" | "AGENT", identifier: string, password: string, otp?: string): Promise<LoginResp>;
  /**
   * 代理端登录发码（匿名）。
   *
   * **查无此号也返回成功** —— 「存在」与「不存在」一旦响应不同，
   * 这个接口就成了代理商手机号枚举器。代价是输错号的人会等一条永远不到的短信。
   *
   * @returns dev-mode 下回显验证码供联调；生产走短信通道，接口不回传
   */
  sendLoginOtp(phone: string): Promise<{ ok: boolean; code?: string }>;
  /** 我的运营主体列表（ADR-030）。STAFF 会话返回空表 —— 运营端没有「我的主体」这个概念。 */
  listOperators(): Promise<OperatorRef[]>;
  /**
   * 切换当前运营主体：**换发 token**，不是改会话里的一个字段。
   *
   * 因为 `agentNo` 是数据范围的锚点。原地改字段的话，旧 token 仍在别处使用时
   * 会拿着旧范围继续跑；换发之后老 token 立刻吊销，没有两个范围并存的窗口。
   */
  switchOperator(agentNo: string): Promise<LoginResp>;
  /**
   * 当前会话的身份与**当场重算的权限码**。
   *
   * 后端每个请求都会比对会话戳与全局 `PermVersion`，变了就按角色重建会话
   * （`StaffTokenAuthFilter` 口径 B）—— 所以这里拿到的是**现在**的权限，
   * 不是登录那一刻的快照。前端据此收敛入口，见 `lib/perms-sync.ts`。
   *
   * 令牌失效时后端返回 **401**（不是 200 + `authenticated:false`），
   * 由 http-client 统一转成会话失效跳转。
   */
  me(): Promise<MeResp>;
  // 登出：**让后端真正吊销 token**。只清本地状态等于没登出 —— 令牌在服务端一直有效到过期，
  // 共用电脑上点完"退出"走人，下一个人拿 localStorage 里的旧令牌仍能调接口。
  logout(): Promise<void>;
  // 工作台
  getDashboard(): Promise<DashboardStats>;
  /**
   * 运营核心流程指标（`dashboard:overview:read`）。窗口 `[from, to)`，YYYY-MM-DD；缺省最近 30 天。
   * 比率为 null = 分母为 0，**不是 0%**。
   */
  getOpsFlowMetrics(q?: { from?: string; to?: string }): Promise<OpsFlowMetrics>;
}
