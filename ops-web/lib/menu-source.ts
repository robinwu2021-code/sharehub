"use client";

import { create } from "zustand";
import type { NavSection, NavLeaf } from "./nav";
import { pageReady, type OperationPage } from "./backend-ready";
import type { MenuNode } from "./api/contracts/dashboard";

/**
 * 菜单数据源：本地 `nav.ts` 还是服务端 `GET /api/auth/menus`。
 *
 * <h2>为什么一定要有开关</h2>
 * 菜单是**全站唯一入口**。切错了整个后台不可用，而前端是静态产物、
 * 回滚要重新发版。所以默认走本地，开关打开才走服务端 ——
 * 先在生产上用开关自己验过，再改默认值。
 *
 * 这不是新想的：[前端-动态菜单权限接入指引](../../docs/technical/前端-动态菜单权限接入指引.md) §3
 * 早就写了「只加不改 + 静态兜底 + 开关灰度」。
 *
 * <h2>两级开关</h2>
 * 1. **构建期** `NEXT_PUBLIC_DYNAMIC_MENU=1` —— 决定这份产物的默认行为；
 * 2. **浏览器** `localStorage['pb-ops-dynamic-menu'] = '1' | '0'` —— 按浏览器覆盖默认值。
 *
 * 第二级才是真正能「先自己验」的那个：不改产物、不影响别人，
 * 在生产上用自己的浏览器打开它，看菜单对不对，再决定要不要改默认。
 *
 * <h2>兜底</h2>
 * 拉取失败、返回空树、字段缺失 —— 一律**继续用本地那份**。
 * 菜单拿不到就整站没有入口，而那是运营唯一能操作的东西；
 * 「用一份可能略旧的菜单」比「什么都点不了」好得多。
 */

const LS_KEY = "pb-ops-dynamic-menu";
const BUILD_DEFAULT = process.env.NEXT_PUBLIC_DYNAMIC_MENU === "1";

/** 这次会话要不要走服务端菜单。浏览器覆盖优先于构建期默认值。 */
export function dynamicMenuEnabled(): boolean {
  if (typeof window === "undefined") return false; // SSR/静态导出阶段一律本地
  try {
    const v = localStorage.getItem(LS_KEY);
    if (v === "1") return true;
    if (v === "0") return false;
  } catch {
    // 隐私模式 / 站点数据被清 —— 读不到就按构建期默认值走
  }
  return BUILD_DEFAULT;
}

interface MenuState {
  /** 服务端菜单；null = 还没到 / 没启用 / 拉失败（此时用本地那份）。 */
  tree: NavSection[] | null;
  /** 启用了但还没拿到结果 —— 外壳据此决定是不是先别渲染导航。 */
  loading: boolean;
  set: (tree: NavSection[] | null) => void;
  setLoading: (v: boolean) => void;
}

export const useMenuStore = create<MenuState>((set) => ({
  tree: null,
  loading: false,
  set: (tree) => set({ tree, loading: false }),
  setLoading: (loading) => set({ loading }),
}));

/**
 * 服务端的 `MenuNode` 树 → 前端的 `NavSection[]`。
 *
 * 字段是一一对应的（V67 起刻意如此），只有两处要在前端重新算：
 * - **`soon`**：由 {@link pageReady} 算，取决于这份产物连的是 mock 还是真后端 ——
 *   那是**构建的属性**，不是菜单的属性，所以库里没有这一列；
 * - **`key`**：服务端的 `menuNo` 形如 `M_<key>`，去掉前缀即可。
 */
export function toNavSections(nodes: MenuNode[]): NavSection[] {
  return nodes.map((n) => {
    const key = n.menuNo.replace(/^M_/, "");
    const children: NavLeaf[] = (n.children ?? []).map((c) => {
      const leaf: NavLeaf = {
        href: c.path ?? "",
        label: c.name,
        ...(c.perm ? { perm: c.perm } : {}),
        ...(c.phase && c.phase > 1 ? { phase: c.phase as NavLeaf["phase"] } : {}),
        ...(c.group ? { group: c.group } : {}),
        ...(c.ready ? { ready: true } : {}),
        ...(c.nameEn ? { labelEn: c.nameEn } : {}),
        ...(c.nameAr ? { labelAr: c.nameAr } : {}),
      };
      // /operation/<page> 的叶子：后端就绪度是构建期常量，服务端不知道
      const m = /^\/operation\/([\w-]+)/.exec(leaf.href);
      if (m && !pageReady(m[1] as OperationPage)) leaf.soon = true;
      return leaf;
    });
    return {
      key,
      label: n.name,
      icon: n.icon ?? "",
      module: n.module ?? "",
      href: n.path ?? "",
      ...(n.modules?.length ? { modules: n.modules } : {}),
      ...(n.match?.length ? { match: n.match } : {}),
      ...(n.perm ? { perm: n.perm } : {}),
      ...(n.phase && n.phase > 1 ? { phase: n.phase as NavSection["phase"] } : {}),
      ...(n.pinBottom ? { pinBottom: true } : {}),
      ...(n.portalFor?.length ? { portalFor: n.portalFor as NavSection["portalFor"] } : {}),
      ...(n.nameEn ? { labelEn: n.nameEn } : {}),
      ...(n.nameAr ? { labelAr: n.nameAr } : {}),
      children,
    };
  });
}
