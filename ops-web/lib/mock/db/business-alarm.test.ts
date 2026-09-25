import { describe, it, expect, beforeEach } from "vitest";
import * as ba from "./business-alarm";
import { alarmRecords } from "./alarm";
import type { AlarmRecord } from "../../types";

/**
 * 业务告警与待办。
 *
 * <p>核心不变量：**预览说什么，执行就做什么**。两者各算一遍的话，
 * 预览显示「派给张三」而执行派给了李四 —— 而这恰恰是预览存在的意义。
 */

let alarmNo: string;

beforeEach(() => {
  ba.__resetTodos();
  alarmNo = `ALM-T${Date.now()}${Math.floor(Math.random() * 1000)}`;
  alarmRecords.unshift({
    alarmNo, alarmCode: "E002", cabinetNo: "CAB1000", siteNo: "ST300",
    level: "WARN", status: "OPEN", source: "DEVICE", occurredAt: new Date().toISOString(),
    workOrderNo: null, vendorCode: "cd-tech", vendorErrorCode: null, remark: null,
  } as unknown as AlarmRecord);
});

const cur = () => alarmRecords.find((a) => a.alarmNo === alarmNo)!;

describe("处置预览与执行", () => {
  it("★ 预览与执行走同一条路由——不然预览说的和做的不是一回事", () => {
    const p = ba.alarmDispositionPreview(alarmNo);
    const r = ba.disposeAlarm(alarmNo);
    expect(r.disposition).toBe(p.type);
  });

  it("★ 处置要真产出东西——只改状态的话，告警堆着而没人真去办", () => {
    const r = ba.disposeAlarm(alarmNo);
    // 开单 or 转客服 or 通知，至少留下一个可追的号
    expect(r.workOrderNo ?? r.mergedInto ?? r.todoNo, JSON.stringify(r)).toBeTruthy();
  });

  it("★ 已有未关闭工单时并单，而不是再开一张", () => {
    ba.saveAlarmRoutes("E002", [{ cause: null, disposition: "WORK_ORDER", woType: "FAULT" }]);
    cur().workOrderNo = "WO70001";
    const p = ba.alarmDispositionPreview(alarmNo);
    expect(p.mergeIntoWoNo, "一台离线的柜子每轮评估都开一张单，维修工到现场会发现五张一样的").toBe("WO70001");
    expect(ba.disposeAlarm(alarmNo).mergedInto).toBe("WO70001");
  });

  it("★ 非开单类处置不该显示并单目标——预览说谎比没有预览更糟", () => {
    ba.saveAlarmRoutes("E002", [{ cause: null, disposition: "NOTIFY" }]);
    cur().workOrderNo = "WO70001";
    expect(ba.alarmDispositionPreview(alarmNo).mergeIntoWoNo).toBeNull();
  });

  it("已关闭的告警不需要处置", () => {
    cur().status = "CLOSED";
    expect(() => ba.disposeAlarm(alarmNo)).toThrowError(/不需要处置|needs no disposition/);
  });

  it("处置后进入「已处置未关闭」——开了工单不等于问题好了", () => {
    const before = ba.alarmSummary().disposedOpen;
    ba.disposeAlarm(alarmNo);
    expect(ba.alarmSummary().disposedOpen).toBe(before + 1);
  });
});

describe("处置路由", () => {
  it("没配过的码走默认路由——不给默认的话，新码的告警会卡在「不知道该干嘛」", () => {
    const rs = ba.listAlarmRoutes("E999");
    expect(rs.length).toBeGreaterThan(0);
    expect(rs.every((r) => r.alarmCode === "E999")).toBe(true);
    // 必须有一条兜底（cause 为空），否则未知成因无路可走
    expect(rs.some((r) => !r.cause)).toBe(true);
  });

  it("★ 同一成因不能配两条——命中哪条取决于顺序，那是最难查的一类配置错误", () => {
    expect(() => ba.saveAlarmRoutes("E002", [
      { cause: "OFFLINE", disposition: "WORK_ORDER" },
      { cause: "OFFLINE", disposition: "NOTIFY" },
    ])).toThrowError(/配了两条|Duplicate cause/);
  });

  it("每条都要指定处置方式；不许存空路由表", () => {
    expect(() => ba.saveAlarmRoutes("E002", [])).toThrowError(/至少保留一条|At least one/);
    expect(() => ba.saveAlarmRoutes("E002", [{ disposition: undefined as never }]))
      .toThrowError(/处置方式|Disposition/);
  });

  it("★ 存了路由之后，处置真的按新路由走（存了不生效等于没存）", () => {
    ba.saveAlarmRoutes("E002", [{ cause: null, disposition: "CS_CASE" }]);
    expect(ba.alarmDispositionPreview(alarmNo).type).toBe("CS_CASE");
  });
});

describe("待办", () => {
  it("处置产出待办，计数跟着变", () => {
    expect(ba.alarmTodoCount().open).toBe(0);
    ba.disposeAlarm(alarmNo);
    expect(ba.alarmTodoCount().open).toBe(1);
    expect(ba.listAlarmTodos({}).list.length).toBe(1);
  });

  it("办结后从未完成里消失，并留下办结人与备注", () => {
    const todoNo = String(ba.disposeAlarm(alarmNo).todoNo);
    const done = ba.doneAlarmTodo(todoNo, "已现场处理");
    expect(done.status).toBe("DONE");
    expect(done.doneBy).toBe("admin");
    expect(done.doneNote).toBe("已现场处理");
    expect(ba.alarmTodoCount().open).toBe(0);
  });

  it("不能重复办结", () => {
    const todoNo = String(ba.disposeAlarm(alarmNo).todoNo);
    ba.doneAlarmTodo(todoNo);
    expect(() => ba.doneAlarmTodo(todoNo)).toThrowError(/已是|already/i);
  });

  it("不存在的待办要报错，而不是静默成功", () => {
    expect(() => ba.doneAlarmTodo("TD-nope")).toThrowError(/不存在|not found/i);
  });
});

describe("摘要与统计", () => {
  it("按域计数，未关闭的才算", () => {
    const s = ba.alarmSummary();
    const total = Object.values(s.byDomain).reduce((n, d) => n + d.open, 0);
    expect(total).toBe(alarmRecords.filter((a) => a.status !== "CLOSED").length);
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
