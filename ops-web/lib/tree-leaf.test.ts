// 树原语的纯逻辑部分：叶子收集。
// 放在 lib/ 而不是组件旁边，是因为 vitest.config 的 include 只覆盖 `lib/**/*.test.ts`——
// 搁 components/ 下的用例一条都不会跑（等于没写）。
//
// 父节点的三态勾选（全选/半选/未选）和「点父节点改哪些值」全建立在这函数上：
// 少收一个叶子，父节点就永远停在半选态，点了也全勾不上。
import { describe, expect, it } from "vitest";
import { leafKeysOf, type TreeNode } from "@/components/ui/tree";

const t: TreeNode = {
  key: "m:order",
  label: "订单交易",
  children: [
    {
      key: "r:order:order", label: "订单",
      children: [{ key: "order:order:read", label: "查" }, { key: "order:order:export", label: "导出" }],
    },
    { key: "r:order:refund", label: "退款", children: [{ key: "order:refund:apply", label: "申请" }] },
  ],
};

describe("leafKeysOf", () => {
  it("只收叶子，不收中间层（勾选值只认权限码本身）", () => {
    expect(leafKeysOf(t)).toEqual(["order:order:read", "order:order:export", "order:refund:apply"]);
  });

  it("叶子自己 → 就是它自己", () => {
    expect(leafKeysOf({ key: "order:order:read", label: "查" })).toEqual(["order:order:read"]);
  });

  it("children 为空数组也算叶子（不返回空列表，否则父节点算不出勾选态）", () => {
    expect(leafKeysOf({ key: "x", label: "x", children: [] })).toEqual(["x"]);
  });
});
