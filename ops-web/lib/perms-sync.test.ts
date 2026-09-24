import { describe, it, expect, beforeEach, vi } from "vitest";
import { useAuth } from "./auth";
import { api } from "./api";
import { refreshPerms, resetPermsThrottle } from "./perms-sync";

/**
 * perms 与服务端对齐（A4）。
 *
 * 这里验的是 **store 那一侧的闸**：`/me` 回来的权限该不该写、写进谁。
 * 「后端给的是当场重算的权限而不是登录快照」由后端用例
 * `PermsRefreshWithoutReloginTest` 守 —— 那是本功能的前提，
 * 前端再怎么定时拉，前提垮了就只是把同一份旧权限反复写回，且**看不出异常**。
 */
describe("perms 与服务端对齐", () => {
  const signIn = (perms: string[]) => {
    useAuth.getState().login({
      realm: "STAFF", subjectNo: "E001", username: "ops.user", role: "OPS",
      token: "t1", perms,
    });
    return useAuth.getState().operatorGen;
  };

  beforeEach(() => useAuth.getState().logout());

  it("新授的权要写进来", () => {
    const gen = signIn(["device:cabinet:read"]);
    useAuth.getState().syncPerms({ perms: ["device:cabinet:read", "order:order:read"] }, gen);
    expect(useAuth.getState().perms).toEqual(["device:cabinet:read", "order:order:read"]);
  });

  it("收了的权要真的少掉", () => {
    // 只加不减的话，被收权的人界面照旧，点下去才 403 —— 而那句「无权限」
    // 既不说是谁收的，也不说什么时候收的
    const gen = signIn(["device:cabinet:read", "order:order:read"]);
    useAuth.getState().syncPerms({ perms: ["device:cabinet:read"] }, gen);
    expect(useAuth.getState().perms).toEqual(["device:cabinet:read"]);
  });

  it("同一份权限不产生新数组——否则每次心跳都让判权组件重渲染", () => {
    const gen = signIn(["a", "b"]);
    const before = useAuth.getState().perms;
    useAuth.getState().syncPerms({ perms: ["a", "b"] }, gen);
    expect(useAuth.getState().perms).toBe(before);
  });

  it("世代号对不上就丢弃——那是上一个身份的响应", () => {
    // 切主体瞬间在途的 /me 回来会把上一家的权限写进新主体：
    // 界面照常渲染，只是多出/少掉几个入口，没有任何报错
    const stale = signIn(["old:perm"]);
    useAuth.getState().login({
      realm: "AGENT", subjectNo: "A001", username: "agent", role: "AGENT",
      token: "t2", perms: ["new:perm"],
    });
    useAuth.getState().syncPerms({ perms: ["old:perm", "extra"] }, stale);
    expect(useAuth.getState().perms).toEqual(["new:perm"]);
  });

  it("登出后不再写——否则 localStorage 里躺着一份没有会话的权限", () => {
    const gen = signIn(["a"]);
    useAuth.getState().logout();
    useAuth.getState().syncPerms({ perms: ["a", "b"] }, gen);
    expect(useAuth.getState().perms).toEqual([]);
  });

  it("role 跟着刷——菜单分组按的是它", () => {
    const gen = signIn(["a"]);
    useAuth.getState().syncPerms({ perms: ["a"], role: "FINANCE" }, gen);
    expect(useAuth.getState().role).toBe("FINANCE");
  });

  it("跨 realm 的 role 不跟——/me 不带 memberships，写了就是个没有主体的代理", () => {
    // 那样的身份：切换器空白、按主体收敛的列表全空，而这些都不报错
    const gen = signIn(["a"]);
    useAuth.getState().syncPerms({ perms: ["a"], role: "AGENT" }, gen);
    expect(useAuth.getState().role).toBe("OPS");
  });
});

describe("refreshPerms（拉 /me 并写回）", () => {
  const signIn = (perms: string[]) => {
    useAuth.getState().login({
      realm: "STAFF", subjectNo: "E001", username: "ops.user", role: "OPS",
      token: "t1", perms,
    });
  };

  beforeEach(() => {
    useAuth.getState().logout();
    resetPermsThrottle();
    vi.restoreAllMocks();
  });

  const mockMe = (v: unknown) =>
    vi.spyOn(api, "me").mockResolvedValue(v as Awaited<ReturnType<typeof api.me>>);

  it("服务端多给了权限 → 写回", async () => {
    signIn(["device:cabinet:read"]);
    mockMe({ authenticated: true, perms: ["device:cabinet:read", "order:order:read"] });
    expect(await refreshPerms(1_000_000)).toBe("written");
    expect(useAuth.getState().perms).toContain("order:order:read");
  });

  it("没登录不发请求——登录页上没有会话可刷新", async () => {
    const spy = mockMe({ authenticated: true, perms: ["x"] });
    expect(await refreshPerms(1_000_000)).toBe("no-session");
    expect(spy).not.toHaveBeenCalled();
  });

  it("一分钟内只拉一次", async () => {
    signIn(["a"]);
    const spy = mockMe({ authenticated: true, perms: ["a"] });
    expect(await refreshPerms(1_000_000)).toBe("unchanged");
    expect(await refreshPerms(1_030_000)).toBe("throttled");
    expect(await refreshPerms(1_061_000)).toBe("unchanged");
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it("响应里没有 perms 就不动——写成空等于全站入口消失", async () => {
    // 「什么都看不见」与「真的没权限」长得一模一样，没人查得出是刷新把它清了
    signIn(["a", "b"]);
    mockMe({ authenticated: true });
    expect(await refreshPerms(1_000_000)).toBe("malformed");
    expect(useAuth.getState().perms).toEqual(["a", "b"]);
  });

  it("服务端说没有这个会话 → 清本地", async () => {
    signIn(["a"]);
    mockMe({ authenticated: false });
    expect(await refreshPerms(1_000_000)).toBe("expired");
    expect(useAuth.getState().token).toBe("");
  });

  it("请求失败保留旧权限，且不占节流额度", async () => {
    // 断网时把人的权限清空或弹回登录页，代价远大于多用一会儿旧权限
    signIn(["a"]);
    vi.spyOn(api, "me").mockRejectedValueOnce(new Error("网络异常"));
    expect(await refreshPerms(1_000_000)).toBe("failed");
    expect(useAuth.getState().perms).toEqual(["a"]);

    mockMe({ authenticated: true, perms: ["a", "b"] });
    expect(await refreshPerms(1_000_100)).toBe("written");
  });
});
