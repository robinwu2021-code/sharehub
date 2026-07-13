import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";

// C 端 uni-app：App 优先 / 微信小程序。原子类走 UnoCSS(preset-wind)（对齐前端栈决策）。
// UnoCSS 为 ESM-only，而 uni CLI 以 CJS require 加载本配置 → 用动态 import 规避 ESM/CJS 冲突。
export default defineConfig(async () => {
  const UnoCSS = (await import("unocss/vite")).default;
  return {
    plugins: [uni(), UnoCSS()],
    server: { port: 5173, host: true },
  };
});
