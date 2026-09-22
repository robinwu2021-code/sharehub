"use client";

// 页内 tab ↔ URL 的同步。
//
// ## 为什么需要它
//
// 整理前 18 / 29 个页面在重复同一段：
//
//   const sp = useSearchParams();
//   const qTab = sp.get("tab");
//   const [tab, setTab] = useState(TABS.some(t => t.key === qTab) ? qTab : TABS[0].key);
//   <TabHeader onChange={(k) => { setTab(k); setPage(1); }} />
//
// 复制粘贴的代价不只是行数：
//
//  1. **漏掉 `setPage(1)`** —— 从第 3 页切到另一个 tab，还停在第 3 页，
//     而那个 tab 只有 1 页：列表空白，看着像没数据。
//  2. **从 URL 进来的那一路没人复位** —— 上面那段只在 `useState` 初值里读一次 `qTab`，
//     点侧边菜单换 tab（URL 变了但组件不重挂）时 **tab 根本不跟着变**。
//  3. **`useSearchParams` 在静态导出下必须包 `<Suspense>`**，漏一个构建期才炸。
//     收进 hook 之后至少只剩一处要记得。
import * as React from "react";
import { useSearchParams } from "next/navigation";
import { navTabs, type PageTabSpec } from "@/lib/nav";
import { useAuth } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";
import type { Phase } from "@/lib/phase";

export interface PageTab {
  key: string;
  label: string;
  phase?: Phase;
}

/**
 * 取本页 tab（名字与权限都来自 nav.ts，页面不再自己写一份文案）。
 * 切语言由 `tNav` 负责 —— 与左侧菜单同一条翻译链路，不会出现菜单已翻、tab 还是中文。
 */
export function useNavTabs(path: string, specs: readonly PageTabSpec[]): PageTab[] {
  const role = useAuth((s) => s.role);
  const { tNav } = useI18n();
  return React.useMemo(
    () => navTabs(path, specs, role).map((t) => ({ ...t, label: tNav(t.label) })),
    // specs 是页面模块级常量，引用稳定；列进依赖会让 useMemo 每次都失效
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [path, role, tNav],
  );
}

/**
 * tab 状态 + URL 同步。
 *
 * @param onChange 切 tab 时的副作用，**分页页一律传 `paging.reset`** ——
 *                 不传的后果见文件头第 1 条
 *
 * 调用方所在的组件必须在 `<Suspense>` 内（静态导出 + `useSearchParams` 的硬要求）。
 */
export function usePageTab(tabs: PageTab[], onChange?: () => void) {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const fallback = tabs[0]?.key ?? "";
  const valid = React.useCallback((k: string | null): k is string => !!k && tabs.some((t) => t.key === k), [tabs]);
  const [tab, setTabState] = React.useState(() => (valid(qTab) ? qTab : fallback));

  // URL 变了（点菜单换 tab，组件不重挂）也要跟上 —— 这正是原来那段漏掉的一路
  React.useEffect(() => {
    if (valid(qTab) && qTab !== tab) {
      setTabState(qTab);
      onChange?.();
    }
    // onChange 多为内联函数，列进依赖会每次触发；这里只关心 qTab 的变化
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [qTab, valid]);

  // 权限或 phase 变化导致当前 tab 消失时，回落到第一个可见 tab（否则整页内容空白）
  React.useEffect(() => {
    if (tabs.length && !valid(tab)) setTabState(fallback);
  }, [tabs, tab, valid, fallback]);

  const setTab = React.useCallback((k: string) => {
    setTabState(k);
    onChange?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return { tab, setTab };
}
