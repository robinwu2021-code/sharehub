"use client";

import { useEffect } from "react";
import { api } from "./api";
import { useAuth } from "./auth";
import { dynamicMenuEnabled, useMenuStore, toNavSections } from "./menu-source";

/**
 * 登录后拉一次服务端菜单，写进 {@link useMenuStore}。
 *
 * <h3>失败一律兜底，不阻断</h3>
 * 拉不到、拿到空树、格式不对 —— 都保持 `tree = null`，界面回落到本地 `NAV`。
 * 菜单没有就整站没有入口，而那是运营唯一能操作的东西：
 * **「用一份可能略旧的菜单」远好过「什么都点不了」**。
 *
 * <h3>为什么不放进 react-query</h3>
 * 它要在 `AppShell` 里、在任何页面挂载之前就开始；而且失败时的行为不是重试，
 * 是「安静地用本地那份」。放进 query 还要额外压掉它的重试与错误冒泡。
 *
 * <h3>换人要重拉</h3>
 * `operatorGen` 进依赖：登录、登出、切主体都会推进它，而这三件事都换了可见菜单。
 */
export function useMenuTree(): void {
  const loggedIn = useAuth((s) => s.loggedIn());
  const operatorGen = useAuth((s) => s.operatorGen);

  useEffect(() => {
    const store = useMenuStore.getState();
    if (!loggedIn || !dynamicMenuEnabled()) {
      store.set(null); // 退出或关掉开关时，别留着上一个人的菜单
      return;
    }
    let cancelled = false;
    store.setLoading(true);
    api.getMenus()
      .then((nodes) => {
        if (cancelled) return;
        // 空树当失败处理：服务端要是因为某个 bug 把人过滤光了，
        // 结果会是一个没有任何入口的后台 —— 那种时候本地兜底更有用
        useMenuStore.getState().set(nodes?.length ? toNavSections(nodes) : null);
      })
      .catch(() => {
        if (!cancelled) useMenuStore.getState().set(null);
      });
    return () => { cancelled = true; };
  }, [loggedIn, operatorGen]);
}
