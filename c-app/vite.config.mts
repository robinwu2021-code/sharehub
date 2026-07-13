import { defineConfig } from "vite";
import uniModule from "@dcloudio/vite-plugin-uni";
import UnoCSS from "unocss/vite";

// .mts 走原生 ESM 加载（unocss/vite 是 ESM-only）。
// @dcloudio/vite-plugin-uni 是 CJS，ESM 下真正的工厂函数在 .default 上（Babel 互操作）。
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const uni = ((uniModule as any).default ?? uniModule) as () => any;

// uni 插件在前，UnoCSS 在后（uni-app 官方推荐顺序）。
// 固定 5174，避开并发会话在 5173 的 dev server。
export default defineConfig({
  server: { port: 5174, strictPort: false },
  plugins: [uni(), UnoCSS()],
});
