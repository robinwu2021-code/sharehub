// 登录端能力：只有「拿登录凭据」因端而异（条件编译）。产出统一 LoginParams 交给 api.login。
// App：手机号 OTP（MVP）/ Apple / Google / Microsoft 企业邮箱；小程序：微信 openid。
import type { LoginParams } from "@/api";
import { MS_AUTH, msAuthority, msRedirectUri } from "@/config/ms-auth";

export async function acquireLogin(input: { phone?: string; otp?: string } = {}): Promise<LoginParams> {
  let params: LoginParams = { grantType: "phone_otp", phone: input.phone, otp: input.otp };
  // #ifdef MP-WEIXIN
  params = { grantType: "wechat_miniapp", code: await wxLogin() };
  // #endif
  return params;
}

// #ifdef MP-WEIXIN
function wxLogin(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.login({
      provider: "weixin",
      success: (r) => resolve(r.code),
      fail: (e) => reject(new Error(e.errMsg || "wx login failed")),
    });
  });
}
// #endif

// ── Microsoft 365 企业邮箱（Entra ID）：Authorization Code + PKCE（公共客户端，前端无密钥）──
// H5/WebView 生效；MP 端不会调用这些函数（小程序走 wechat_miniapp）。以运行时判断代替条件编译，
// 保证导出恒存在、各端可编译。真正的 code→令牌交换与企业域名校验都在后端完成。
const MS_STATE_KEY = "pb_ms_state";
const MS_VERIFIER_KEY = "pb_ms_verifier";

function msRandom(len = 64): string {
  const arr = new Uint8Array(len);
  crypto.getRandomValues(arr);
  return Array.from(arr, (b) => ("0" + (b & 0xff).toString(16)).slice(-2)).join("").slice(0, len);
}
function base64url(buf: ArrayBuffer): string {
  let s = "";
  new Uint8Array(buf).forEach((b) => (s += String.fromCharCode(b)));
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
async function pkceChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return base64url(digest);
}

// 跳转到 Microsoft 授权页；state+verifier 暂存 sessionStorage，回跳后校验/取用。
export async function startMicrosoftLogin(): Promise<void> {
  const state = msRandom(32);
  const verifier = msRandom(64);
  sessionStorage.setItem(MS_STATE_KEY, state);
  sessionStorage.setItem(MS_VERIFIER_KEY, verifier);
  const challenge = await pkceChallenge(verifier);
  const q = new URLSearchParams({
    client_id: MS_AUTH.clientId,
    response_type: "code",
    redirect_uri: msRedirectUri(),
    response_mode: "query",
    scope: MS_AUTH.scopes,
    state,
    code_challenge: challenge,
    code_challenge_method: "S256",
  });
  const domain = MS_AUTH.allowedDomains.split(",")[0]?.trim();
  if (domain) q.set("domain_hint", domain);
  location.assign(`${msAuthority()}/oauth2/v2.0/authorize?${q.toString()}`);
}

// 授权回跳后从 URL 取回 code（校验 state），产出统一 LoginParams；无回跳返回 null。
export async function consumeMicrosoftRedirect(): Promise<LoginParams | null> {
  if (typeof location === "undefined") return null;
  const url = new URL(location.href);
  const code = url.searchParams.get("code");
  if (!code) return null;
  const state = url.searchParams.get("state");
  const saved = sessionStorage.getItem(MS_STATE_KEY);
  const verifier = sessionStorage.getItem(MS_VERIFIER_KEY) || undefined;
  sessionStorage.removeItem(MS_STATE_KEY);
  sessionStorage.removeItem(MS_VERIFIER_KEY);
  // 清掉 URL 上的授权参数，避免刷新时重复消费
  ["code", "state", "session_state"].forEach((k) => url.searchParams.delete(k));
  history.replaceState(null, "", url.toString());
  if (!saved || saved !== state) throw new Error("Microsoft login state mismatch");
  return { grantType: "microsoft", code, codeVerifier: verifier, redirectUri: msRedirectUri() };
}
