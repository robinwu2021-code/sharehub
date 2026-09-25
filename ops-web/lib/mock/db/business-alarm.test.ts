import { describe, it, expect, beforeEach } from "vitest";
import * as ba from "./business-alarm";
import { alarmRecords, alarmTodos, closeAlarm, listAlarmRecords } from "./alarm";
import { workOrders } from "./workorder";
import type { AlarmRecord } from "../../types";

/**
 * 业务告警与待办（批次 6a 数据层 + 6b 对齐真后端）。
 *
 * <p>核心不变量：**预览说什么，执行就做什么**。两者各算一遍的话，
 * 预览显示「并进 WO-1」而执行新开了一张 —— 而这恰恰是预览存在的意义。
 *
 * <p>6b 对着真后端（:8093）改过的形状：处置回 `{ alarmNo, dispositionRef }`（不是 6a 自拟的
 * workOrderNo / todoNo / mergedInto 三选一）；路由兜底成因存 `"*"`；详情回 AlarmDetail 包一层。
 */

let alarmNo: string;
/** 告警行的种子快照：用例会处置 / 关闭种子行，不复原的话用例之间互相污染、结果取决于执行顺序。 */
const SNAP = structuredClone(alarmRecords);

/** 每条用例一条全新的「存量设备告警」（无业务维度，码未登记 → 走全局默认路由）。 */
beforeEach(() => {
  ba.__resetTodos();
  alarmRecords.splice(0, alarmRecords.length, ...structuredClone(SNAP));
  alarmNo = `ALM-T${Date.now()}${Math.floor(Math.random() * 1000)}`;
  alarmRecords.unshift({
    alarmNo, alarmCode: "SCREEN_FAULT", cabinetNo: "CAB1000", siteNo: "ST300", siteName: null, agentNo: null,
    level: "WARN", status: "OPEN", source: "DEVICE", occurredAt: new Date().toISOString(),
    workOrderNo: null, vendorCode: "cd-tech", vendorErrorCode: null, remark: null, count: 1, dedupKey: null,
    closeReason: null, closeNote: null, closedBy: null, closedAt: null, business: null,
  } satisfies AlarmRecord);
});

const cur = () => alarmRecords.find((a) => a.alarmNo === alarmNo)!;
const biz = (code: string, status: AlarmRecord["status"] = "OPEN") =>
  alarmRecords.find((a) => a.alarmCode === code && a.status === status && a.business)!;

