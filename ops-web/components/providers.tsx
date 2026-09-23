"use client";

import { QueryClient, QueryClientProvider, MutationCache } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useTheme, applyTheme } from "@/lib/stores/theme";
import { useLocaleStore, applyLocale } from "@/lib/stores/locale";
import { notify } from "@/lib/notify";
import { IS_MOCK } from "@/lib/api-mode";
import { Toaster } from "@/components/ui/toaster";

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
