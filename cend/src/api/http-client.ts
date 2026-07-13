// 真实后端请求封装（uni.request，跨端）：拼 C 会话头 + 统一 Result 拆包。
// 端点路径已自带 /mp 前缀，API_BASE 为后端「源」（同源留空/跨源填源）。
import { API_BASE } from "../config";
import type { Result } from "./types";

interface AuthLite {
  token?: string;
  cUserNo?: string;
}

function authHeaders(): Record<string, string> {
  let a: AuthLite = {};
  try {
    a = (uni.getStorageSync("c-auth") as AuthLite) || {};
  } catch {
    a = {};
  }
  return {
    "Content-Type": "application/json",
    ...(a.token ? { Authorization: `Bearer ${a.token}` } : {}),
    ...(a.cUserNo ? { "X-User-Id": a.cUserNo } : {}),
  };
}

function request<T>(method: "GET" | "POST" | "PUT", path: string, data?: unknown): Promise<T> {
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${API_BASE}${path}`,
      method,
      data: data as Record<string, unknown> | undefined,
      header: authHeaders(),
      success: (r) => {
        const body = (r.data ?? {}) as Partial<Result<T>>;
        if (r.statusCode !== 200 || (body.code !== undefined && body.code !== 0)) {
          reject(new Error(body.message || `HTTP ${r.statusCode}`));
          return;
        }
        resolve(body.data as T);
      },
      fail: (e) => reject(new Error(e.errMsg || "network error")),
    });
  });
}

function qs(q?: object): string {
  if (!q) return "";
  const parts = Object.entries(q as Record<string, unknown>)
    .filter(([, v]) => v != null && v !== "")
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join("&")}` : "";
}

export const client = {
  get: <T>(path: string, q?: object) => request<T>("GET", `${path}${qs(q)}`),
  post: <T>(path: string, data?: unknown) => request<T>("POST", path, data ?? {}),
  put: <T>(path: string, data?: unknown) => request<T>("PUT", path, data ?? {}),
};
