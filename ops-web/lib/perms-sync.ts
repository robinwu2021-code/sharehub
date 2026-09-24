"use client";

import { useEffect, useRef } from "react";
import { api } from "./api";
import { useAuth } from "./auth";
import type { Role } from "./auth";
import { sessionExpired } from "./api/session";

/**
 * 让本地的 perms 跟上服务端。
 *
 * <h3>修的是什么</h3>
 * perms 此前**只在登录与切主体时**写入。运营改了某个角色的权限之后，
 * 受影响的人不重新登录就一直用旧的那份：
 *
 * - **多给的看不见** —— 后端已经放行，界面上却没有入口。这是真正的功能损失：
 *   当事人只会觉得「说给我开了，可我这儿没有」。
 * - **少给的还点得动** —— 按钮仍在，点下去后端 403。**不是安全漏洞**
 *   （闸在服务端，后端用例 `PermsRefreshWithoutReloginTest` 验过收权后同一个 token 被拒），
 *   但用户看到的是一个能点的按钮弹出「无权限」，没人知道该找谁。
 *
 * <h3>为什么不用 react-query 的 refetchOnWindowFocus</h3>
 * `components/providers.tsx` 里有一句 `focusManager.setFocused(true)` ——
 * 把「是否聚焦」**永久钉成 true**（为修另一个 bug，见那里的注释）。
 * 于是 react-query 再也收不到「窗口重新聚焦」这个事件，
 * 挂 `refetchOnWindowFocus` 会**一次都不触发，而且完全没有报错**。
 * 所以这里直接听 `visibilitychange`。
 *
 * <h3>为什么判断逻辑不在 hook 里</h3>
 * 本仓库的测试跑在 node 环境、不装 jsdom（见 vitest.config）。
 * 逻辑留在 hook 里等于**没有一条分支测得到** —— 而这里的分支恰恰都是
 * 「错了也不报错」那一类：世代过期、字段缺失、节流。
 * 所以判断全在 {@link refreshPerms}，hook 只负责挂触发器。
 */

/** 两次刷新的最小间隔。切来切去不该变成对 `/me` 的连续请求。 */
export const MIN_INTERVAL_MS = 60_000;

/** 一次刷新的结局。返回它只为可测 —— 调用方不看。 */
export type RefreshOutcome =
  | "written"      // 权限变了，已写回 store
  | "unchanged"    // 与本地一致
  | "throttled"    // 距上次不足 MIN_INTERVAL_MS
  | "no-session"   // 本地没有登录态，不该发请求
  | "expired"      // 服务端说这个会话不存在
  | "malformed"    // 响应里没有 perms 数组
  | "failed";      // 请求出错（401 已由 http-client 转成会话失效）

let lastAt = 0;

/** 身份变了（登录 / 切主体 / 登出）就清节流：新身份的第一次对齐不该被跳过。 */
export function resetPermsThrottle(): void {
  lastAt = 0;
}

/**
 * 拉一次 `/me` 并把权限码写回 store。
 *
 * @param now 注入时钟，只为让节流可测（生产传默认值）
 */
export async function refreshPerms(now: number = Date.now()): Promise<RefreshOutcome> {
  const auth = useAuth.getState();
  if (!auth.token) return "no-session";
  if (now - lastAt < MIN_INTERVAL_MS) return "throttled";
  lastAt = now;
  // 发请求**之前**取世代号：之后再取的话，切主体正好发生在这两行之间时
  // 拿到的是新世代，这份旧响应就会被当成新身份的写进去。
  const gen = auth.operatorGen;
  try {
    const me = await api.me();
    /*
     * 令牌失效走的是 **401** → http-client 统一转 sessionExpired（后端用例验过实际就是 401）。
     * 这里的 `authenticated === false` 是另一种可能：后端认为没有会话但仍回 200。
     * 真出现时也该回登录页 —— 留着本地那份权限只会让界面显示一堆点下去全是 401 的入口。
     */
    if (!me.authenticated) {
      sessionExpired();
      return "expired";
    }
    // perms 缺字段 ≠ 零权限。写成 [] 会让整站入口消失，而「什么都看不见」
    // 与「真的没权限」长得一模一样 —— 宁可不动，等下一次。
    if (!Array.isArray(me.perms)) return "malformed";
    const before = useAuth.getState().perms;
    useAuth.getState().syncPerms({ perms: me.perms, role: (me.role ?? "") as Role | "" }, gen);
    return useAuth.getState().perms === before ? "unchanged" : "written";
  } catch {
    // 静默：401 已由 http-client 处理，其余（断网、后端抖动）保留现有 perms 继续用。
    // 为一次刷新失败把人弹回登录页，代价远大于多用一会儿旧权限。
    lastAt = 0; // 失败不占节流额度，下次可见时立刻再试
    return "failed";
  }
}

/**
 * 挂在 AppShell 上：登录态存在时保持 perms 与服务端一致。
 *
 * 触发时机只有两个 —— **进入应用**与**标签页重新可见**。不设轮询：
 * 权限变更是低频动作，而轮询会让每个开着后台的浏览器持续打后端；
 * 「切回标签页就是最新的」对运营来说已经够用。
 */
export function usePermsSync(): void {
  const loggedIn = useAuth((s) => s.loggedIn());
  const operatorGen = useAuth((s) => s.operatorGen);
  const lastGen = useRef(-1);

  useEffect(() => {
    if (!loggedIn) return;
    if (lastGen.current !== operatorGen) {
      lastGen.current = operatorGen;
      resetPermsThrottle();
    }
    void refreshPerms();

    const onVisible = () => {
      if (document.visibilityState === "visible") void refreshPerms();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
    // operatorGen 进依赖：切主体后重新对齐一次。切主体响应本来就自带 perms，
    // 这里是兜底 —— 两者一致时 syncPerms 同值不写，不会多一次渲染。
  }, [loggedIn, operatorGen]);
}
