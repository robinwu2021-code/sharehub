import { describe, it, expect } from "vitest";
import { ownershipIssues } from "./ownership";
import type { SiteAgent } from "@/lib/types";

const role = (agentNo: string, r: SiteAgent["role"] = "OPERATE", extra: Partial<SiteAgent> = {}): SiteAgent =>
  ({ siteNo: "ST1", agentNo, role: r, ...extra }) as SiteAgent;

describe("ownershipIssues：看得到 vs 分得到", () => {
  it("站点没配责任行：分成回落到机柜的商家，天然一致", () => {
    expect(ownershipIssues({ agentNo: "AG1", issuedAgentNo: "AG1", locationNo: "L1" }, [])).toEqual([]);
  });
  it("机柜归 B、站点分成给 A：标出来", () => {
    expect(ownershipIssues({ agentNo: "AG2", issuedAgentNo: "AG2", locationNo: "L1" }, [role("AG1")])[0]).toMatch(/分成给 AG1/);
  });
  it("牵线不逐单分成，不算分成对象", () => {
    expect(ownershipIssues({ agentNo: "AG1", issuedAgentNo: "AG1", locationNo: "L1" }, [role("AG1"), role("AG9", "REFER")])).toEqual([]);
  });
  it("已过期的责任行不算", () => {
    expect(ownershipIssues({ agentNo: "AG2", issuedAgentNo: "AG2", locationNo: "L1" },
      [role("AG1", "OPERATE", { effectiveTo: "2000-01-01" })])).toEqual([]);
  });
  it("出库给 A 却绑在 B 的站点上", () => {
    expect(ownershipIssues({ agentNo: "AG2", issuedAgentNo: "AG1", locationNo: "L1" }, [])[0]).toMatch(/出库给了 AG1/);
  });
  it("未绑定的设备不判", () => {
    expect(ownershipIssues({ agentNo: null, issuedAgentNo: "AG1", locationNo: null }, [role("AG1")])).toEqual([]);
  });
});
