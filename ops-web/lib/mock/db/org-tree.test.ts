// 组织架构树（S6 / 拍板点 #4）的 mock 自洽性测试。
//
// 树形视图对数据的要求比列表严得多：列表里一条 parent 指错顶多显示得怪，
// 树里则是**整支子树凭空消失**（挂不到父节点）或**递归爆栈**（成环）。
// 这两件事在页面上都不会报错，只会让人以为「部门就这些」——所以钉在这里。
import { describe, expect, it, beforeEach } from "vitest";
import { departments, saveDepartment, listDepartments } from "./org";
import type { Department } from "../../types";

const snapshot = departments.map((d) => ({ ...d }));
beforeEach(() => {
  departments.splice(0, departments.length, ...snapshot.map((d) => ({ ...d })));
});

const byNo = () => new Map(departments.map((d) => [d.deptNo, d]));

describe("组织架构数据自洽", () => {
  it("parent 只能是空串（顶级）或真实存在的 deptNo —— 无孤儿", () => {
    const m = byNo();
    const orphans = departments.filter((d) => d.parent && !m.has(d.parent));
    expect(orphans.map((d) => `${d.deptNo}→${d.parent}`)).toEqual([]);
  });

  it("deptNo 唯一（树以它为 key，撞号会让 React 渲染错节点）", () => {
    const nos = departments.map((d) => d.deptNo);
    expect(nos).toEqual([...new Set(nos)]);
  });

  it("沿 parent 往上爬一定能到顶，不成环", () => {
    const m = byNo();
    for (const d of departments) {
      const seen = new Set<string>([d.deptNo]);
      let cur: Department | undefined = m.get(d.parent);
      while (cur) {
        expect(seen.has(cur.deptNo), `${d.deptNo} 的 parent 链成环于 ${cur.deptNo}`).toBe(false);
        seen.add(cur.deptNo);
        cur = m.get(cur.parent);
      }
    }
  });

  it("恰有一个顶级部门，且层级不止一层（否则树形毫无意义）", () => {
    expect(departments.filter((d) => !d.parent)).toHaveLength(1);
    const depth = (d: Department, m = byNo()): number => (d.parent ? 1 + depth(m.get(d.parent)!, m) : 0);
    expect(Math.max(...departments.map((d) => depth(d)))).toBeGreaterThanOrEqual(2);
  });

  it("列表接口不分页丢节点（树要整棵拉）", () => {
    expect(listDepartments({ page: 1, size: 500 }).list).toHaveLength(departments.length);
  });
});

describe("saveDepartment 守卫", () => {
  it("新增顶级部门：parent 留空即顶级", () => {
    const d = saveDepartment({ name: "中东大区", parent: "", leader: "Nora", memberCount: 3 });
    expect(d.parent).toBe("");
    expect(d.deptNo).toMatch(/^D\d+$/);
  });

  it("parent 缺省视为顶级（不是 undefined 落库——undefined 会让树的分组键变成 'undefined'）", () => {
    const d = saveDepartment({ name: "新部门", leader: "X", memberCount: 1 });
    expect(d.parent).toBe("");
  });

  it("parent 指向不存在的部门 → 抛错（这就是孤儿的来源）", () => {
    expect(() => saveDepartment({ name: "野部门", parent: "D999" })).toThrow(/上级部门不存在/);
  });

  it("parent = 自己 → 抛错", () => {
    expect(() => saveDepartment({ deptNo: "D2", name: "运维部", parent: "D2" })).toThrow(/不能是自己/);
  });

  it("parent = 自己的下级 → 抛错（成环）", () => {
    // D6 的上级是 D2；把 D2 的上级改成 D6 就 D2→D6→D2
    expect(() => saveDepartment({ deptNo: "D2", name: "运维部", parent: "D6" })).toThrow(/环/);
  });

  it("合法改挂：D8 从运维部挪到客服部", () => {
    const d = saveDepartment({ deptNo: "D8", parent: "D3" });
    expect(d.parent).toBe("D3");
    expect(departments.find((x) => x.deptNo === "D8")?.parent).toBe("D3");
  });
});