describe("处置预览与执行", () => {
  it("★ 预览与执行走同一条路由——不然预览说的和做的不是一回事", () => {
    ba.saveAlarmRoutes("OFFLINE", [{ cause: null, disposition: "WORK_ORDER", woType: "FAULT" }]);
    const a = alarmRecords.find((x) => x.alarmCode === "OFFLINE" && x.status === "OPEN" && !x.workOrderNo)!;
    const p = ba.alarmDispositionPreview(a.alarmNo);
    const woBefore = workOrders.length;
    const r = ba.disposeAlarm(a.alarmNo);
    expect(p.type).toBe("WORK_ORDER");
    expect(r.dispositionRef, "预览说开单，执行就得真有一张单").toMatch(/^WO/);
    expect(workOrders.length, "mock 必须真插一行工单，不是只回一个号").toBe(woBefore + 1);
    expect(workOrders.find((w) => w.woNo === r.dispositionRef)!.sourceNo).toBe(a.alarmNo);
  });

  it("★ 返回形状与后端一致：{ alarmNo, dispositionRef }", () => {
    const r = ba.disposeAlarm(alarmNo);
    expect(Object.keys(r).sort()).toEqual(["alarmNo", "dispositionRef"]);
  });

  it("★ 幂等：已处置过的再处置，返回首次的单号、不产出第二张单", () => {
    ba.saveAlarmRoutes("SCREEN_FAULT", [{ cause: null, disposition: "WORK_ORDER", woType: "FAULT" }]);
    const first = ba.disposeAlarm(alarmNo).dispositionRef;
    const n = workOrders.length;
    expect(ba.disposeAlarm(alarmNo).dispositionRef).toBe(first);
    expect(workOrders.length).toBe(n);
  });

  it("★ 同主体已有开单中的告警时并单，而不是再开一张", () => {
    const parent = biz("SITE_UNRENTABLE");   // 种子：已开 WO70003
    const sameSite = biz("SITE_UNRETURNABLE"); // 种子：另一个站、未处置
    // 码的并单范围是 SITE：同一个站的「借不到」与「还不了」多半是同一个原因（整站离线），派一个人去就够
    sameSite.siteNo = parent.siteNo;
    sameSite.business!.subjectNo = parent.business!.subjectNo;
    const p = ba.alarmDispositionPreview(sameSite.alarmNo);
    expect(p.mergeIntoWoNo, "一台离线的柜子每轮评估都开一张单，维修工到现场会发现五张一样的").toBe("WO70003");
    expect(ba.disposeAlarm(sameSite.alarmNo).dispositionRef).toBe("WO70003");
  });

  it("★ 非开单类处置不该显示并单目标——预览说谎比没有预览更糟", () => {
    ba.saveAlarmRoutes("SCREEN_FAULT", [{ cause: null, disposition: "NOTIFY" }]);
    cur().workOrderNo = "WO70001";
    expect(ba.alarmDispositionPreview(alarmNo).mergeIntoWoNo).toBeNull();
  });

  it("待办类处置：预览给出承接岗位，执行真的产出一条待办", () => {
    const a = biz("SITE_OWNER_MISSING");   // 码首选处置 TODO → OPS
    const p = ba.alarmDispositionPreview(a.alarmNo);
    expect(p).toMatchObject({ type: "TODO", todoRole: "OPS", woType: null });
    const r = ba.disposeAlarm(a.alarmNo);
    expect(alarmTodos.find((t) => t.todoNo === r.dispositionRef)?.roleCode).toBe("OPS");
    expect(a.business!.dispositionRef).toBe(r.dispositionRef);
  });

  it("自愈类处置：成功即以 AUTO_FIXED 关闭，没有单可返回", () => {
    const a = biz("RENT_NOT_DELIVERED", "CLOSED");
    a.status = "OPEN";
    a.business!.dispositionType = null;
    a.business!.dispositionRef = null;
    const r = ba.disposeAlarm(a.alarmNo);
    expect(r.dispositionRef).toBeNull();
    expect(a.status).toBe("CLOSED");
    expect(a.closeReason).toBe("AUTO_FIXED");
  });

  it("已关闭的告警不需要处置", () => {
    cur().status = "CLOSED";
    expect(() => ba.disposeAlarm(alarmNo)).toThrowError(/不需要处置|needs no disposition/);
  });

  it("处置后进入「已处置未关闭」——开了工单不等于问题好了", () => {
    const a = biz("SITE_UNRETURNABLE");
    const before = ba.alarmSummary().disposedOpen;
    ba.disposeAlarm(a.alarmNo);
    expect(ba.alarmSummary().disposedOpen).toBe(before + 1);
  });

  it("处置写进时间线", () => {
    const a = biz("SITE_UNRETURNABLE");
    ba.disposeAlarm(a.alarmNo);
    const tl = ba.getAlarmDetail(a.alarmNo).timeline;
    expect(tl[0].event).toBe("OPEN");
    expect(tl.at(-1)!.event).toBe("DISPOSE");
  });
});

describe("详情", () => {
  it("★ 回 AlarmDetail 包一层（record / codeName / timeline…），不是整行记录", () => {
    const d = ba.getAlarmDetail(biz("SITE_UNRENTABLE").alarmNo);
    expect(d.record.alarmCode).toBe("SITE_UNRENTABLE");
    expect(d.codeName).toBe("站点借不到");
    expect(d.suggestion).toBeTruthy();
    expect(JSON.parse(d.evidence!)).toBeInstanceOf(Array);
  });

  it("同主体近 7 天：被取代的柜级子告警出现在站点级的历史里", () => {
    const site = biz("SITE_UNRENTABLE");
    const child = alarmRecords.find((a) => a.business?.parentAlarmNo === site.alarmNo)!;
    child.business!.subjectNo = site.business!.subjectNo;
    child.occurredAt = new Date().toISOString();
    expect(ba.getAlarmDetail(site.alarmNo).recentSameSubject.map((a) => a.alarmNo)).toContain(child.alarmNo);
  });
});

