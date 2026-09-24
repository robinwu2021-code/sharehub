import { describe, it, expect, beforeEach } from "vitest";
import { useMenuStore, toNavSections } from "./menu-source";
import { NAV, navTree, visibleSections } from "./nav";
import type { MenuNode } from "./api/contracts/dashboard";

/**
 * 服务端菜单接进来之后的两件事：**映射对不对**、**拿不到时兜不兜得住**。
 *
 * <p>浏览器里验不了「走的是哪条路」——两条路本来就该给出同样的菜单，
 * 一致恰恰是验收标准。所以用注入的树来验：给一棵**能认出来**的树，
 * 看界面读到的是不是它。
 */

const node = (over: Partial<MenuNode> & Pick<MenuNode, "menuNo" | "name">): MenuNode => ({
  parentNo: null, nameEn: null, nameAr: null, type: "MENU", path: "/x", icon: "Box",
  group: null, sort: 1, perm: null, phase: 1, ready: false, module: "device",
  modules: [], match: [], pinBottom: false, portalFor: [], children: [], ...over,
});

describe("服务端菜单 → 前端结构", () => {
  beforeEach(() => useMenuStore.getState().set(null));

  it("menuNo 去掉 M_ 前缀就是 section 的 key——路由归属靠它", () => {
    const [s] = toNavSections([node({ menuNo: "M_device", name: "设备管理" })]);
    expect(s.key).toBe("device");
  });

  it("叶子的分组标题要带过来——丢了二级面板就变成一长串平铺", () => {
    const [s] = toNavSections([node({
      menuNo: "M_finance", name: "财务管理",
      children: [node({ menuNo: "M_finance__1", parentNo: "M_finance", name: "分润规则",
        type: "ITEM", path: "/finance", group: "分润与结算", perm: "finance:share_rule:read" })],
    })]);
    expect(s.children?.[0].group).toBe("分润与结算");
    expect(s.children?.[0].perm).toBe("finance:share_rule:read");
  });

  it("phase=1 不写进对象——它是缺省值，写了会让「有没有显式分期」看不出来", () => {
    const [s] = toNavSections([node({
      menuNo: "M_a", name: "A",
      children: [node({ menuNo: "M_a__1", parentNo: "M_a", name: "叶", type: "ITEM", phase: 1 })],
    })]);
    expect(s.children?.[0]).not.toHaveProperty("phase");
  });

  it("soon 由前端算，不由服务端给——它取决于这份产物连的是 mock 还是真后端", () => {
    // 库里没有 soon 这一列（刻意），/operation/<page> 的就绪度要在映射时重新施加
    const [s] = toNavSections([node({
      menuNo: "M_operation", name: "运营管理", module: "location",
      children: [node({ menuNo: "M_operation__1", parentNo: "M_operation", name: "站点概览",
        type: "ITEM", path: "/operation/overview" })],
    })]);
    // mock 模式下一切就绪，所以不该被标 soon
    expect(s.children?.[0].soon).toBeUndefined();
  });

  it("门户角色数组要带过来——丢了代理门户就对所有人可见", () => {
    const [s] = toNavSections([node({ menuNo: "M_my-biz", name: "我的经营", portalFor: ["AGENT"] })]);
    expect(s.portalFor).toEqual(["AGENT"]);
  });
});

describe("界面读哪棵树", () => {
  beforeEach(() => useMenuStore.getState().set(null));

  it("没有服务端菜单时用本地那份", () => {
    expect(navTree()).toBe(NAV);
  });

  it("有服务端菜单时用它——这是「切数据源」真的生效的判据", () => {
    const injected = toNavSections([node({ menuNo: "M_only", name: "只有这一个" })]);
    useMenuStore.getState().set(injected);
    expect(navTree()).toBe(injected);
    expect(navTree().map((s) => s.label)).toEqual(["只有这一个"]);
  });

  it("注入的树真的决定了界面看到什么，而不只是被存起来", () => {
    useMenuStore.getState().set(toNavSections([node({
      menuNo: "M_solo", name: "独苗", module: "device",
      children: [node({ menuNo: "M_solo__1", parentNo: "M_solo", name: "叶",
        type: "ITEM", path: "/x", perm: "device:cabinet:read" })],
    })]));
    const seen = visibleSections({ role: "ADMIN", perms: ["*"] });
    expect(seen.map((s) => s.key)).toEqual(["solo"]);
  });

  it("清空后立刻回落到本地——登出/关开关时不能留着上一个人的菜单", () => {
    useMenuStore.getState().set(toNavSections([node({ menuNo: "M_x", name: "临时" })]));
    useMenuStore.getState().set(null);
    expect(navTree()).toBe(NAV);
  });
});
