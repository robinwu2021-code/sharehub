"use client";

import { useMemo } from "react";
import { useAuth } from "../auth";
import type { Viewer } from "../permissions";

/**
 * 「谁在看」—— 菜单与 tab 的可见性入参。
 *
 * `perms` 判权（后端下发），`role` 只挑门户 section（`NavSection.portalFor`）。
 *
 * ⚠️ **必须 memo**：不 memo 的话每次渲染都是新对象，
 * 依赖它的 `useMemo`（如 `use-page-tab` 里的 tab 计算）每帧都会重算。
 */
export function useViewer(): Viewer {
  const perms = useAuth((s) => s.perms);
  const role = useAuth((s) => s.role);
  return useMemo(() => ({ perms, role }), [perms, role]);
}
