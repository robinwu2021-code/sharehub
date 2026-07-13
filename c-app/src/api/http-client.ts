// 真实后端 uni.request 封装：拼 Bearer + 统一 Result<T> 拆包 + 错误抛出。
// C 端只持 token，不自造受信头（后端据 token 反查属主，防 IDOR）。契约口径 {code,msg,data}。
import type { Result } from "@/types";
import { STORAGE } from "@/shared/constants";

// 端点路径已自带 /mp 前缀（见 http.ts），BASE 是后端「源」：
// - 同源反代（生产）：留空 → 走同源 /mp/**；
// - 跨源本地开发：置后端源，如 http://localhost:8080。
const BASE = import.meta.env.VITE_API_BASE || "";

type Method = "GET" | "POST" | "PUT";

function authHeaders(): Record<string, string> {
  const token = uni.getStorageSync(STORAGE.token) as string;
  const h: Record<string, string> = { "Content-Type": "application/json" };
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
}

function request<T>(
  path: string,
  opts: { method?: Method; data?: unknown; header?: Record<string, string> } = {},
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: `${BASE}${path}`,
      method: opts.method ?? "GET",
      data: opts.data as string | AnyObject | ArrayBuffer | undefined,
      header: { ...authHeaders(), ...opts.header },
      success: (res) => {
        const body = res.data as Partial<Result<T>>;
        const ok = res.statusCode >= 200 && res.statusCode < 300;
        if (ok && body && body.code === 0) {
          resolve(body.data as T);
        } else {
          reject(new Error(body?.msg || `HTTP ${res.statusCode}`));
        }
      },
      fail: (err) => reject(new Error(err.errMsg || "network error")),
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
  get: <T>(path: string, q?: object) => request<T>(`${path}${qs(q)}`),
  post: <T>(path: string, data?: unknown) => request<T>(path, { method: "POST", data: data ?? {} }),
  put: <T>(path: string, data?: unknown) => request<T>(path, { method: "PUT", data: data ?? {} }),
};

type AnyObject = Record<string, unknown>;
