"use client";

import { create } from "zustand";
import { persist } from "zustand/middleware";

// 运营端内部角色（单运营方，无平台超管）。AGENT=代理端受限视图。
export type Role = "ADMIN" | "OPS" | "CS" | "FINANCE" | "BD" | "VIEWER" | "AGENT";

// tenantNo 仅作后端/DB 兼容层（默认 MAIN），产品层不体现租户（ADR-011）。
const MAIN_TENANT = "MAIN";

export interface AuthState {
  username: string;
  // 未登录时为空串，**不是某个角色**。曾经默认写死 `"ADMIN"`：
  // 后端有红线（只认 token 反查权限、不信客户端 X-Roles），所以它不构成服务端越权，
  // 但它让"没有身份"这件事在前端无法表达 —— 任何新写的 `role === "ADMIN"` 判断
  // 都会对未登录状态返回真。`can()` / `canModule()` 对空值是 fail-closed 的，
  // 所以空串是唯一正确的默认。
  role: Role | "";
  tenantNo: string; // 后端兼容：随请求头透传，MVP 恒为 MAIN
  agentNo: string;  // AGENT 角色的数据范围（自己 agent_no），非代理为空
  token: string;
  login: (v: { username: string; role: Role; token: string; agentNo?: string }) => void;
  /** **只清本地状态**，不发请求。吊销服务端会话用 `signOut()`（lib/api/session）。 */
  logout: () => void;
  loggedIn: () => boolean;
}

export const useAuth = create<AuthState>()(
  persist(
    (set, get) => ({
      username: "",
      role: "",
      tenantNo: MAIN_TENANT,
      agentNo: "",
      token: "",
      login: (v) => set({ ...v, tenantNo: MAIN_TENANT, agentNo: v.agentNo ?? "" }),
      // role 也要清：漏掉它，登出后 localStorage 里还留着上一个人的角色，
      // 而 http-client 是直接读 localStorage 拼 X-Roles 的。
      logout: () => set({ username: "", role: "", token: "", agentNo: "" }),
      loggedIn: () => !!get().token,
    }),
    { name: "pb-ops-auth" },
  ),
);

// 供非 React 层（lib/api）读取当前身份 → 拼 AuthHeaders。
export function currentAuth() {
  if (typeof window === "undefined") return null;
  try {
    const raw = localStorage.getItem("pb-ops-auth");
    if (!raw) return null;
    return JSON.parse(raw).state as AuthState;
  } catch {
    return null;
  }
}
