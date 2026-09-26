// 员工的部门字段：**存编号、名字由编号派生**。
//
// 与角色的 roleName → roleNo 是同一个坑，只差一行。`app/employees` 上方的注释记着：
// 角色那一格原先是自由文本框（key 为 `roleName`），后端认 `roleNo`，
// 填进去的名字被静默丢弃，那个人登录后什么菜单都没有。
// 紧接下一行的 `deptName` 当时没一起改。
//
// 部门不决定权限，所以没有那种响亮症状 —— 它只是让按部门筛人 / 派单 / 统计永远筛不出东西。
//
// 这里钉死两件事，因为 mock 曾经两头都不对：
//   1. 提交只认 deptNo，deptName 由它派生（真后端按 deptNo 查 iam_dept，mock 得同口径，
//      否则前端发错键在本地测不出来 —— 这正是它活到今天的原因）；
//   2. 员工种子里的部门必须是 departments 里真实存在的编号。此前种子直接编了
//      「运营 / 运维 / 客服 / 财务」四个字符串，和 D1–D5（运营中心 / 运维部 / …）对不上。
import { describe, expect, it } from "vitest";
import { employees, departments, saveEmployee } from "./org";

describe("员工部门", () => {
  it("种子里每个人的 deptNo 都指向真实部门，且 deptName 与之一致", () => {
    const byNo = new Map(departments.map((d) => [d.deptNo, d.name]));
    const bad = employees
      .filter((e) => e.deptNo !== null)
      .filter((e) => byNo.get(e.deptNo as string) !== e.deptName)
      .map((e) => `${e.employeeNo}: deptNo=${e.deptNo} deptName=${e.deptName}`);
    expect(bad, `部门编号与名字对不上：\n${bad.join("\n")}`).toEqual([]);
  });

  it("提交 deptNo 时 deptName 跟着派生出来", () => {
    const target = departments[1];
    const saved = saveEmployee({ name: "部门派生探针", roleNo: "OPS", deptNo: target.deptNo });
    try {
      expect(saved.deptNo).toBe(target.deptNo);
      expect(saved.deptName).toBe(target.name);
    } finally {
      employees.splice(employees.findIndex((e) => e.employeeNo === saved.employeeNo), 1);
    }
  });

  it("只传 deptName 不算——名字不是写入面的字段", () => {
    // 这一条正是缺陷本身：运营在自由文本框里打「运维部」，接口 200，库里 dept_no 还是空的。
    const saved = saveEmployee({ name: "只传名字探针", roleNo: "OPS", deptName: "运维部" } as never);
    try {
      expect(saved.deptNo, "没给编号就是没设部门").toBeNull();
      expect(saved.deptName, "名字也不该凭空留下——它是派生值").toBeNull();
    } finally {
      employees.splice(employees.findIndex((e) => e.employeeNo === saved.employeeNo), 1);
    }
  });
});
