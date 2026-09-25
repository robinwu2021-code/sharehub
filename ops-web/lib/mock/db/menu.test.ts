import { describe, it, expect } from "vitest";
import { allMenus, visibleMenus, updateMenu } from "./menu";
import { permissions } from "./org";

/**
 * 菜单管理的 mock **必须与后端同规则**。
 *
 * mock 放行而后端拒绝，离线调一路顺、切后端当场吃 500 ——
 * 这条坑本仓库踩过四次（saveShareRule / saveVenueOnboarding /
 * reviewVenueOnboarding / 两个详情端点），不再靠记性。
 *
 * 对端用例：backend `MenuAdminTest`。
 */
const catalog = () => permissions.map((p) => p.code);

describe("菜单管理（mock）", () => {
  it("改动能读回来——不是只改了界面上的一个 state", async () => {
    // 「保存看着成功、刷新就没了」是 mock 最常见的假象
    await updateMenu("M_device", { name: "设备中心" }, catalog());
    const again = await allMenus();
    expect(again.find((s) => s.menuNo === "M_device")?.name).toBe("设备中心");
    await updateMenu("M_device", { name: "设备管理" }, catalog());
  });

  it("挂目录外的权限码要拒——否则这菜单对谁都不可见且不报错", async () => {
    await expect(updateMenu("M_device", { perm: "device:nope:read" }, catalog()))
      .rejects.toThrow();
  });

  it("藏掉「员工与权限」要拒并回滚——那是回得来的唯一一扇门", async () => {
    await expect(updateMenu("M_org", { visible: 0 }, catalog())).rejects.toThrow();
    const tree = await visibleMenus(["*"], "ADMIN");
    expect(tree.map((s) => s.menuNo)).toContain("M_org");
  });

  it("藏别的可以，且真的从「我看得到的」那棵里消失", async () => {
    await updateMenu("M_marketing", { visible: 0 }, catalog());
    expect((await visibleMenus(["*"], "ADMIN")).map((s) => s.menuNo)).not.toContain("M_marketing");
    // 但管理树里还在 —— 否则藏了就再也找不回来
    expect((await allMenus()).map((s) => s.menuNo)).toContain("M_marketing");
    await updateMenu("M_marketing", { visible: 1 }, catalog());
  });

  it("不存在的菜单要拒，而不是凭空造一个", async () => {
    await expect(updateMenu("M_nope", { name: "凭空" }, catalog())).rejects.toThrow();
  });

  it("代理门户是排他的：代理只看门户，运营看不到门户", async () => {
    const agent = await visibleMenus(["dashboard:overview:read"], "AGENT");
    expect(agent.length).toBeGreaterThan(0);
    expect(agent.every((s) => s.menuNo.startsWith("M_my-"))).toBe(true);
    expect((await visibleMenus(["*"], "ADMIN")).some((s) => s.menuNo.startsWith("M_my-"))).toBe(false);
  });
});