describe("关闭（业务告警）", () => {
  it("★ 安全域不能人工以「已解决」关——隐患宝锁着仓，点一下就放锁而现场可能没人去过", () => {
    const a = biz("BATTERY_HAZARD");
    expect(() => closeAlarm(a.alarmNo, "RESOLVED", "现场已处理")).toThrowError(/只能随处置完成关闭|disposition/);
    expect(a.status).toBe("OPEN");
  });

  it("★ 已解决 / 误报必须写说明——没有说明的误报没人敢据此放宽阈值", () => {
    const a = biz("SITE_OWNER_MISSING");
    expect(() => closeAlarm(a.alarmNo, "FALSE_ALARM")).toThrowError(/说明|note/i);
    expect(() => closeAlarm(a.alarmNo, "FALSE_ALARM", "  ")).toThrowError(/说明|note/i);
    expect(a.status).toBe("OPEN");
  });

  it("系统档（AUTO_FIXED / SUPERSEDED）人工不可选", () => {
    const a = biz("SITE_OWNER_MISSING");
    expect(() => closeAlarm(a.alarmNo, "AUTO_FIXED")).toThrow();
    expect(() => closeAlarm(a.alarmNo, "SUPERSEDED")).toThrow();
  });

  it("★ 关闭联动取消该告警名下未完成的待办，并写时间线", () => {
    const a = biz("SITE_LOW_YIELD");   // 种子：已挂待办 ATD8002
    closeAlarm(a.alarmNo, "SELF_HEALED");
    const t = alarmTodos.find((x) => x.todoNo === "ATD8002")!;
    expect(t.status).toBe("CANCELLED");
    expect(t.doneNote).toMatch(/SELF_HEALED/);
    expect(ba.getAlarmDetail(a.alarmNo).timeline.at(-1)!.event).toBe("CLOSE");
  });
});

describe("列表筛选（与后端 AlarmQuery 同口径）", () => {
  it("status / domain 支持逗号多值；domain 筛掉无域的存量设备告警", () => {
    const r = listAlarmRecords({ size: 200, status: "OPEN,ACKED", domain: "AVAILABILITY,RETURNABILITY" });
    expect(r.list.length).toBeGreaterThan(0);
    for (const a of r.list) {
      expect(["OPEN", "ACKED"]).toContain(a.status);
      expect(["AVAILABILITY", "RETURNABILITY"]).toContain(a.business?.domain);
    }
  });

  it("topOnly 收起被取代的子告警", () => {
    const all = listAlarmRecords({ size: 200 }).list;
    const top = listAlarmRecords({ size: 200, topOnly: true }).list;
    expect(all.some((a) => a.business?.parentAlarmNo)).toBe(true);
    expect(top.some((a) => a.business?.parentAlarmNo)).toBe(false);
  });

  it("按发生时刻倒序——与后端默认序一致", () => {
    const l = listAlarmRecords({ size: 200 }).list;
    for (let i = 1; i < l.length; i++) expect(l[i - 1].occurredAt >= l[i].occurredAt).toBe(true);
  });
});

describe("处置路由", () => {
  it("没配过的码走默认路由；兜底成因存 `*`（与后端存库值一致）", () => {
    const rs = ba.listAlarmRoutes("E999");
    expect(rs.length).toBeGreaterThan(0);
    expect(rs.every((r) => r.alarmCode === "E999")).toBe(true);
    expect(rs.some((r) => r.cause === "*")).toBe(true);
  });

  it("★ 同一成因不能配两条——命中哪条取决于顺序，那是最难查的一类配置错误", () => {
    expect(() => ba.saveAlarmRoutes("OFFLINE", [
      { cause: "OFFLINE", disposition: "WORK_ORDER" },
      { cause: "OFFLINE", disposition: "NOTIFY" },
    ])).toThrowError(/配了两条|Duplicate cause/);
    // 空成因与 `*` 是同一个兜底，也不能各配一条
    expect(() => ba.saveAlarmRoutes("OFFLINE", [
      { cause: null, disposition: "WORK_ORDER" },
      { cause: "*", disposition: "NOTIFY" },
    ])).toThrowError(/配了两条|Duplicate cause/);
  });

  it("每条都要指定处置方式；不许存空路由表；码必须存在", () => {
    expect(() => ba.saveAlarmRoutes("OFFLINE", [])).toThrowError(/至少保留一条|At least one/);
    expect(() => ba.saveAlarmRoutes("OFFLINE", [{ disposition: undefined as never }]))
      .toThrowError(/处置方式|Disposition/);
    expect(() => ba.saveAlarmRoutes("NO_SUCH_CODE", [{ disposition: "NOTIFY" }])).toThrowError(/不存在|not found/);
  });

  it("优先级增量夹在 ±2；非开单处置不留工单类型", () => {
    const [r] = ba.saveAlarmRoutes("OFFLINE", [{ cause: null, disposition: "NOTIFY", woType: "FAULT", priorityDelta: 9 }]);
    expect(r).toMatchObject({ cause: "*", priorityDelta: 2, woType: null });
  });

  it("★ 存了路由之后，处置真的按新路由走（存了不生效等于没存）", () => {
    ba.saveAlarmRoutes("SCREEN_FAULT", [{ cause: null, disposition: "CS_CASE" }]);
    expect(ba.alarmDispositionPreview(alarmNo).type).toBe("CS_CASE");
    expect(ba.disposeAlarm(alarmNo).dispositionRef).toMatch(/^CS/);
  });

  it("优先级增量作用在基准优先级上", () => {
    const a = biz("SITE_UNRETURNABLE");   // 基准 HIGH
    ba.saveAlarmRoutes("SITE_UNRETURNABLE", [{ cause: "FULL", disposition: "WORK_ORDER", woType: "REFILL", priorityDelta: 1 }]);
    expect(ba.alarmDispositionPreview(a.alarmNo)).toMatchObject({ priority: "URGENT", woType: "REFILL" });
  });
});

