import { describe, it, expect, beforeEach } from "vitest";
import * as ax from "./agent-exit";
import { agents, agentAccounts } from "./agent";
import { sites } from "./location";
import { cabinets } from "./device";
import { workOrders } from "./workorder";
import { shareRecords, settlements, withdrawals } from "./finance";
import { AGENT_EXIT_TRANSITIONS, agentExitActionOf } from "../../types";
import type { Agent, WorkOrder } from "../../types";

/**
 * 代理清退（F3）mock 回归 —— 钉的是与后端 AgentExitServiceImpl 的一致性：
 * 发起即停用、门禁不过不许推进、最后一步停账号并归档。
 *
 * 每条用例造一个**全新的代理**：种子代理名下有站点 / 机柜 / 分润，
 * 拿它测「门禁全过」会被别的域的种子数据绊住，而那不是本用例要验的东西。
 */
let agentNo: string;

beforeEach(() => {
  agentNo = `AG-T${Date.now()}${Math.floor(Math.random() * 1000)}`;
  agents.push({
    agentNo, name: "清退测试代理", contact: "+971500000000", regionScope: "Test",
    agentType: "AGENT", shareRate: 0.3, cabinetCount: 0, status: "ENABLED", archivedAt: null,
  } as Agent);
  agentAccounts.push({
    accountNo: `AA-${agentNo}`, agentNo, agentName: "清退测试代理", username: null, loginPhone: "+971500000001",
    status: "ACTIVE", dataScope: "AGENT", createdAt: new Date().toISOString(),
  });
});

const agent = () => agents.find((a) => a.agentNo === agentNo)!;

describe("发起清退", () => {
  it("★ 发起即停用 —— 否则清退期间它还能接新单、还能提现", () => {
    const e = ax.startAgentExit(agentNo, "合作到期不续约");
    expect(e.status).toBe("RECLAIMING");
    expect(e.exitNo).toMatch(/^AX\d+$/);
    expect(agent().status).toBe("SUSPENDED");
  });

  it("原因必填（写进停用记录，事后追溯靠它）", () => {
    expect(() => ax.startAgentExit(agentNo, "  ")).toThrow(/原因|Reason/);
    expect(agent().status).toBe("ENABLED");   // 被拒之后不能把代理停掉
  });

  it("同一代理至多一张在途清退单", () => {
    ax.startAgentExit(agentNo, "r1");
    expect(() => ax.startAgentExit(agentNo, "r2")).toThrow(/在途|in progress/);
  });

  it("名下未完结工单改派平台（F2），已完结的不动", () => {
    const open = { woNo: `WO-T-${agentNo}-1`, status: "DISPATCHED", assigneeName: agentNo } as WorkOrder;
    const done = { woNo: `WO-T-${agentNo}-2`, status: "DONE", assigneeName: agentNo } as WorkOrder;
    workOrders.push(open, done);
    ax.startAgentExit(agentNo, "r");
    expect(open.assigneeName).not.toBe(agentNo);
    expect(done.assigneeName).toBe(agentNo);
  });
});

