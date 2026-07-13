// import.meta.env 类型（Vite）。VITE_USE_MOCK / VITE_API_BASE 是 mock↔真实的唯一切换点。
interface ImportMetaEnv {
  readonly VITE_USE_MOCK?: string; // "0" 走真实后端，否则 mock
  readonly VITE_API_BASE?: string; // 后端源（同源留空 / 跨源填 http://localhost:8080）
}
interface ImportMeta {
  readonly env: ImportMetaEnv;
}
