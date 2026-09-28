import { createSSRApp } from "vue";
import App from "./App.vue";
import { pinia } from "@/stores";
import { i18n, t } from "@/i18n";
import "uno.css";

export function createApp() {
  const app = createSSRApp(App);
  app.use(pinia);
  app.use(i18n);
  /*
   * 模板里的 $t 换成我们自己的 t：vue-i18n 在 uni 的构建里是 runtime-only，
   * 消息编译器不在包里，`$t(key, { n })` 的插值会静默失效（见 i18n/index.ts 的说明）。
   * 覆盖必须在 app.use(i18n) **之后** —— globalInjection 是在那一步装上去的。
   */
  // 类型上与 vue-i18n 的重载签名对不上（它还接受 locale / plural 等形参），本端只用具名参数这一种
  (app.config.globalProperties as Record<string, unknown>).$t = t;
  return { app };
}
