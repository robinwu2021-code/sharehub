<script setup lang="ts">
import { onLaunch } from "@dcloudio/uni-app";
import { isRTL } from "./i18n";

onLaunch(() => {
  // #ifdef H5
  if (typeof document !== "undefined") {
    document.documentElement.setAttribute("dir", isRTL() ? "rtl" : "ltr");
  }
  // #endif
});
</script>

<style>
/* 全局基础样式（原子类走 UnoCSS，此处仅页面底色） */
page {
  background-color: #f5f6f8;
  color: #1f2430;
  font-size: 14px;
}
</style>
