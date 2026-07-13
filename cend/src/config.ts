// C 端运行配置。唯一切换点：VITE_USE_MOCK=0 走真实 /mp 后端，否则 mock（对齐 ops-web 心智）。
// H5 用 import.meta.env（.env 文件 VITE_ 前缀）；App/小程序为构建期注入。

const env = import.meta.env as Record<string, string | undefined>;

/** true=用内存 mock（无后端）；false=接真实后端 /mp。 */
export const USE_MOCK = env.VITE_USE_MOCK !== "0";

/** 后端「源」。同源留空；跨源开发填后端源（如 http://localhost:8080）。路径已自带 /mp 前缀。 */
export const API_BASE = env.VITE_API_BASE || "";

/** C 端会话渠道：App=手机/Apple/Google，小程序=微信。骨架默认 APP。 */
export const LOGIN_CHANNEL = env.VITE_LOGIN_CHANNEL || "APP";
