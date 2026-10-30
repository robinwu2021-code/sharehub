import { describe, it, expect } from "vitest";
import { inventoryRedirect, INVENTORY_TAB_KEYS } from "./inventory-routes";
import { NAV } from "./nav";

describe("库存管理路由（V209）", () => {
  it("/devices 上的库存页签转到 /inventory，调拨键改名，其余参数保留", () => {
    expect(inventoryRedirect(new URLSearchParams("tab=inventory&no=TR1"))).toBe("/inventory?tab=transfers&no=TR1");
    expect(inventoryRedirect(new URLSearchParams("tab=issued"))).toBe("/inventory?tab=issued");
    expect(inventoryRedirect(new URLSearchParams("tab=monitor"))).toBeNull();
    expect(inventoryRedirect(new URLSearchParams(""))).toBeNull();
  });
  it("页签键与菜单叶子一一对应、同序", () => {
    const leaves = NAV.find((s) => s.key === "inventory")!.children!.map((l) => new URL(l.href, "http://x").searchParams.get("tab"));
    expect(leaves).toEqual([...INVENTORY_TAB_KEYS]);
  });
});
