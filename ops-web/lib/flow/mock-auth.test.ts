// mock 层判权 = 对 OpsKit 服务端的期望：动作列表只给能走的，写接口缺权限 403。
// 角色的码取自前端镜像 BACKEND_ROLE_PERMS（与后端 RolePerms 逐码一致，perm-map.test.ts 守着）。
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { BACKEND_ROLE_PERMS } from "../permissions";
import { setMockOperatorPerms } from "./mock-auth";
import { claimTicket, getTicket, queryTickets, reassignTickets, resetFlowDb, transitionTicket, ASSIGNEE_OPTIONS } from "./mock-db";
import { upsertSlaPolicy, listSlaPolicies, resetConfigDb } from "./mock-config";

const NOW = new Date("2026-09-27T12:00:00").getTime();
beforeEach(() => { setMockOperatorPerms(["*"]); resetFlowDb(NOW); resetConfigDb(NOW); });
afterEach(() => setMockOperatorPerms(null));

const as = (role: keyof typeof BACKEND_ROLE_PERMS) => setMockOperatorPerms(BACKEND_ROLE_PERMS[role]);
const codes = (no: string) => getTicket(no, NOW).actions.map((a) => a.code);

describe("mock 判权", () => {
  it("没有权限 = 什么都不给（不是「空即放行」）", () => {
    setMockOperatorPerms([]);
    expect(() => queryTickets({ page: 1, size: 10 }, NOW)).toThrow(/flow:ticket:read/);
  });

  it("只读角色：看得到工单，但动作列表为空，直接调也是 403", () => {
    as("VIEWER");
    expect(queryTickets({ page: 1, size: 10 }, NOW).total).toBeGreaterThan(0);
    expect(codes("WO26092688")).toEqual([]);
    expect(() => transitionTicket("WO26092688", "ESCALATE", NOW)).toThrow(/flow:ticket:dispatch/);
    expect(() => claimTicket("WO26092733", NOW)).toThrow(/flow:ticket:handle/);
  });

  it("客服：能办理，不能派单类动作（升级 / 催办 / 改派）", () => {
    as("CS");
    expect(codes("WO26092688")).not.toContain("ESCALATE");
    expect(codes("CS26092712")).toContain("TAKEOVER");
    expect(() => reassignTickets(["WO26092688"], ASSIGNEE_OPTIONS[0], "x", NOW)).toThrow(/flow:ticket:dispatch/);
    expect(claimTicket("WO26092733", NOW).statusCategory).toBe("IN_PROGRESS");
  });

  it("运维：派单类动作都在", () => {
    as("OPS");
    expect(codes("WO26092688")).toContain("ESCALATE");
  });

  it("没有 flow:config:manage 改不了 SLA", () => {
    as("CS");
    expect(() => upsertSlaPolicy(listSlaPolicies()[0])).toThrow(/flow:config:manage/);
  });
});
