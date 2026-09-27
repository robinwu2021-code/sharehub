/**
 * 借出前的登录门禁（C-RT-02「未登录先登录」）。
 *
 * 此前首页的扫码与「借」都不看登录态：游客一路点到确认页，`submit()` 时吃 401，
 * 而 `installSessionGuard` 会 **reLaunch 到登录页** —— 柜机号、选好的券、
 * 免押还是押金，全部丢失；登录完落回首页，要从扫码重来一遍。
 * 401 那条路是为「会话过期」准备的，用它兜「本来就没登录」是把一次可预期的跳转
 * 办成了一次数据丢失。
 *
 * 所以门禁前置，并且**带着回跳地址走** —— 登录完回到他原本要去的那一页。
 */
import { useUserStore } from "@/stores/user";

/**
 * 确认已登录；未登录则跳登录页并记下回跳地址，返回 false。
 *
 * @param redirect 登录成功后要去的页面（含参数），如 `/pages/order/confirm?cabinetNo=CAB-1`
 */
export function ensureLoggedIn(redirect: string): boolean {
  if (useUserStore().isLoggedIn) return true;
  uni.navigateTo({ url: `/pages/login/index?redirect=${encodeURIComponent(redirect)}` });
  return false;
}
