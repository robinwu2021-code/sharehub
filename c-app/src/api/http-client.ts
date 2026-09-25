// 真实后端 uni.request 封装：拼 Bearer + 统一 Result<T> 拆包 + 错误抛出。
// C 端只持 token，不自造受信头（后端据 token 反查属主，防 IDOR）。契约口径 {code,message,data}（neargo-common-core 的 Result，与 ops-web 同一份）。
import type { Result } from "@/types";
import { STORAGE, DEFAULT_LANG } from "@/shared/constants";

// 端点路径已自带 /mp 前缀（见 http.ts），BASE 是后端「源」：
// - 同源反代（生产）：留空 → 走同源 /mp/**；
// - 跨源本地开发：置后端源，如 http://localhost:8080。
const BASE = import.meta.env.VITE_API_BASE || "";

type Method = "GET" | "POST" | "PUT";

/**
 * 会话失效（401）回调。
 *
 * 为什么是「注册回调」而不是直接 import user store：
 * store 依赖 api，api 依赖本文件 —— 本文件再 import store 就成环。
 * 而直接在这里清 storage 也不对：pinia 里那份 token 还在内存中，
 * 页面读的是内存那份，会出现「storage 清了、界面仍是登录态」。
 * 所以把"怎么清"交回给 store，本文件只负责判定"什么时候该清"。
 */
let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn;
}

/**
 * 这个端点的 401 该不该当作会话失效。
 *
 * **认证端点除外**：验证码错、密码错时后端也返回 401，若按会话失效处理，
 * 用户看到的是被踢回登录页，而不是"验证码错误" —— 错误提示被自己的跳转吃掉了。
 */
export function isSessionSensitive(path: string): boolean {
  return !path.startsWith("/mp/auth/");
}

/**
 * 语言标签。与 ops-web 用同一套（`zh-CN` / `en-AE` / `ar-AE`），
 * 后端 `AcceptHeaderLocaleResolver` 的 supportedLocales 是 zh/en/ar。
 */
const LOCALE_TAG: Record<string, string> = { zh: "zh-CN", en: "en-AE", ar: "ar-AE" };

/**
 * 请求头。
 *
 * **必须带 `Accept-Language`**：后端的错误文案走 `Messages`（i18n 消息包）按
 * 请求 Locale 解析，不带这个头就一律回落到默认语 —— 于是界面切到英文/阿语之后，
 * **页面是英文、错误提示还是中文**，而错误提示恰恰是用户最需要看懂的那一句。
 * ops-web 那条路一直是对的（它发这个头），C 端这条从来没发过。
 *
 * 语言直接读 storage 而不 import app store：store 依赖 api、api 依赖本文件，
 * 再反向 import 就成环 —— 与上面 token 的处理同理。
 */
function authHeaders(): Record<string, string> {
  const token = uni.getStorageSync(STORAGE.token) as string;
  const lang = (uni.getStorageSync(STORAGE.lang) as string) || DEFAULT_LANG;
  const h: Record<string, string> = {
    "Content-Type": "application/json",
    "Accept-Language": LOCALE_TAG[lang] ?? LOCALE_TAG[DEFAULT_LANG],
  };
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
          // 401 = 服务端不认这个会话了（过期 / 被吊销 / 换了设备）。
          // 不处理的话 token 还留在 storage 里，isLoggedIn 仍为真，
          // 界面照常显示已登录，而每个请求各弹一个错。
          if (res.statusCode === 401 && isSessionSensitive(path)) onUnauthorized?.();
          reject(new Error(body?.message || `HTTP ${res.statusCode}`));
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
