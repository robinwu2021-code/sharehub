// 后端文件下载（K8：报表与发票导出 Excel / PDF / zip，2026-10-29）。
// 文件响应不走 {code,message,data} 信封，client.get 解不了；失败时后端仍回信封 JSON，这里照样转成 ApiError，
// 交给调用方的 mutation 弹错 —— 与其它接口同一条错误路径。
import { ApiError } from "./error";
import { currentAuth } from "../auth";
import { useLocaleStore } from "../stores/locale";
import { translate, LOCALE_TAG } from "../i18n";

/** Content-Disposition 优先取 filename*（UTF-8），否则 filename，都没有用兜底名。 */
function filenameOf(disposition: string | null, fallback: string) {
  if (!disposition) return fallback;
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (star) return decodeURIComponent(star[1]);
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  return plain ? plain[1] : fallback;
}

export async function downloadFile(path: string, q: Record<string, string | undefined> = {}, fallback = "download"): Promise<void> {
  const base = process.env.NEXT_PUBLIC_API_BASE || "";
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(q)) if (v !== undefined && v !== "") params.set(k, v);
  const qs = params.toString() ? `?${params.toString()}` : "";
  const token = currentAuth()?.token;
  const locale = useLocaleStore.getState().locale;
  let r: Response;
  try {
    r = await fetch(`${base}${path}${qs}`, {
      headers: { "Accept-Language": LOCALE_TAG[locale], ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    });
  } catch {
    throw new ApiError(-1, translate(locale, "error.network"));
  }
  const type = r.headers.get("content-type") ?? "";
  if (!r.ok || type.includes("application/json")) {
    const body = (await r.json().catch(() => ({}))) as { code?: number; message?: string };
    throw new ApiError(body.code ?? r.status, body.message || translate(locale, "error.unknown"));
  }
  const blob = await r.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filenameOf(r.headers.get("content-disposition"), fallback);
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
