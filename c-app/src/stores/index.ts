// Pinia 实例。main.ts 注册；store 内用 uni storage 做轻量持久化（免第三方插件的版本矩阵）。
import { createPinia } from "pinia";

export const pinia = createPinia();
