// 外观默认值：零依赖、无 "use client"，根布局（服务端组件）要在 <html> 上写它。
// 从 stores/theme.ts 或 stores/style.ts 导入的话，服务端拿到的是客户端引用而不是值。
export const DEFAULT_THEME = "teal" as const;
export const DEFAULT_STYLE = "clean" as const;
