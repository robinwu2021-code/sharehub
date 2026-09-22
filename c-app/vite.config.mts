import { defineConfig } from "vite";
import uniModule from "@dcloudio/vite-plugin-uni";
import UnoCSS from "unocss/vite";

// .mts 走原生 ESM 加载（unocss/vite 是 ESM-only）。
// @dcloudio/vite-plugin-uni 是 CJS，ESM 下真正的工厂函数在 .default 上（Babel 互操作）。
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const uni = ((uniModule as any).default ?? uniModule) as () => any;

// uni 插件在前，UnoCSS 在后（uni-app 官方推荐顺序）。
// 默认 5174（避开并发会话在 5173 的 dev server）；PORT 环境变量可覆盖，供其它会话并发起服务。
export default defineConfig({
  // 部署到子路径（如 /c/）时必须给 H5_BASE：不给的话产物里的资源写成 `/assets/…`
  // 而它们实际在 `/c/assets/…`，浏览器 404 → **整站白屏**。同 ai-shop c-app 的踩坑。
  base: process.env.H5_BASE || "/",
  server: { port: Number(process.env.PORT) || 5174, strictPort: false },
  plugins: [uni(), UnoCSS()],
});
