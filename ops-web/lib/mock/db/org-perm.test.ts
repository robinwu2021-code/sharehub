// 角色功能权限（S6 勾选树）的 mock 自洽性测试。
//
// 背景：角色列表长期只有 `permCount` 一个数字，没有任何东西保证它和「实际分配了哪些权限码」一致——
// 之前那七个数（80/22/16/20/15/12/8）是手写的，勾选树一上线就会露馅：
// 树里勾着 30 项，列表却写着 22。这里把恒等式钉死：**permCount ≡ 已分配码数**。
import { describe, expect, it, beforeEach } from "vitest";
import {
  roles, permissions, listRolePermissions, saveRolePermissions, saveRoleRow,
} from "./org";
import type { RoleRow } from "../../types";

const snapshot = roles.map((r) => ({ ...r }));
const snapshotPerms = Object.fromEntries(roles.map((r) => [r.roleNo, listRolePermissions(r.roleNo)]));
const find = (no: string) => roles.find((r) => r.roleNo === no) as RoleRow;

beforeEach(() => {
  // saveRolePermissions / saveRoleRow 是就地改的真持久化，用例之间必须复位
  // 整体替换而不是截断长度：upsert 是 unshift（新角色进数组头），截尾会把 R7 干掉、留下脏的自定义角色
  roles.splice(0, roles.length, ...snapshot.map((s) => ({ ...s })));
  for (const [no, codes] of Object.entries(snapshotPerms)) saveRolePermissionsRaw(no, codes);
});
/** 绕开「内置角色只读」守卫做复位——守卫是给 UI 的，不该妨碍测试夹具还原。 */
function saveRolePermissionsRaw(roleNo: string, codes: string[]) {
  const i = roles.findIndex((r) => r.roleNo === roleNo);
  const wasBuiltin = roles[i].builtin;
  roles[i] = { ...roles[i], builtin: false };
  saveRolePermissions(roleNo, codes);
  roles[i] = { ...roles[i], builtin: wasBuiltin };
}

describe("权限码目录", () => {
  it("码格式一律 <模块>:<资源>:<动作>，且无重复", () => {
    const bad = permissions.filter((x) => !/^[a-z_]+:[a-z_]+:[a-z_]+$/.test(x.code));
    expect(bad.map((x) => x.code)).toEqual([]);
    expect(permissions.map((x) => x.code)).toEqual([...new Set(permissions.map((x) => x.code))]);
  });

  it("module 与 code 前缀一致（树按 module 分组，不一致会分错枝）", () => {
    const mismatch = permissions.filter((x) => x.module !== x.code.split(":")[0]);
    expect(mismatch.map((x) => x.code)).toEqual([]);
  });

  it("每条都有中文名（勾选树的叶子标签，空名等于给一串码让人猜）", () => {
    expect(permissions.filter((x) => !x.name.trim()).map((x) => x.code)).toEqual([]);
  });

  it("不含多租户口子码（ADR-011：运营端不体现多租户，摆出来就是点了没页面）", () => {
    const leaked = permissions.filter((x) => x.module === "tenant" || x.code === "dashboard:platform:read");
    expect(leaked.map((x) => x.code)).toEqual([]);
  });
});

describe("permCount ≡ 已分配权限码数", () => {
  it("每个内置角色的初值都对得上", () => {
    for (const r of roles) {
      expect(r.permCount, `${r.code}.permCount`).toBe(listRolePermissions(r.roleNo).length);
    }
  });

  it("ADMIN 持有目录全量（lib/permissions.ts 的 '*' 展开）", () => {
    expect(listRolePermissions("R1").length).toBe(permissions.length);
  });

  it("受限角色只是子集，不是全量（否则等于没做鉴权）", () => {
    const admin = listRolePermissions("R1").length;
    for (const r of roles.filter((x) => x.code !== "ADMIN")) {
      const n = listRolePermissions(r.roleNo).length;
      expect(n, `${r.code}`).toBeGreaterThan(0);
      expect(n, `${r.code}`).toBeLessThan(admin);
    }
  });

  it("覆盖写后 permCount 跟着变（列表数字与树里勾选数不许脱钩）", () => {
    const custom = saveRoleRow({ code: "CUSTOM_OPS", name: "自定义运营", dataScope: "ALL", memberCount: 0, builtin: false });
    expect(custom.permCount).toBe(0);
    saveRolePermissions(custom.roleNo, ["order:order:read", "order:order:export", "order:order:read"]);
    expect(find(custom.roleNo).permCount).toBe(2); // 去重后 2 项
    expect(listRolePermissions(custom.roleNo)).toEqual(["order:order:read", "order:order:export"]);
  });

  it("saveRoleRow 传进来的 permCount 不作数（派生量，只认实际码数）", () => {
    const custom = saveRoleRow({ code: "FAKE_COUNT", name: "吹牛角色", dataScope: "ALL", permCount: 999, builtin: false });
    expect(custom.permCount).toBe(0);
  });
});

describe("覆盖写守卫（与后端 IamAdminController 同款）", () => {
  it("角色不存在 → 抛错，不静默新建", () => {
    expect(() => saveRolePermissions("R999", ["order:order:read"])).toThrow(/不存在/);
  });

  it("内置角色只读 → 抛错", () => {
    expect(() => saveRolePermissions("R2", ["order:order:read"])).toThrow(/内置角色/);
    expect(find("R2").permCount).toBe(snapshotPerms["R2"].length); // 没被改坏
  });

  it("目录外的野码 → 抛错（否则塞进去的是永远命中不了的死码）", () => {
    const custom = saveRoleRow({ code: "C2", name: "自定义", dataScope: "ALL", builtin: false });
    expect(() => saveRolePermissions(custom.roleNo, ["order:order:read", "order:order:teleport"]))
      .toThrow(/不在目录/);
    expect(listRolePermissions(custom.roleNo)).toEqual([]); // 整批拒绝，不半写
  });
});

describe("listRolePermissions 返回副本", () => {
  it("调用方改返回值改不到内部状态（勾选树本地草稿不许污染数据源）", () => {
    const got = listRolePermissions("R2"); // OPS 没有发票作废权
    got.push("finance:invoice:void");
    expect(listRolePermissions("R2")).not.toContain("finance:invoice:void");
  });
});
