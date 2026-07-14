// ─────────────────────────────────────────────────────────────────────────────
// Microsoft 365 企业邮箱登录配置（Microsoft Entra ID / 旧称 Azure AD）
//
// ★ 请在下方 <尖括号占位> 处填写你自己的租户信息；也可用 .env 的 VITE_MS_* 覆盖
//   （见 c-app/.env.example）。填好后把 enabled 置 true，或设 VITE_MS_ENABLED=1。
//
// 安全边界（务必遵守）：
//   • 前端只做「授权跳转 + 取回授权码(code)」，采用 Authorization Code + PKCE 的
//     「公共客户端(SPA)」模式——全程不需要、也严禁写入 client secret。
//   • client secret 是后端机密：只放后端环境变量，用于后端拿 code 换令牌 + 校验企业域名。
//     千万不要把 secret 填进本文件或任何 VITE_* 变量（VITE_ 会被打进前端包，等于公开泄露）。
//
// 在 Entra 门户获取这些值：
//   门户 → 「应用注册」→ 你的应用 →「概述」页可见 应用(客户端)ID 与 目录(租户)ID；
//   「身份验证」→ 添加平台 → 选「单页应用(SPA)」→ 填入下方 redirectUri（须完全一致）。
// ─────────────────────────────────────────────────────────────────────────────

const env = import.meta.env;

export const MS_AUTH = {
  // 是否在登录页展示「使用 Microsoft 登录」按钮。
  // 未填真实信息时保持 false；mock 模式下按钮仍会出现（走本地演示登录，不联网）。
  enabled: (env.VITE_MS_ENABLED as string) === "1" || false,

  // 目录(租户) ID：Entra「概述」页的“目录(租户) ID”。
  //   • 只允许本企业账户登录 → 填租户 GUID（如 "00000000-1111-2222-3333-444444444444"）
  //   • 允许任意工作/学校账户 → 填 "organizations"
  tenantId: (env.VITE_MS_TENANT_ID as string) || "<TENANT_ID>",

  // 应用(客户端) ID：Entra「概述」页的“应用程序(客户端) ID”。
  clientId: (env.VITE_MS_CLIENT_ID as string) || "<CLIENT_ID>",

  // 重定向 URI：须与 Entra「单页应用(SPA)」平台里登记的 URI 完全一致。
  //   • 本地 H5 开发示例：http://localhost:5174/pages/login/index
  //   • 生产示例：       https://你的域名/pages/login/index
  //   留空则运行时回退为「当前登录页地址」（location.origin + 路径）。
  redirectUri: (env.VITE_MS_REDIRECT_URI as string) || "",

  // 申请的权限范围：openid/profile/email 取身份足矣；如后端需读 Graph 资料再加 "User.Read"。
  scopes: (env.VITE_MS_SCOPES as string) || "openid profile email",

  // 仅允许的企业邮箱域名（可选，多个用逗号分隔，如 "neargo.ai,contoso.com"）。
  // 前端仅用于 domain_hint 提示 + 简单预校验；真正的域名放行以后端为准。
  allowedDomains: (env.VITE_MS_DOMAINS as string) || "",
};

// 授权服务器基址（v2.0 端点）。
export function msAuthority(): string {
  return `https://login.microsoftonline.com/${MS_AUTH.tenantId}`;
}

// 运行时确定的重定向地址：优先用配置，否则回退到当前页面地址（H5）。
export function msRedirectUri(): string {
  if (MS_AUTH.redirectUri) return MS_AUTH.redirectUri;
  if (typeof location !== "undefined") return location.origin + location.pathname;
  return "";
}

// 配置是否「已填真值」——用于区分真实授权流程 vs 本地演示登录。
export function msConfigured(): boolean {
  return (
    MS_AUTH.enabled &&
    !!MS_AUTH.clientId &&
    !MS_AUTH.clientId.startsWith("<") &&
    !!MS_AUTH.tenantId &&
    !MS_AUTH.tenantId.startsWith("<")
  );
}
