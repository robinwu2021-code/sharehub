// 唯一切换点：VITE_USE_MOCK=0 走真实后端，否则 mock。
// 页面统一 `import { api } from "@/api"`，调用 api.xxx()，不感知 mock/真实（与 ops-web 同法）。
import type { McpApi } from "./contract";
import { mockApi } from "./mock";
import { httpApi } from "./http";

const USE_MOCK = import.meta.env.VITE_USE_MOCK !== "0";

export const api: McpApi = USE_MOCK ? mockApi : httpApi;
export const IS_MOCK = USE_MOCK;
export type { McpApi } from "./contract";
export * from "./contract";
