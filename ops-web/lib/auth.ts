"use client";

import { create } from "zustand";
import { persist } from "zustand/middleware";

// 运营端内部角色（单运营方，无平台超管）。AGENT=代理端受限视图。
export type Role = "ADMIN" | "OPS" | "CS" | "FINANCE" | "BD" | "VIEWER" | "AGENT";

// tenantNo 仅作后端/DB 兼容层（默认 MAIN），产品层不体现租户（ADR-011）。
const MAIN_TENANT = "MAIN";

export interface AuthState {
  username: string;
  role: Role;
  tenantNo: string; // 后端兼容：随请求头透传，MVP 恒为 MAIN
  agentNo: string;  // AGENT 角色的数据范围（自己 agent_no），非代理为空
  token: string;
  login: (v: { username: string; role: Role; token: string; agentNo?: string }) => void;
  logout: () => void;
  loggedIn: () => boolean;
}

export const useAuth = create<AuthState>()(
  persist(
    (set, get) => ({
      username: "",
      role: "ADMIN",
      tenantNo: MAIN_TENANT,
      agentNo: "",
      token: "",
      login: (v) => set({ ...v, tenantNo: MAIN_TENANT, agentNo: v.agentNo ?? "" }),
      logout: () => set({ username: "", token: "", agentNo: "" }),
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
