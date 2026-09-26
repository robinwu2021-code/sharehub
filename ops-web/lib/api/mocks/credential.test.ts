// 员工凭据（P3b·B2）在 mock 层的闭环：建号 → 强制改密 → 改完放行。
//
// 为什么在 mock 层也要测：离线开发时前端唯一能验的「一次性口令必须先改密」
// 就是这条路。mock 放行而后端强制的话，联调时一路顺，切后端当场被挡在改密屏 ——
// 而那一屏如果写错了，人就真的进不去了。
import { describe, it, expect, beforeEach } from "vitest";
import * as db from "@/lib/mock/db";
import { useAuth } from "@/lib/auth";

/** 在职的那个种子员工。**不写死编号** —— 种子里哪几个在职会变，写死会变成「测不到东西」。 */
const EMP = db.employees.find((e) => e.status === "ACTIVE")!.employeeNo;

describe("员工登录凭据（mock）", () => {
  beforeEach(() => {
    useAuth.getState().logout();
  });

  it("★ 建号回一次性口令，且这个人随即被要求改密", () => {
    expect(db.mustChangePassword(EMP)).toBe(false);
    const r = db.resetEmployeeCredential(EMP);
    expect(r.password).toHaveLength(12);
    // 形近字符会让口头转述变成「登不进去」的投诉 —— 与后端同一份字母表
    expect(r.password).not.toMatch(/[0O1lI]/);
    expect(db.mustChangePassword(EMP)).toBe(true);
  });

  it("改密后不再被要求改密；改密的长度闸在 mock 层也拦", () => {
    db.resetEmployeeCredential(EMP);
    expect(() => db.changeOwnPassword(EMP, "temp-pwd", "short")).toThrow();
    expect(db.mustChangePassword(EMP)).toBe(true);   // 拦住了就不该放行

    expect(() => db.changeOwnPassword(EMP, "temp-pwd", "temp-pwd")).toThrow();
    expect(() => db.changeOwnPassword(EMP, "", "LongEnough1")).toThrow();

    db.changeOwnPassword(EMP, "temp-pwd", "LongEnough1");
    expect(db.mustChangePassword(EMP)).toBe(false);
  });

  it("离职的人不给建号——既然离职要停用凭据，就不该有一条路把它激活回来", () => {
    const left = db.employees.find((e) => e.status !== "ACTIVE");
    if (!left) throw new Error("种子里没有离职员工，这条测不到东西");
    expect(() => db.resetEmployeeCredential(left.employeeNo)).toThrow();
    expect(db.mustChangePassword(left.employeeNo)).toBe(false);
  });

  it("★ store 的 mustChange 由登录响应带进来，只由改密成功清掉", () => {
    const base = {
      realm: "STAFF" as const, subjectNo: EMP, username: EMP, role: "OPS" as const,
      token: "t", perms: [] as string[],
    };
    useAuth.getState().login({ ...base, mustChange: true });
    expect(useAuth.getState().mustChange).toBe(true);

    useAuth.getState().passwordChanged();
    expect(useAuth.getState().mustChange).toBe(false);

    // 响应里没这个字段时必须是 false，不是 undefined ——
    // 外壳那道门用它做条件，undefined 与 false 在渲染上等价但在断言上不等价
    useAuth.getState().login(base);
    expect(useAuth.getState().mustChange).toBe(false);
  });
});