describe("待办", () => {
  it("处置产出待办，计数跟着变", () => {
    const before = ba.alarmTodoCount().open;
    ba.disposeAlarm(biz("SITE_OWNER_MISSING").alarmNo);
    expect(ba.alarmTodoCount().open).toBe(before + 1);
  });

  it("★ 办结后留下办结人与备注，并关闭关联告警（RESOLVED）", () => {
    const a = biz("SITE_WITHOUT_CONTRACT");   // 种子待办 ATD8003
    const done = ba.doneAlarmTodo("ATD8003", "已补签");
    expect(done).toMatchObject({ status: "DONE", doneBy: "admin", doneNote: "已补签" });
    expect(a.status).toBe("CLOSED");
    expect(a.closeReason).toBe("RESOLVED");
  });

  it("不能重复办结", () => {
    ba.doneAlarmTodo("ATD8004");
    expect(() => ba.doneAlarmTodo("ATD8004")).toThrowError(/已是|already/i);
  });

  it("不存在的待办要报错，而不是静默成功", () => {
    expect(() => ba.doneAlarmTodo("TD-nope")).toThrowError(/不存在|not found/i);
  });

  it("status 筛选；mine=false 连已办结的一起看", () => {
    ba.doneAlarmTodo("ATD8005");
    expect(ba.listAlarmTodos({ status: "DONE" }).list.map((t) => t.todoNo)).toContain("ATD8005");
    expect(ba.listAlarmTodos({}).list.some((t) => t.status !== "OPEN")).toBe(false);
    expect(ba.listAlarmTodos({ mine: false, size: 200 }).list.some((t) => t.status === "DONE")).toBe(true);
  });
});

describe("摘要与统计", () => {
  it("按域计数：未关闭且顶层的才算；无域的存量设备告警计入 AVAILABILITY（后端口径）", () => {
    const s = ba.alarmSummary();
    const total = Object.values(s.byDomain).reduce((n, d) => n + (d?.open ?? 0), 0);
    expect(total).toBe(alarmRecords.filter((a) => a.status !== "CLOSED" && !a.business?.parentAlarmNo).length);
    expect(s.byDomain.SAFETY?.critical).toBeGreaterThan(0);
  });

  it("★ 每码统计的三个比率都在 0..1——比率算错会让「规则太敏感」这个判断反过来", () => {
    for (const st of ba.alarmCodeStats()) {
      for (const [k, v] of Object.entries(st)) {
        if (!k.endsWith("Rate")) continue;
        expect(v as number, `${st.code}.${k}`).toBeGreaterThanOrEqual(0);
        expect(v as number, `${st.code}.${k}`).toBeLessThanOrEqual(1);
      }
    }
  });

  it("统计覆盖所有出现过的码", () => {
    const codes = new Set(alarmRecords.map((a) => a.alarmCode));
    expect(new Set(ba.alarmCodeStats().map((s) => s.code))).toEqual(codes);
  });
});
