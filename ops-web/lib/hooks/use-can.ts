"use client";

import { useAuth } from "../auth";
import { can } from "../permissions";

/**
 * 页面内按钮级鉴权：`const allow = useCan(); allow('order:refund:audit')`。
 *
 * **读 perms 不是 role** —— 后端下发的才是判权依据；role 只用于展示（「你是财务」）
 * 与菜单分组。多主体下同一个人在不同主体的权限不同，role 表达不了这个差异。
 */
export function useCan() {
  const perms = useAuth((s) => s.perms);
  return (code: string) => can(perms, code);
}
