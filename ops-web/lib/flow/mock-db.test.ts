// flow mock 的行为 = 对后端的期望。每条都对应界面上一个看得见的后果。
import { beforeEach, describe, expect, it } from "vitest";
import { setMockOperatorPerms } from "./mock-auth";
import {
  claimTicket, countTodos, getTicket, queryTickets, queryTodos, reassignTickets, resetFlowDb,
  ticketStats, transitionTicket, ME, ASSIGNEE_OPTIONS, getTicketView, getTimeline, addComment, poolTicket,
} from "./mock-db";

const NOW = new Date("2026-09-27T12:00:00").getTime();
beforeEach(() => { setMockOperatorPerms(["*"]); resetFlowDb(NOW); });

describe("待办", () => {
  it("四个页签的角标与各自列表的条数一致（角标不能骗人）", () => {
    const c = countTodos(NOW);
    const n = (q: Parameters<typeof queryTodos>[0]) => queryTodos({ ...q, page: 1, size: 200 }, NOW).total;
    expect(c.all).toBe(n({ origin: "ALL", page: 1, size: 1 }));
    expect(c.process).toBe(n({ origin: "PROCESS", page: 1, size: 1 }));
    expect(c.ticket).toBe(n({ origin: "TICKET", claimable: false, page: 1, size: 1 }));
    expect(c.claimable).toBe(n({ origin: "ALL", claimable: true, page: 1, size: 1 }));
    expect(c.all).toBe(c.process + c.ticket + c.claimable);
  });

  it("最紧的排最前", () => {
    const list = queryTodos({ origin: "ALL", page: 1, size: 50 }, NOW).list;
    const dues = list.filter((t) => t.sla?.state !== "PAUSED").map((t) => t.dueAtMs!);
    expect(dues[0]).toBe(Math.min(...dues));
  });

  it("抢单后：从可抢移到我的工单，工单派给我并进入处理中", () => {
    const pool = queryTodos({ origin: "ALL", claimable: true, page: 1, size: 10 }, NOW).list[0];
    const before = countTodos(NOW);
    const t = claimTicket(pool.ticketNo!, NOW);
    expect(t.assignee?.ref).toBe(ME.ref);
    expect(t.statusCategory).toBe("IN_PROGRESS");
    const after = countTodos(NOW);
    expect(after.claimable).toBe(before.claimable - 1);
    expect(after.ticket).toBe(before.ticket + 1);
  });

  it("同一张单不能被抢两次", () => {
    const no = queryTodos({ origin: "ALL", claimable: true, page: 1, size: 10 }, NOW).list[0].ticketNo!;
    claimTicket(no, NOW);
    expect(() => claimTicket(no, NOW)).toThrow(/已被他人领取/);
  });
});

describe("工单", () => {
  it("SLA 读时计算：过了截止就是已超时，两小时内是即将超时", () => {
    expect(getTicket("CS26092712", NOW).slaState).toBe("BREACHED");
    expect(getTicket("WO26092701", NOW).slaState).toBe("WARNING");
    expect(getTicket("WO26092651", NOW).slaState).toBe("PAUSED");
    // 时间往后走，同一张单会变成已超时 —— 不是存死的字段
    expect(getTicket("WO26092701", NOW + 60 * 60_000).slaState).toBe("BREACHED");
  });

  it("统计卡的数字 = 用它自己的 queryParams 查列表的条数（卡与列表同口径）", () => {
    for (const s of ticketStats(NOW)) {
      expect(queryTickets({ ...s.queryParams, page: 1, size: 500 }, NOW).total, s.label).toBe(s.value);
    }
  });

  it("只接受服务端当前给出的动作：待接单不能复核", () => {
    expect(() => transitionTicket("WO26092701", "REVIEW", NOW)).toThrow(/不能执行 REVIEW/);
  });

  it("复核 → 关闭，统计里的今日关闭 +1，且不再有可走动作", () => {
    const before = ticketStats(NOW).find((s) => s.key === "closedToday")!.value;
    const t = transitionTicket("WO26092640", "REVIEW", NOW);
    expect(t.statusCategory).toBe("DONE");
    expect(t.actions).toEqual([]);
    expect(ticketStats(NOW).find((s) => s.key === "closedToday")!.value).toBe(before + 1);
  });

  it("升级只能一次（升级后动作消失，再发被拒）", () => {
    transitionTicket("WO26092688", "ESCALATE", NOW);
    expect(getTicket("WO26092688", NOW).actions!.map((a) => a.code)).not.toContain("ESCALATE");
    expect(() => transitionTicket("WO26092688", "ESCALATE", NOW)).toThrow();
  });

  it("超时且不是我的单才出「接管」；接管后派给我", () => {
    expect(getTicket("CS26092712", NOW).actions![0].code).toBe("TAKEOVER");
    expect(transitionTicket("CS26092712", "TAKEOVER", NOW).assignee?.ref).toBe(ME.ref);
  });

  it("批量改派：原因必填（整体拒绝）；逐单结果，已结的单记失败、其余照改不回滚", () => {
    const to = ASSIGNEE_OPTIONS.find((m) => m.ref === "omar")!;
    expect(() => reassignTickets(["WO26092688"], to, "  ", NOW)).toThrow(/原因/);
    expect(() => reassignTickets(Array.from({ length: 201 }, (_, i) => `X${i}`), to, "休假", NOW)).toThrow(/200/);
    transitionTicket("WO26092640", "REVIEW", NOW);
    const r = reassignTickets(["WO26092688", "WO26092640", "NOPE"], to, "休假", NOW);
    expect(r.succeeded).toEqual(["WO26092688"]);
    expect(r.failed.map((f) => f.ticketNo)).toEqual(["WO26092640", "NOPE"]);
    expect(r.failed[0].error).toMatch(/已结/);
    expect(getTicket("WO26092688", NOW).assignee?.ref).toBe("omar");
  });

  it("出参不带业务词的硬编码：办理人类型、来源都来自服务给的 name", () => {
    const t = getTicket("WO26092688", NOW);
    expect(t.assignee?.attrs?.partyType).toEqual({ value: "STAFF", name: "员工" });
    expect(t.fields.source).toEqual({ value: "ALARM", name: "告警" });
    expect(t.priority).toEqual({ code: "HIGH", name: "高", ordinal: 1 });
  });

  it("按成员属性筛受理方", () => {
    const r = queryTickets({ assigneeAttr: { key: "partyType", values: ["AGENT"] }, page: 1, size: 100 }, NOW);
    expect(r.total).toBeGreaterThan(0);
    expect(r.list.every((t) => t.assignee?.attrs?.partyType?.value === "AGENT")).toBe(true);
  });

  it("筛选：类型 + 关键字都生效", () => {
    const r = queryTickets({ typeCode: ["cs-report"], keyword: "无法归还", page: 1, size: 50 }, NOW);
    expect(r.list.every((t) => t.type.code === "cs-report")).toBe(true);
    expect(r.list.map((t) => t.ticketNo)).toContain("CS26092712");
  });
});

