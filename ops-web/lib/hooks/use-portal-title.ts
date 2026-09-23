"use client";

// 门户标题覆盖的薄 hook：只是把 nav 的纯函数接上当前路由与登录角色。
// 判定逻辑全部在 lib/nav.ts#portalTitleOverride（可单测），这里不写任何规则。
import { usePathname } from "next/navigation";
import { useAuth } from "../auth";
import { portalTitleOverride } from "../nav";

/**
 * @param currentKey 页面当前 tab/view 的 key；无 tab 概念的页面（工作台）传 null
 * @param isDefault  当前是否处于页面默认 tab（无 tab 概念的页面恒 true）
 */
export function usePortalTitle(currentKey: string | null, isDefault: boolean): string | undefined {
  const pathname = usePathname();
  const role = useAuth((s) => s.role);
  return portalTitleOverride(role, pathname ?? "/", currentKey, isDefault);
}
