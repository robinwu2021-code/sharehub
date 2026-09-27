import { defineConfig, loadEnv, type Plugin } from "vite";
import { writeFileSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import uniModule from "@dcloudio/vite-plugin-uni";
import UnoCSS from "unocss/vite";

// .mts 走原生 ESM 加载（unocss/vite 是 ESM-only）。
// @dcloudio/vite-plugin-uni 是 CJS，ESM 下真正的工厂函数在 .default 上（Babel 互操作）。
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const uni = ((uniModule as any).default ?? uniModule) as () => any;

/**
 * 往产物里写一份 `pb-build-info.json`（构建配置的自述）。
 *
 * **为什么需要它**：离线包的坏法都是「构建成功、产物是假的」——
 * 最典型的是 `VITE_USE_MOCK` 没关，打出来的包连的是内存 mock（迪拜四个假门店、
 * 登录随便填都进、充值立刻到账），一个后端请求都不发。
 * 而产物是压缩过的，事后想从 `app-service.js` 里把这件事看出来**不可靠**：
 * 变量名全变了，mock 数据即使没被启用也照样打进包里（摇不掉），
 * 按字符串判定只会得到假绿。
 *
 * 所以把结论在构建时写成一个文件，让闸门读文件而不是猜代码。它跟着
 * `dist/build/app` 一起被拷进离线工程的 `www/`，于是**装机后的 APK 里也有这一份** ——
 * `offline/verify-apk.sh` 直接从 apk 里 unzip 出来核，核的是真正发出去的那个包。
 *
 * 里面不放密钥：地图 key 只记「在不在」。
 */
function pbBuildInfo(env: Record<string, string>): Plugin {
  let outDir = "";
  return {
    name: "pb-build-info",
    apply: "build",
    configResolved(c) {
      outDir = c.build.outDir;
    },
    closeBundle() {
      const mf = JSON.parse(
        readFileSync(resolve(__dirname, "src/manifest.json"), "utf-8").replace(/\/\*[\s\S]*?\*\//g, ""),
      );
      const info = {
        // `src/api/index.ts` 的口径：不写就是 mock（`!== "0"`），这里必须同口径，
        // 否则闸门放行的与端上实际走的不是一回事。
        useMock: env.VITE_USE_MOCK !== "0",
        apiBase: env.VITE_API_BASE || "",
        hasGmapsKey: !!env.VITE_GMAPS_KEY,
        h5Base: env.H5_BASE || "/",
        appid: mf.appid || "",
        versionName: mf.versionName || "",
        versionCode: String(mf.versionCode ?? ""),
        builtAt: new Date().toISOString(),
      };
      writeFileSync(resolve(outDir, "pb-build-info.json"), JSON.stringify(info, null, 2) + "\n");
    },
  };
}

// ⚠️ `loadEnv` 而不是 `process.env`：Vite 把 .env 读进的是 `import.meta.env`，
// **不经过 process.env**。用后者写出来的 build-info 恒为「mock、无 base、无 key」，
// 而产物里其实是对的 —— 一个永远报警的闸门，和没有闸门一样会被绕过去。
// 第三个参数 "" 表示不按 VITE_ 前缀过滤：H5_BASE 没有那个前缀。

// uni 插件在前，UnoCSS 在后（uni-app 官方推荐顺序）。
// 默认 5174（避开并发会话在 5173 的 dev server）；PORT 环境变量可覆盖，供其它会话并发起服务。
export default defineConfig(({ mode }) => {
  const env = { ...loadEnv(mode, __dirname, ""), ...process.env } as Record<string, string>;
  return {
  // 部署到子路径（如 /c/）时必须给 H5_BASE：不给的话产物里的资源写成 `/assets/…`
  // 而它们实际在 `/c/assets/…`，浏览器 404 → **整站白屏**。同 ai-shop c-app 的踩坑。
    base: env.H5_BASE || "/",
    server: { port: Number(env.PORT) || 5174, strictPort: false },
    plugins: [uni(), UnoCSS(), pbBuildInfo(env)],
  };
});
