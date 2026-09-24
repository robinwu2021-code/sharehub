// 角色数据权限（G7）落库测试。
//
// 背景：数据权限抽屉此前「点保存有成功提示，但 onSave 根本没调 API」，选中的值被丢弃，
// 重开抽屉又变回原值——用户完全看不出来。这里断言 saveRoleDataScope 真的改了数据、
// 并且 ALL/SELF 会清空 scopeRefs（否则残留脏 refs 会被后端 DataScopeHandler 误用）。
import { describe, expect, it, beforeEach } from "vitest";
import { roles, saveRoleDataScope, normalizeScopeValues } from "./org";
import { regions } from "./system";
import { sites } from "./location";
import { agents } from "./agent";
import type { RoleRow } from "../../types";

const snapshot = roles.map((r) => ({ ...r }));
const find = (code: string) => roles.find((r) => r.code === code) as RoleRow;

beforeEach(() => {
  // 用例之间互不污染（saveRoleDataScope 是就地改数组的真持久化）
  snapshot.forEach((s, i) => { roles[i] = { ...s }; });
});

describe("saveRoleDataScope 真的改了数据", () => {
  it("写入后从 roles 数组读回的就是新值（重开抽屉能读回）", () => {
    const ret = saveRoleDataScope("OPS", "LOCATION", "ST300,ST301");
    expect(ret.dataScope).toBe("LOCATION");
    expect(ret.scopeRefs).toBe("ST300,ST301");
    // 关键：不是只改了返回值的副本，数组里那条记录本身也变了
    expect(find("OPS").dataScope).toBe("LOCATION");
    expect(find("OPS").scopeRefs).toBe("ST300,ST301");
  });

  it("连续两次写入以最后一次为准（覆盖写语义）", () => {
    saveRoleDataScope("BD", "REGION", "AE-DU");
    saveRoleDataScope("BD", "AGENT", "AG003");
    expect(find("BD")).toMatchObject({ dataScope: "AGENT", scopeRefs: "AG003" });
  });

  it("角色码不存在时抛错（不静默新建脏角色）", () => {
    expect(() => saveRoleDataScope("NO_SUCH_ROLE", "ALL")).toThrow();
    expect(roles.some((r) => r.code === "NO_SUCH_ROLE")).toBe(false);
  });
});

describe("ALL / SELF 清空 scopeRefs", () => {
  it("原本有范围值的角色改成 ALL → scopeRefs 清空", () => {
    saveRoleDataScope("OPS", "REGION", "AE-DU,AE-AZ");
    expect(find("OPS").scopeRefs).toBe("AE-DU,AE-AZ");
    saveRoleDataScope("OPS", "ALL", "AE-DU,AE-AZ"); // 即使调用方仍传了值
    expect(find("OPS").scopeRefs).toBe("");
  });

  it("改成 SELF → scopeRefs 清空", () => {
    // 用 CS 而非 AGENT：AGENT 角色的数据范围被服务端守卫锁死（见下方越权守卫用例）
    saveRoleDataScope("CS", "SELF", "AG001,AG002");
    expect(find("CS")).toMatchObject({ dataScope: "SELF", scopeRefs: "" });
  });
});

describe("CSV 归一", () => {
  it("去空白 / 去空项 / 去重，保持选择顺序", () => {
    expect(normalizeScopeValues(" AE-DU , ,AE-AZ,AE-DU ")).toBe("AE-DU,AE-AZ");
    expect(normalizeScopeValues(undefined)).toBe("");
    expect(normalizeScopeValues("")).toBe("");
  });
  it("saveRoleDataScope 落库前也走归一", () => {
    saveRoleDataScope("CS", "REGION", "AE-DU, AE-DU ,AE-SH");
    expect(find("CS").scopeRefs).toBe("AE-DU,AE-SH");
  });
});

describe("scopeRefs 引用真实主数据（配合 integrity.test.ts，CSV 字段它查不到）", () => {
  const setOf = (xs: readonly string[]) => new Set(xs);
  const regionIds = setOf(regions.map((r) => r.regionId));
  const siteNos = setOf(sites.map((s) => s.siteNo));
  const agentNos = setOf(agents.map((a) => a.agentNo));

  it("每个角色种子数据里的范围 ID 都能在对应主数据里找到", () => {
    const bad: string[] = [];
    for (const r of snapshot) {
      const values = (r.scopeRefs ?? "").split(",").map((s) => s.trim()).filter(Boolean);
      if (r.dataScope === "ALL" || r.dataScope === "SELF") {
        if (values.length) bad.push(`roles[${r.code}] dataScope=${r.dataScope} 却带了范围值 "${r.scopeRefs}"`);
        continue;
      }
      const [pool, to] = r.dataScope === "REGION" ? [regionIds, "regions.regionId"]
        : r.dataScope === "LOCATION" ? [siteNos, "sites.siteNo"]
        : [agentNos, "agents.agentNo"];
      for (const v of values) {
        if (!(pool as Set<string>).has(v)) bad.push(`roles[${r.code}].scopeRefs 的 "${v}" 不存在于 ${to}`);
      }
    }
    expect(bad, `\n${bad.join("\n")}\n`).toEqual([]);
  });
});

describe("AGENT 角色数据范围越权守卫", () => {
  it("不能把 AGENT 角色改成别的范围档", () => {
    expect(() => saveRoleDataScope("AGENT", "ALL")).toThrow(/强制为自己 agent_no/);
    expect(() => saveRoleDataScope("AGENT", "REGION", "AE-DU")).toThrow(/强制为自己 agent_no/);
  });
  it("不能给 AGENT 角色指定其它代理（绕过 UI 直调接口同样被拒）", () => {
    expect(() => saveRoleDataScope("AGENT", "AGENT", "AG001,AG002")).toThrow(/不可更改或指定其它代理/);
  });
  it("AGENT 保持自身语义（AGENT 档 + 空范围值）是允许的", () => {
    const r = saveRoleDataScope("AGENT", "AGENT", "");
    expect(r.dataScope).toBe("AGENT");
    expect(r.scopeRefs).toBe("");
  });
});
