"use client";

import { create } from "zustand";
import { persist } from "zustand/middleware";

// 运营端内部角色（单运营方，无平台超管）。AGENT=代理端受限视图。
export type Role = "ADMIN" | "OPS" | "CS" | "FINANCE" | "BD" | "VIEWER" | "AGENT";

/** 账号池。**只选走哪套登录，不决定权限**（v4/06 §2.6）。 */
export type Realm = "STAFF" | "AGENT" | "";

/** 一个人在某个运营主体下的成员关系（ADR-030）。仅 AGENT realm 有。 */
export interface Membership {
  operatorNo: string;
  name: string;
  isOwner: boolean;
  isPrimary: boolean;
}

/**
 * persist 的键。
 *
 * ⚠️ **v2 是有意换的**（2026-09-23，D6a）：store 从「role 判权」改成「perms 判权」，
 * 老浏览器里残留的 v1 结构会被读成「有 token、没 perms」＝**登录态存在但什么都看不见**，
 * 而且不报错。换 key 让旧结构自然失效，代价是所有人重登一次 —— 比写 migrate 简单且无残留。
 */
const STORAGE_KEY = "pb-ops-auth-v2";

export interface AuthState {
  /** 账号池；"" = 未登录。 */
  realm: Realm;
  /** 员工 employee_no / 代理 account_no。 */
  subjectNo: string;
  username: string;
  /**
   * 角色。**只用于展示与菜单分组**（`NavSection.portalFor`），**不参与判权**。
   *
   * 判权一律看 {@link perms}。任何新写的 `role === "…"` 分支都是错的 ——
   * 多主体下同一个人在不同主体的权限不同，而 role 表达不了这个差异。
   */
  role: Role | "";
  /**
   * **后端下发的权限码**（`GET /api/auth/me` 的 `perms`）。判权唯一依据。
   *
   * `["*"]` = 超管通配。空数组 = 零权限（**不是「还没加载」**：未登录本来就该什么都看不见）。
   *
   * 它此前一个字节都没被读过 —— `can()` 查的是前端自己写死的角色表，
   * 而后端一直在下发另一份。两套各自演化到漂了 29 处：26 处前端放行·后端 403，
   * 3 处后端允许·界面上没有入口。
   */
  perms: string[];

  // —— 多主体（仅 AGENT realm，ADR-030）——
  memberships: Membership[];
  /**
   * 本次会话生效的主体。
   *
   * **不再随请求头发出**（2026-09-24）：主体写在 token 里，切换靠
   * `POST /api/auth/operators/{agentNo}/switch` 换发。这里留它是给界面用的
   * （顶栏显示、按主体收敛列表、queryKey），不是传给服务端的凭据。
   */
  currentOperatorNo: string;
  /**
   * 切换世代号。参与 react-query 的 queryKey，也用于丢弃在途响应。
   *
   * **只清缓存不够**：切换瞬间已发出的请求带的是**旧**主体的头，
   * 回来时会写进新主体的缓存 —— 页面照常打开、数据是另一家的，没有任何报错。
   */
  operatorGen: number;

  token: string;

  login: (v: {
    realm: Realm; subjectNo: string; username: string; role: Role | "";
    token: string; perms: string[];
    memberships?: Membership[]; currentOperatorNo?: string;
  }) => void;
  /** 切主体。**只改 store**；重拉身份、清缓存、路由兜底由调用方做（D6b）。 */
  /**
   * 切换当前运营主体。`session` 是**服务端换发的新会话** —— 见实现里的注释：
   * 只改 operatorNo 而不换 token，会出现「界面切了、数据还是上一家」且不报错。
   */
  switchOperator: (operatorNo: string, session: { token: string; perms: string[]; username: string }) => void;
  /** 覆盖身份（切主体后重拉 `/me` 的结果落这里）。 */
  refreshIdentity: (v: { role: Role | ""; perms: string[] }) => void;
  /** **只清本地状态**，不发请求。吊销服务端会话用 `signOut()`（lib/api/session）。 */
  logout: () => void;
  loggedIn: () => boolean;
}

const EMPTY = {
  realm: "" as Realm, subjectNo: "", username: "", role: "" as Role | "",
  perms: [] as string[], memberships: [] as Membership[],
  currentOperatorNo: "", token: "",
};

export const useAuth = create<AuthState>()(
  persist(
    (set, get) => ({
      ...EMPTY,
      operatorGen: 0,
      login: (v) => set({
        ...EMPTY, ...v,
        memberships: v.memberships ?? [],
        currentOperatorNo: v.currentOperatorNo
          ?? v.memberships?.find((m) => m.isPrimary)?.operatorNo ?? "",
        operatorGen: get().operatorGen + 1,
      }),
      switchOperator: (operatorNo, session) => {
        // 前端也挡一层，但**这一层不算数** —— 真正的越权闸在服务端：
        // 它校验目标主体落在会话 memberships 内，不在就拒（ADR-030 §4.2 / AgentIdentityPort）。
        if (!get().memberships.some((m) => m.operatorNo === operatorNo)) return;
        /*
         * **必须换 token，不能只改这个字段**（2026-09-24）。
         * agentNo 是数据范围的锚点，服务端按它做 AGENT 硬过滤 ——
         * 只改本地字段的话，旧 token 仍拿着**旧主体的范围**去查数据，
         * 界面显示已切换、拿回来的却还是上一家的数据，而且没有任何报错。
         * 所以服务端换发、老 token 当场吊销，这里把新会话整体落下来。
         */
        set({
          currentOperatorNo: operatorNo,
          token: session.token,
          perms: session.perms,
          username: session.username,
          operatorGen: get().operatorGen + 1,
        });
      },
      refreshIdentity: (v) => set({ role: v.role, perms: v.perms }),
      // perms / memberships 也要清：漏掉任一个，登出后 localStorage 里还留着
      // 上一个人的权限，而 http-client 是直接读 localStorage 的。
      logout: () => set({ ...EMPTY, operatorGen: get().operatorGen + 1 }),
      loggedIn: () => !!get().token,
    }),
    { name: STORAGE_KEY },
  ),
);

// 供非 React 层（lib/api）读取当前身份 → 拼请求头。
export function currentAuth() {
  if (typeof window === "undefined") return null;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    return JSON.parse(raw).state as AuthState;
  } catch {
    return null;
  }
}
