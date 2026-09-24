import type { NextConfig } from "next";

// 子路径部署：构建期注入 NEXT_PUBLIC_BASE_PATH（如 /powerbank/ops-web）→ basePath/assetPrefix。
// 对齐 ai-boss/ops-web：静态导出，由 nginx 托管；/api 反代到后端（同源，无 CORS）。
const BASE_PATH = process.env.NEXT_PUBLIC_BASE_PATH || "";

const nextConfig: NextConfig = {
  output: "export",
  images: { unoptimized: true },
  trailingSlash: true,
  typescript: { ignoreBuildErrors: true },

  // Turbopack 的 dev 持久缓存（16.2.10 默认开启）只追加 SST 层、不做 compaction/GC：
  // 实测 9-23~9-24 两天堆到 741 个文件 / 30GB（约 15GB/天），把整块盘撑满。
  // 产物本身只有 ~378MB，膨胀全在 .next/dev/cache/turbopack/。
  // 代价：每次 dev 冷启动变慢。待上游修好 compaction 后可再打开。
  experimental: { turbopackFileSystemCacheForDev: false },
  ...(BASE_PATH ? { basePath: BASE_PATH, assetPrefix: BASE_PATH } : {}),
};

export default nextConfig;
