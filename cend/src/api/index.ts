// 唯一切换点：VITE_USE_MOCK=0 走真实后端 /mp，否则 mock。
// 页面统一 `import { api } from "@/api"`，不感知 mock/真实（对齐 ops-web）。
import type { CApi } from "./contract";
import { mockApi } from "./mock";
import { httpApi } from "./http";
import { USE_MOCK } from "../config";

export const api: CApi = USE_MOCK ? mockApi : httpApi;
export const IS_MOCK = USE_MOCK;
export * from "./types";
export type { CApi } from "./contract";
