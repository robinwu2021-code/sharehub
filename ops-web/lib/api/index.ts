// 唯一切换点：NEXT_PUBLIC_USE_MOCK=0 走真实后端，否则 mock。
// 页面统一 `import { api } from "@/lib/api"`，调用 api.xxx()，不感知 mock/真实。
import type { Api } from "./contract";
import { mockApi } from "./mock";
import { httpApi } from "./http";

const USE_MOCK = process.env.NEXT_PUBLIC_USE_MOCK !== "0";

export const api: Api = USE_MOCK ? mockApi : httpApi;
export const IS_MOCK = USE_MOCK;
export type { Api } from "./contract";
export * from "./contract";
