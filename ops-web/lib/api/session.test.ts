// 会话失效与登出的单测。这三条边界都是"写得看起来对、实际把功能吃掉"的那类：
//   ① 登录密码错也是 401 —— 当成会话失效会把错误提示变成一次跳转；
//   ② 401 跳登录，而登录页自己的请求再 401 → 无限跳；
//   ③ 登出必须**先**请后端吊销再清本地，反过来就没有 Authorization 头可发。
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { isSessionSensitive, sessionExpired, signOut, LOGIN_PATH } from "./session";
import { useAuth } from "../auth";

const logged = () => useAuth.setState({ username: "admin", role: "ADMIN", token: "T1", agentNo: "" });

/** 造一个最小 window，node 环境下没有。返回跳转目标（null = 没跳）。 */
function stubWindow(pathname: string) {
  let to: string | null = null;
  (globalThis as unknown as { window: unknown }).window = {
    location: { pathname, replace: (u: string) => { to = u; } },
  };
  return () => to;
}

afterEach(() => {
  delete (globalThis as unknown as { window?: unknown }).window;
  useAuth.setState({ username: "", role: "", token: "", agentNo: "" });
});

describe("isSessionSensitive", () => {
  it("登录接口的 401 不算会话失效 —— 否则密码输错会变成跳转，用户看不到「密码错误」", () => {
    expect(isSessionSensitive("/api/auth/login")).toBe(false);
  });

  it("登出接口的 401 也不算 —— 本来就在退出，再跳一次没有意义", () => {
    expect(isSessionSensitive("/api/auth/logout")).toBe(false);
  });

  it("带查询串也要认得出（client.get 会把 ?a=b 拼进 path 再传进来）", () => {
    expect(isSessionSensitive("/api/auth/login?from=x")).toBe(false);
  });

  it("业务接口的 401 就是会话失效", () => {
    expect(isSessionSensitive("/api/ops/dashboard")).toBe(true);
    expect(isSessionSensitive("/api/ops/orders")).toBe(true);
  });
});

describe("sessionExpired", () => {
  it("清本地会话 —— 不清的话 token 还在，loggedIn() 仍为真，外壳照常渲染", () => {
    logged();
    stubWindow("/orders");
    sessionExpired();
    expect(useAuth.getState().token).toBe("");
    expect(useAuth.getState().role).toBe("");
  });

  it("跳登录页", () => {
    logged();
    const to = stubWindow("/orders");
    expect(sessionExpired()).toBe(true);
    expect(to()).toBe(LOGIN_PATH);
  });

  it("已经在登录页就不跳 —— 否则 401→跳登录→页面请求又 401→再跳，死循环", () => {
    logged();
    const to = stubWindow(LOGIN_PATH);
    expect(sessionExpired()).toBe(false);
    expect(to()).toBeNull();
    expect(useAuth.getState().token).toBe(""); // 本地仍然要清
  });

  it("服务端渲染时没有 window，不能因此抛异常", () => {
    logged();
    expect(() => sessionExpired()).not.toThrow();
  });
});

describe("signOut", () => {
  beforeEach(() => vi.resetModules());

  // ⚠️ resetModules 之后动态 import 的 session 拿到的是**新的** auth 模块实例，
  // 断言必须用同一次 import 出来的那个 store —— 用文件顶部那个旧实例会看到它纹丝不动，
  // 而那正是第一版测试红的原因（红得有理由：测试测错了对象）。
  const freshSession = async () => ({
    signOut: (await import("./session")).signOut,
    store: (await import("../auth")).useAuth,
  });

  it("先请后端吊销、再清本地 —— 顺序反了就没有 Authorization 头可发", async () => {
    let tokenWhenCalled: string | undefined;
    const { useAuth: ua } = await import("../auth");
    vi.doMock("./index", () => ({
      api: { logout: async () => { tokenWhenCalled = ua.getState().token; } },
    }));
    const { signOut: s, store } = await freshSession();

    store.setState({ username: "admin", role: "ADMIN", token: "T1", agentNo: "" });
    await s();

    expect(tokenWhenCalled).toBe("T1");       // 调后端时本地还留着令牌
    expect(store.getState().token).toBe("");  // 之后才清
  });

  it("后端吊销失败也要清本地 —— 点了退出就必须退出", async () => {
    vi.doMock("./index", () => ({
      api: { logout: async () => { throw new Error("网络不通"); } },
    }));
    const { signOut: s, store } = await freshSession();

    store.setState({ username: "admin", role: "ADMIN", token: "T1", agentNo: "" });
    await expect(s()).resolves.toBeUndefined();
    expect(store.getState().token).toBe("");
  });

  it("导出的 signOut 存在且是函数（防止重构时被改没）", () => {
    expect(typeof signOut).toBe("function");
  });
});
