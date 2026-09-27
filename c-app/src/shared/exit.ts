// 底部三个 Tab 页的「再按一次退出」。
//
// 此前用的是 DCloud 原生层的提示，它跟随**手机系统语言** —— App 设成英文 / 阿语时，
// 按返回键弹出一句中文「再按一次退出应用」（真机 REDMI 实测）。改为自己接管返回键，用 App 的语言。
// 只在 App 端生效：H5 的返回是浏览器历史，小程序由微信管。
import { onBackPress } from "@dcloudio/uni-app";
import { t } from "@/i18n";

const WINDOW_MS = 2000;

export function useDoubleBackExit(): void {
  // #ifdef APP-PLUS
  let last = 0;
  onBackPress((e) => {
    // 只接管物理返回键 / 手势；页面自己调 navigateBack 的不管
    if (e?.from && e.from !== "backbutton") return false;
    const now = Date.now();
    if (now - last < WINDOW_MS) {
      (globalThis as unknown as { plus?: { runtime: { quit: () => void } } }).plus?.runtime.quit();
      return true;
    }
    last = now;
    uni.showToast({ title: t("common.pressAgainToExit"), icon: "none" });
    return true;
  });
  // #endif
}
