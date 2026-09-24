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
import { useViewer } from "./use-viewer";
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
export function useNavTabs(
  path: string, specs: readonly PageTabSpec[], defaultKey?: string,
): PageTab[] {
  const role = useAuth((s) => s.role);
  const viewer = useViewer();
  const { tNav } = useI18n();
  return React.useMemo(
    () => navTabs(path, specs, viewer, defaultKey).map((t) => ({ ...t, label: tNav(t.label) })),
    // specs 是页面模块级常量，引用稳定；列进依赖会让 useMemo 每次都失效
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [path, role, tNav, defaultKey],
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
export interface PageTabOptions {
  /**
   * 页内切换参数：多数页 `?tab=`，工单页 `?view=`。菜单里那条叶子用哪个，这里就得用哪个 ——
   * 读错的表现是深链点进去永远落在默认 tab。
   */
  param?: "tab" | "view";
  /**
   * URL 没带 tab 时落在哪一个。
   *
   * **不传就是列表里的第一个**，而这对少数页面是错的：`/marketing` 的默认随分期变
   * （阶段 1 公告、阶段 2 起优惠券），tab 顺序却固定。漏传的表现很隐蔽 ——
   * 面包屑按菜单显示「优惠券」，页面内容却是公告，两者对不上
   * （2026-09-23 浏览器实测撞到过）。
   */
  defaultKey?: string;
}

export function usePageTab(tabs: PageTab[], onChange?: () => void, opts: PageTabOptions = {}) {
  const { param = "tab", defaultKey } = opts;
  const sp = useSearchParams();
  const qTab = sp.get(param);
  const fallback = (defaultKey && tabs.some((t) => t.key === defaultKey) ? defaultKey : tabs[0]?.key) ?? "";
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

/**
 * 分页翻页时保留上一页数据（不闪白），**但换 tab 时不保留**。
 *
 * <h3>为什么需要它</h3>
 * 这些页面是「一个 `useQuery` 喂多个 tab」：`tab` 在 `queryKey` 里，
 * `queryFn` 按 tab 分流。换 tab 得到的是一个**新的 key**，
 * 而 `placeholderData: keepPreviousData` 的语义是「key 变了就把上一次的数据先顶上」——
 * 它分不出「翻页」和「换 tab」。
 *
 * 于是切 tab 的那一帧，**新 tab 的列拿着上一个 tab 的行**去渲染：
 * `rowKey` 全是 undefined（React 报重复 key），`StatusBadge` 查不到状态直接抛
 * （`Cannot read properties of undefined (reading 'tone')`）。
 * 2026-09-24 在财务页实测复现：从「分润规则」点到「发票」，dev 下弹红遮罩。
 *
 * <h3>为什么不是干脆去掉 keepPreviousData</h3>
 * 翻页时它是对的 —— 去掉之后每翻一页表格都会先塌成空态再撑开，那是另一种难受。
 * 要分的是「同一个 tab 内翻页」与「换了 tab」，而不是全有或全无。
 *
 * 用法：`placeholderData: keepWithinTab(tab)` 替换 `placeholderData: keepPreviousData`。
 */
export function keepWithinTab<T>(tab: string) {
  return (prev: T | undefined, prevQuery?: { queryKey: readonly unknown[] }): T | undefined =>
    // 约定：这些页面的 queryKey 形如 [域名, tab, …]，tab 固定在第 2 位。
    // 取不到上一次的 key（首次加载）时一律不顶 —— 顶错的代价远大于闪一下。
    prevQuery && prevQuery.queryKey[1] === tab ? prev : undefined;
}
