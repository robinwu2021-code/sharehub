"use client";

import { useAuth } from "./auth";
import { can } from "./permissions";

/** 页面内按钮级鉴权：const allow = useCan(); allow('order:refund:audit')。 */
export function useCan() {
  const role = useAuth((s) => s.role);
  return (code: string) => can(role, code);
}
