import { createSSRApp } from "vue";
import App from "./App.vue";
import { pinia } from "@/stores";
import { i18n } from "@/i18n";
import "uno.css";

export function createApp() {
  const app = createSSRApp(App);
  app.use(pinia);
  app.use(i18n);
  return { app };
}