describe("工单详情", () => {
  it("待接单时「响应」就是列表上那个 SLA；接单后响应达标", () => {
    const row = getTicket("WO26092701", NOW);
    const v = getTicketView("WO26092701", NOW);
    const respond = v.slaMetrics.find((m) => m.code === "respond")!;
    expect(respond.state).toBe(row.slaState);
    expect(respond.dueAtMs).toBe(row.slaNextDueAtMs);
    transitionTicket("WO26092701", "ACCEPT", NOW);
    expect(getTicketView("WO26092701", NOW).slaMetrics.find((m) => m.code === "respond")!.state).toBe("MET");
  });

  it("撤单必须填原因；迁移、备注、改派都进时间线（新的在上）", () => {
    expect(() => transitionTicket("WO26092701", "CANCEL", NOW)).toThrow(/原因/);
    transitionTicket("WO26092701", "ACCEPT", NOW);
    addComment("WO26092701", "已联系场地方", NOW + 1000);
    const tl = getTimeline("WO26092701");
    expect(tl[0]).toMatchObject({ kind: "COMMENT", detail: "已联系场地方", actor: { ref: "admin" }, hot: false });
    expect(tl[1].title).toMatch(/接单：待接单 → 处理中/);
  });

  it("退回抢单池：清空受理方、回到待接单，出现在可抢待办里", () => {
    expect(() => poolTicket("WO26092688", " ", NOW)).toThrow(/原因/);
    poolTicket("WO26092688", "受理方联系不上", NOW);
    const t = getTicket("WO26092688", NOW);
    expect(t.assignee).toBeUndefined();
    expect(t.status.code).toBe("PENDING_ACCEPT");
    expect(queryTodos({ origin: "ALL", claimable: true, page: 1, size: 50 }, NOW).list.map((x) => x.ticketNo)).toContain("WO26092688");
  });
});


describe("手工建单（21/06 §五 POST tickets）", () => {
  it("校验一次给全、路径按字段；合法的新开进抢单池、出现在可抢待办", async () => {
    const { createTicket } = await import("./mock-db");
    const { isValidationError } = await import("./errors");
    let err: unknown;
    try { createTicket({ typeCode: "nope", title: " ", priority: "X", subject: { type: "cabinet", ref: " " } }, NOW); } catch (e) { err = e; }
    expect(isValidationError(err) && err.errors.map((x) => x.path).sort()).toEqual(["priority", "subject", "title", "typeCode"]);
    const r = createTicket({ typeCode: "ops", title: "机柜离线", priority: "HIGH", subject: { type: "cabinet", ref: "CAB9001" } }, NOW);
    expect(r.attached).toBe(false);
    expect(r.ticket).toMatchObject({ status: { code: "PENDING_ACCEPT" }, priority: { code: "HIGH" }, fields: { source: { value: "MANUAL" } } });
    expect(queryTodos({ origin: "ALL", claimable: true, page: 1, size: 200 }, NOW).list.map((x) => x.ticketNo)).toContain(r.ticket.ticketNo);
    expect(getTimeline(r.ticket.ticketNo)[0]).toMatchObject({ kind: "CREATED" });
  });

  it("去重键命中未结单 → 并入它（attached），不另开；已结的不算", async () => {
    const { createTicket } = await import("./mock-db");
    const a = createTicket({ typeCode: "ops", title: "告警 ALM9", dedupeKey: "ALM9" }, NOW);
    const b = createTicket({ typeCode: "ops", title: "告警 ALM9 再报", dedupeKey: "ALM9" }, NOW + 1000);
    expect(b).toMatchObject({ attached: true, ticket: { ticketNo: a.ticket.ticketNo } });
    expect(getTimeline(a.ticket.ticketNo)[0]).toMatchObject({ kind: "DEDUPE" });
  });

  it("建单要 flow:ticket:handle（客服有、只读没有）", async () => {
    const { createTicket } = await import("./mock-db");
    setMockOperatorPerms(["flow:ticket:read"]);
    expect(() => createTicket({ typeCode: "ops", title: "x" }, NOW)).toThrow(/flow:ticket:handle/);
  });
});
