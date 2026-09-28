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

/**
 * 业务错误：除了给人读的 message，还带服务端的 code 与**结构化的 data**。
 * 调用方要据此做事时读 data，不要解析 message —— 例如借出被欠费拦下，
 * data 里有那张欠费单的 orderNo，端上直接打开它去补缴（后端 BizException#withData）。
 */
export class ApiError extends Error {
  constructor(message: string, readonly code: number, readonly data?: unknown) {
    super(message);
  }
}

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
          reject(new ApiError(body?.message || `HTTP ${res.statusCode}`, body?.code ?? res.statusCode, body?.data));
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

/**
 * 上传一个本地文件（`uni.chooseImage` 给的临时路径），返回服务端的 fileNo。
 *
 * 走 `uni.uploadFile` 而不是 `uni.request`：端上拿到的是临时路径不是二进制，
 * 只有前者会读文件并拼 multipart。**不能带 `Content-Type`** —— 那个头由它自己拼
 * （里面要带 boundary），手动塞一个 application/json 会让服务端解不出表单。
 *
 * 出参是字符串，要自己 JSON.parse，与 uni.request 不同。
 */
export function upload<T>(path: string, filePath: string): Promise<T> {
  const { "Content-Type": _ct, ...headers } = authHeaders();
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: `${BASE}${path}`,
      filePath,
      name: "file",
      header: headers,
      success: (res) => {
        let body: Partial<Result<T>> = {};
        try {
          body = JSON.parse(res.data) as Partial<Result<T>>;
        } catch {
          reject(new ApiError(`HTTP ${res.statusCode}`, res.statusCode));
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && body.code === 0) {
          resolve(body.data as T);
        } else {
          if (res.statusCode === 401) onUnauthorized?.();
          reject(new ApiError(body.message || `HTTP ${res.statusCode}`, body.code ?? res.statusCode));
        }
      },
      fail: (err) => reject(new Error(err.errMsg || "network error")),
    });
  });
}

/**
 * 下载一张受鉴权保护的图，返回本地临时路径。
 *
 * `<image src>` 发不了 Authorization 头，而报障截图是个人数据、服务端不发公开链接 ——
 * 所以只能 `uni.downloadFile`（它能带 header）先落地，再把临时路径交给 `<image>` / `previewImage`。
 */
export function downloadAuthed(path: string): Promise<string> {
  const { "Content-Type": _ct, ...headers } = authHeaders();
  return new Promise((resolve, reject) => {
    uni.downloadFile({
      url: `${BASE}${path}`,
      header: headers,
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300 && res.tempFilePath) resolve(res.tempFilePath);
        else reject(new ApiError(`HTTP ${res.statusCode}`, res.statusCode));
      },
      fail: (err) => reject(new Error(err.errMsg || "network error")),
    });
  });
}

export const client = {
  get: <T>(path: string, q?: object) => request<T>(`${path}${qs(q)}`),
  post: <T>(path: string, data?: unknown) => request<T>(path, { method: "POST", data: data ?? {} }),
  put: <T>(path: string, data?: unknown) => request<T>(path, { method: "PUT", data: data ?? {} }),
};

type AnyObject = Record<string, unknown>;
