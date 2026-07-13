import type { NextConfig } from "next";

// 子路径部署：构建期注入 NEXT_PUBLIC_BASE_PATH（如 /powerbank/ops-web）→ basePath/assetPrefix。
// 对齐 ai-boss/ops-web：静态导出，由 nginx 托管；/api 反代到后端（同源，无 CORS）。
const BASE_PATH = process.env.NEXT_PUBLIC_BASE_PATH || "";

const nextConfig: NextConfig = {
  output: "export",
  images: { unoptimized: true },
  trailingSlash: true,
  typescript: { ignoreBuildErrors: true },
  ...(BASE_PATH ? { basePath: BASE_PATH, assetPrefix: BASE_PATH } : {}),
};

export default nextConfig;
