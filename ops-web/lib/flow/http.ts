// OpsKit 真实服务的 fetch 封装（21/06 §二 通用约定）。只被 lib/flow/client.ts 使用。
//
// 与宿主 http-client 的差别（都是 OpsKit 契约决定的，不是风格差异）：
//  - 响应是**裸对象**，不包 Result{code,message,data}；分页是 {list, total, page, size}；
//  - 错误体 {error, message, args, traceId, errors?[{path, error}]}：422 带逐条错误 → FlowValidationError；
//  - 写请求带 Idempotency-Key（同键同内容返回首次结果）；
//  - 401 **不**触发宿主登出：OpsKit 不认这个令牌，不等于宿主会话失效（令牌由宿主签发，内省在 OpsKit 侧）。
//
// 开关（构建时）：
//  NEXT_PUBLIC_FLOW_API=http        走真实服务（缺省走 mock）
//  NEXT_PUBLIC_OPSKIT_BASE          基址，缺省同源反代 /api/opskit/v1
//  NEXT_PUBLIC_OPSKIT_WS            空间，如 ext:{租户号}
// 本地联调（非 production 构建）可在 localStorage 放 `ops-opskit-token` 覆盖成员令牌 —— 测试 IdP 签的 JWT。
import { currentAuth } from "@/lib/auth";
import { ApiError } from "@/lib/api/error";
import { translate, LOCALE_TAG } from "@/lib/i18n";
import { errText } from "./i18n";
import { useLocaleStore } from "@/lib/stores/locale";
import { FlowValidationError } from "./errors";
import type { FieldError } from "./types";

export const FLOW_USE_HTTP = process.env.NEXT_PUBLIC_FLOW_API === "http";
const BASE = process.env.NEXT_PUBLIC_OPSKIT_BASE || "/api/opskit/v1";
const WS = process.env.NEXT_PUBLIC_OPSKIT_WS || "";
export const DEV_TOKEN_KEY = "ops-opskit-token";

interface OpsKitError { error?: string; message?: string; traceId?: string; errors?: FieldError[] }

const locale = () => useLocaleStore.getState().locale;

function token(): string | undefined {
  if (process.env.NODE_ENV !== "production") {
    try { const t = localStorage.getItem(DEV_TOKEN_KEY); if (t) return t; } catch { /* 隐私模式等：退回宿主令牌 */ }
  }
  return currentAuth()?.token || undefined;
}

const statusKey = (s: number) => s === 401 ? "error.unauthorized" : s === 403 ? "error.forbidden" : s === 404 ? "error.notFound"
  : s === 400 ? "error.badRequest" : s >= 500 ? "error.serverError" : "error.unknown";

/** 空间内的路径：/workspaces/{ws}/… */
export const ws = (path: string) => {
  if (!WS) throw new ApiError(-1, "NEXT_PUBLIC_OPSKIT_WS 未配置");
  return `/workspaces/${encodeURIComponent(WS)}${path}`;
};

export async function opskit<T>(method: "GET" | "POST" | "PUT" | "PATCH", path: string, body?: unknown): Promise<T> {
  const tk = token();
  const h: Record<string, string> = {
    "Accept-Language": LOCALE_TAG[locale()],
    ...(tk ? { Authorization: `Bearer ${tk}` } : {}),
    ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    ...(method !== "GET" ? { "Idempotency-Key": crypto.randomUUID() } : {}),
  };
  let r: Response;
  try {
    r = await fetch(`${BASE}${path}`, { method, headers: h, ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
  } catch {
    throw new ApiError(-1, translate(locale(), "error.network"));
  }
  if (r.status === 204) return undefined as T;
  const data = (await r.json().catch(() => ({}))) as T & OpsKitError;
  if (!r.ok) {
    // 已知错误键用本地三语文案（服务对这些键不给文案）；其余用服务的 message，再退状态码兜底
    const known = data.error ? errText((k, p) => translate(locale(), k, p), data.error) : undefined;
    const msg = (known && known !== data.error ? known : "") || data.message || translate(locale(), statusKey(r.status));
    if (r.status === 422 && Array.isArray(data.errors)) throw new FlowValidationError(msg, data.errors);
    throw new ApiError(r.status, msg);
  }
  return data;
}
