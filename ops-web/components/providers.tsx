"use client";

import { QueryClient, QueryClientProvider, MutationCache, focusManager } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useTheme, applyTheme } from "@/lib/stores/theme";
import { useLocaleStore, applyLocale } from "@/lib/stores/locale";
import { notify } from "@/lib/notify";
import { IS_MOCK } from "@/lib/api-mode";
import { Toaster } from "@/components/ui/toaster";

/*
 * **不要因为标签页不在前台就挂起重试。**
 *
 * React Query 的 retryer 有一条 `canContinue = focusManager.isFocused() && …`：
 * 标签页隐藏时，失败的重试会被挂起，此时
 * `status` 停在 "pending"、`isLoading` 是 false、`error` 是 null ——
 * 于是页面上「加载中」「失败」「有数据」三个分支**一个都不成立**，渲染出来是**一片空白**。
 *
 * 2026-09-23 在生产上实测到：会话过期后打开站点概览，标签页隐藏时整页只剩标题，
 * 切到可见才出现「登录已失效」。运营看到的是白板，既不知道该重试还是该重新登录。
 *
 * 这里把「是否聚焦」固定为 true：重试在后台照常跑完，该报错就报错。
 * 代价只是隐藏标签页里多跑一次重试（retry: 1）。
 * `refetchOnWindowFocus` 本就关着，所以这个覆盖不会带来别的行为变化。
 */
focusManager.setFocused(true);

export function Providers({ children }: { children: React.ReactNode }) {
  const [qc] = useState(
    () =>
      new QueryClient({
        // 所有 mutation 失败统一 toast（消息已本地化：后端 message / mock i18n / ApiError）。
        mutationCache: new MutationCache({
          onError: (e) => notify.error(e instanceof Error ? e.message : String(e)),
        }),
        defaultOptions: {
          queries: {
            staleTime: 15_000,
            retry: 1,
            refetchOnWindowFocus: false,
            /*
             * mock 模式**不走网络**，没有理由按网络状态挂起取数。
             * 真后端保留 "online"：那边离线时挂起、等网络回来自动续跑，
             * 比立刻报一个「网络异常」更贴近实际。
             */
            networkMode: IS_MOCK ? "always" : "online",
          },
        },
      }),
  );
  // hydration 后对齐主题与语言（首帧脚本已抢先应用，避免闪烁）。
  const themeKey = useTheme((s) => s.themeKey);
  const locale = useLocaleStore((s) => s.locale);
  useEffect(() => applyTheme(themeKey), [themeKey]);
  useEffect(() => applyLocale(locale), [locale]);

  return (
    <QueryClientProvider client={qc}>
      {children}
      <Toaster />
    </QueryClientProvider>
  );
}
