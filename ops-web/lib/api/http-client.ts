// 真实后端 fetch 封装：拼 AuthHeaders 受信头 + Accept-Language + 统一 Result<T> 拆包 + 抛 ApiError。
// 对齐 ai-boss/ops-web 与 commons Result/ErrorCode 约定。
import { currentAuth } from "../auth";
import type { Result } from "../types";
import { ApiError } from "./error";
import { sessionExpired, isSessionSensitive } from "./session";
import { useLocaleStore } from "../stores/locale";
import { translate, LOCALE_TAG } from "../i18n";

function curLocale() {
  return useLocaleStore.getState().locale;
}
// HTTP 状态 → i18n 错误 key（后端有本地化 message 时优先用后端的）。
function statusKey(status: number): string {
  return status === 401 ? "error.unauthorized"
    : status === 403 ? "error.forbidden"
    : status === 404 ? "error.notFound"
    : status === 400 ? "error.badRequest"
    : status >= 500 ? "error.serverError"
    : "error.unknown";
}

// 端点路径已自带 /api、/internal 前缀（见 http.ts），故 BASE 是后端「源」而非 /api：
// - 同源 nginx 反代（生产）：留空 → 走同源 /api/**、/internal/**；
// - 跨源本地开发：置后端源 http://localhost:8080。
const BASE = process.env.NEXT_PUBLIC_API_BASE || "";

function headers(): Record<string, string> {
  const a = currentAuth();
  return {
    "Content-Type": "application/json",
    "Accept-Language": LOCALE_TAG[curLocale()], // 让后端按语言返回本地化 message
    /*
     * X-Merchant-Id / X-User-Id / X-Roles **已删**（2026-09-23，D6a）。
     * 实测后端全仓只读两个头：Authorization 与 X-Forwarded-For；
     * 那三个在后端出现 10 处**全是注释**，内容都是「不信客户端这些头」。
     * 留着只会让人以为可以靠它们传身份。
     *
     * **X-Operator-No 也已删**（2026-09-24），理由不同，值得写清楚：
     * 开发计划原本设计「切换靠这个请求头，不新增切换端点」，并把它称为
     * 「整条多主体改造里唯一有越权面的地方」—— 客户端可控、而服务端会采纳。
     *
     * 实际落地改成了 `POST /api/auth/operators/{agentNo}/switch` 换发 token：
     * 主体写进 token、老 token 当场吊销。那个越权面**整个不存在了**，
     * 而不是给它加一道必须永远校验对的闸。
     *
     * 留着它还有一层危险：后端实测**一个字节都没读过**这个头，而原注释写得
     * 像是已实现的契约 —— 将来有人照着去「补上服务端采纳」，
     * 就把刚消掉的越权面重新装了回来。
     */
    ...(a?.token ? { Authorization: `Bearer ${a.token}` } : {}),
  };
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  let r: Response;
  try {
    r = await fetch(`${BASE}${path}`, { ...init, headers: { ...headers(), ...init?.headers } });
  } catch {
    throw new ApiError(-1, translate(curLocale(), "error.network")); // 网络层失败
  }
  const body = (await r.json().catch(() => ({}))) as Partial<Result<T>>;
  if (!r.ok || (body.code !== undefined && body.code !== 0)) {
    // 401 = 服务端不认这个会话了（过期 / 被吊销 / 另一端登出）。
    // 不处理的话，token 还留在 localStorage 里，loggedIn() 仍为真，
    // 于是外壳照常渲染、每个请求各弹一个错 —— **已失效的会话看起来仍是登录态**。
    if (r.status === 401 && isSessionSensitive(path)) sessionExpired();
    // 后端已本地化 message 优先；否则用状态码映射的 i18n 文案
    const msg = body.message || translate(curLocale(), statusKey(r.status));
    throw new ApiError(body.code ?? r.status, msg);
  }
  return body.data as T;
}

/**
 * 上传：multipart + **上传进度**。
 *
 * <h3>为什么这一个不用 fetch</h3>
 * `fetch` 至今没有上传进度事件（只有下载端的 stream）。合同扫描件可到 20MB，
 * 没有进度条时用户会以为界面卡死而反复点按钮，**于是同一份文件传好几遍** ——
 * 附件列表里出现三份一样的扫描件，谁也说不清哪份是最终版。所以这里退回 XHR。
 *
 * <h3>不要设 Content-Type</h3>
 * 交给浏览器：它会带上 multipart 的 boundary。手写 `multipart/form-data`
 * 没有 boundary，后端解析出零个 part，报的却是「缺少参数 file」——
 * 从错误信息完全看不出是 Content-Type 的问题。
 */
function upload<T>(path: string, form: FormData, onProgress?: (p: number) => void): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", `${BASE}${path}`);
    const h = headers();
    for (const [k, v] of Object.entries(h)) {
      if (k.toLowerCase() === "content-type") continue;   // 见上：必须让浏览器自己带 boundary
      xhr.setRequestHeader(k, v);
    }
    if (onProgress) {
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) onProgress(e.loaded / e.total);
      };
    }
    xhr.onerror = () => reject(new ApiError(-1, translate(curLocale(), "error.network")));
    xhr.onload = () => {
      let body: Partial<Result<T>> = {};
      try { body = JSON.parse(xhr.responseText) as Partial<Result<T>>; } catch { /* 非 JSON：按状态码处理 */ }
      const ok = xhr.status >= 200 && xhr.status < 300;
      if (!ok || (body.code !== undefined && body.code !== 0)) {
        if (xhr.status === 401 && isSessionSensitive(path)) sessionExpired();
        reject(new ApiError(body.code ?? xhr.status, body.message || translate(curLocale(), statusKey(xhr.status))));
        return;
      }
      resolve(body.data as T);
    };
    xhr.send(form);
  });
}

export const client = {
  get: <T>(path: string, q?: object) => req<T>(`${path}${qs(q)}`),
  post: <T>(path: string, data?: unknown) => req<T>(path, { method: "POST", body: JSON.stringify(data ?? {}) }),
  put: <T>(path: string, data?: unknown) => req<T>(path, { method: "PUT", body: JSON.stringify(data ?? {}) }),
  upload,
};

function qs(q?: object): string {
  if (!q) return "";
  const p = new URLSearchParams(
    Object.entries(q as Record<string, unknown>).filter(([, v]) => v != null && v !== "").map(([k, v]) => [k, String(v)]),
  );
  const s = p.toString();
  return s ? `?${s}` : "";
}