describe("门禁与推进", () => {
  it("★ 资产没收回不许进结清 —— 结完账才发现还有柜子在它手里产生分润", () => {
    const e = ax.startAgentExit(agentNo, "r");
    const site = { ...sites[0], siteNo: `ST-${agentNo}`, agentNo, status: "ACTIVE", archivedAt: null };
    sites.push(site);
    const g = ax.agentExitGate(e.exitNo);
    expect(g.allPassed).toBe(false);
    const failed = g.items.find((i) => i.key === "SITES")!;
    expect(failed.passed).toBe(false);
    expect(failed.detail).toMatch(/1/);
    expect(failed.fixHref).toBeTruthy();   // 未通过必须给去处
    expect(() => ax.advanceAgentExit(e.exitNo)).toThrow(/门禁|Gate/);
    expect(ax.getAgentExit(e.exitNo).status).toBe("RECLAIMING");
    // 收回之后放行
    site.agentNo = null as unknown as string;
    expect(ax.agentExitGate(e.exitNo).allPassed).toBe(true);
    expect(ax.advanceAgentExit(e.exitNo).status).toBe("SETTLING");
  });

  it("结清步：机柜 / 分润 / 结算单 / 提现在途都挡", () => {
    const e = ax.startAgentExit(agentNo, "r");
    ax.advanceAgentExit(e.exitNo);
    expect(ax.isSettlingExit(agentNo)).toBe(true);   // 这一步允许提现（settlingExit）
    const wd = { ...withdrawals[0], withdrawNo: `WD-T-${agentNo}`, payeeNo: agentNo, status: "AUDIT" as const };
    withdrawals.push(wd);
    const g = ax.agentExitGate(e.exitNo);
    expect(g.items.find((i) => i.key === "WITHDRAWALS")!.passed).toBe(false);
    expect(() => ax.advanceAgentExit(e.exitNo)).toThrow();
    wd.status = "PAID";
    const stl = { ...settlements[0], settleNo: `STL-T-${agentNo}`, payeeNo: agentNo, status: "CONFIRMED" as const };
    settlements.push(stl);
    expect(ax.agentExitGate(e.exitNo).items.find((i) => i.key === "SETTLEMENTS")!.passed).toBe(false);
    stl.status = "PAID";
    const rec = { ...shareRecords[0], recordNo: `SREC-T-${agentNo}`, dimension: "AGENT" as const, payeeNo: agentNo, status: "PENDING" as const };
    shareRecords.push(rec);
    expect(ax.agentExitGate(e.exitNo).items.find((i) => i.key === "SHARES")!.passed).toBe(false);
    rec.status = "DONE";
    expect(ax.advanceAgentExit(e.exitNo).status).toBe("CLOSING");
    expect(ax.isSettlingExit(agentNo)).toBe(false);
  });

  it("★ 关闭：停用全部登录账号 + 归档代理，之后不能再推进", () => {
    const e = ax.startAgentExit(agentNo, "r");
    ax.advanceAgentExit(e.exitNo);
    ax.advanceAgentExit(e.exitNo);
    const closed = ax.advanceAgentExit(e.exitNo, "Sara Ahmed");
    expect(closed.status).toBe("CLOSED");
    expect(closed.closedBy).toBe("Sara Ahmed");
    expect(closed.reclaimedAt && closed.settledAt && closed.closedAt).toBeTruthy();
    expect(agentAccounts.filter((a) => a.agentNo === agentNo).every((a) => a.status === "DISABLED")).toBe(true);
    expect(agent().archivedAt).toBeTruthy();
    expect(() => ax.advanceAgentExit(e.exitNo)).toThrow(/没有下一步|already closed/);
  });

  it("机柜仍归属该代理 → 设备项不过（退役的不算）", () => {
    const e = ax.startAgentExit(agentNo, "r");
    const cab = { ...cabinets[0], cabinetNo: `CAB-${agentNo}`, agentNo, status: "DEPLOYED", archivedAt: null } as (typeof cabinets)[number];
    cabinets.push(cab);
    expect(ax.agentExitGate(e.exitNo).items.find((i) => i.key === "CABINETS")!.passed).toBe(false);
    cab.status = "RETIRED";
    expect(ax.agentExitGate(e.exitNo).items.find((i) => i.key === "CABINETS")!.passed).toBe(true);
  });

  it("不存在的清退单报 404 而不是静默返回空", () => {
    expect(() => ax.getAgentExit("AX-NOPE")).toThrow(/不存在|not found/);
  });
});

describe("迁移表与后端 AgentExitStateMachine 一致", () => {
  it("三步严格按序，终态没有出边", () => {
    expect(agentExitActionOf("RECLAIMING")).toBe("reclaimed");
    expect(agentExitActionOf("SETTLING")).toBe("settled");
    expect(agentExitActionOf("CLOSING")).toBe("close");
    expect(agentExitActionOf("CLOSED")).toBeNull();
    expect(Object.values(AGENT_EXIT_TRANSITIONS).map((t) => `${t.from.join()}->${t.to}`))
      .toEqual(["RECLAIMING->SETTLING", "SETTLING->CLOSING", "CLOSING->CLOSED"]);
  });
});

describe("运维月度考核", () => {
  it("按考核月倒序；系数用后端同一套分档", () => {
    const rows = ax.listAgentOpsAssessments("AG001");
    expect(rows.length).toBeGreaterThan(0);
    expect([...rows].sort((a, b) => b.period.localeCompare(a.period)).map((r) => r.period)).toEqual(rows.map((r) => r.period));
    for (const r of rows) expect(r.coefficient).toBe(ax.coefficientOf(r.slaRate));
  });

  it("分档：≥95% 不打折，≥80% 0.9，以下 0.8，无从考核 1", () => {
    expect(ax.coefficientOf(0.96)).toBe(1);
    expect(ax.coefficientOf(0.85)).toBe(0.9);
    expect(ax.coefficientOf(0.5)).toBe(0.8);
    expect(ax.coefficientOf(null)).toBe(1);
  });

  it("★ 被接管算没达成 —— 否则超时不管、等平台接走反而不影响考核", () => {
    for (const r of ax.listAgentOpsAssessments("AG002")) {
      expect(r.woInSla).toBeLessThanOrEqual(r.woTotal - r.takenOver);
    }
  });
});
