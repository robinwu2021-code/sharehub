// 告警域写动作单测：确认（ack）/ 转工单 / 告警通知重发。
//
// 背景：后端 AlarmStateMachine 只认 OPEN --ACK--> ACKED，「已受理再确认」是非法迁移（409）。
// mock 若默默放行，前端就会长出「重复确认没问题」的错觉，切真后端才暴雷 —— 故这里把语义钉死：
//   ① OPEN → ACKED，备注非空才覆盖原上报说明
//   ② 非 OPEN（含刚 ack 过的）必须抛错，**不做幂等**
import { describe, it, expect } from "vitest";
import {
  alarmRecords, alarmNotices, ackAlarm, raiseAlarmWorkOrder,
  resendAlarmNotice, AlarmNoticeSendError,
  alarmCodes, autoRaiseWorkOrders,
} from "./alarm";
import { notifyBlacklist, maskTarget } from "./system";
import { ALARM_TRANSITIONS, canAlarmAction } from "../../types";

/** 取一条 OPEN 告警；用例会改状态，故每次现取一条未被改过的。 */
const anyOpen = () => alarmRecords.find((x) => x.status === "OPEN")!;

describe("确认告警", () => {
  it("OPEN → ACKED，备注被处置说明覆盖", () => {
    const a = anyOpen();
    const r = ackAlarm(a.alarmNo, "  已远程弹仓，恢复正常  ");
    expect(r).toEqual({ alarmNo: a.alarmNo, status: "ACKED" });
    expect(a.remark).toBe("已远程弹仓，恢复正常"); // trim 后落库
  });

  it("备注留空保留原上报说明（不抹掉排障线索）", () => {
    const a = anyOpen();
    const before = a.remark;
    ackAlarm(a.alarmNo, "   ");
    expect(a.status).toBe("ACKED");
    expect(a.remark).toBe(before);
  });

  it("重复确认抛错（不幂等，与状态机一致）", () => {
    const a = anyOpen();
    ackAlarm(a.alarmNo);
    expect(() => ackAlarm(a.alarmNo)).toThrow(/仅待处理/);
  });

  it("已关闭的告警不可确认", () => {
    const closed = alarmRecords.find((x) => x.status === "CLOSED")!;
    expect(() => ackAlarm(closed.alarmNo)).toThrow(/仅待处理/);
  });

  it("告警号不存在直接抛（前端不该调到不存在的行）", () => {
    expect(() => ackAlarm("ALM_NOT_EXIST")).toThrow(/告警不存在/);
  });
});

// 转工单：与 ack 相反，这个动作**是**幂等的（后端以 alarmNo 为幂等键）。
// 契约此前声明返回整行 AlarmRecord、页面读 r.workOrderNo，而后端一直返回 WorkOrderRef ——
// mock 也回整行，于是单测全绿、只有接真后端才显示「已转工单 undefined」。这几条钉住新形状。
describe("告警转工单", () => {
  it("首次转单：created=true，回带新工单号并置为已受理", () => {
    const a = anyOpen();
    const r = raiseAlarmWorkOrder(a.alarmNo);
    expect(r.alarmNo).toBe(a.alarmNo);
    expect(r.woNo).toMatch(/^WO\d+$/);
    expect(r.created).toBe(true);
    expect(a.status).toBe("ACKED");
    expect(a.workOrderNo).toBe(r.woNo);
  });

  it("重复转单：幂等 —— 沿用原工单号且 created=false，不产生第二张单", () => {
    const a = anyOpen();
    const first = raiseAlarmWorkOrder(a.alarmNo);
    const again = raiseAlarmWorkOrder(a.alarmNo);
    expect(again.woNo).toBe(first.woNo);
    expect(again.created).toBe(false);
  });

  it("已有工单的告警再转：不改状态、不换号（运营不该被诱导重复派人）", () => {
    const withWo = alarmRecords.find((x) => x.workOrderNo && x.status === "CLOSED")!;
    const before = { wo: withWo.workOrderNo, st: withWo.status };
    const r = raiseAlarmWorkOrder(withWo.alarmNo);
    expect(r.created).toBe(false);
    expect(r.woNo).toBe(before.wo);
    expect(withWo.status).toBe(before.st);
  });

  it("告警号不存在直接抛", () => {
    expect(() => raiseAlarmWorkOrder("ALM-NOPE")).toThrow();
  });
});

// 告警通知重发（拍板 #6）：口径与 system.ts 的发送记录重发逐条对齐 ——
// 必须带幂等键、同键第二次拒绝、只能重发失败通知、命中拉黑/告警已关闭一律拒、原记录一字不改。
// 这是「会真的再发一条短信并再计一次费」的动作，mock 若默默放行，接后端就是重复触达 + 重复扣费。
describe("告警通知重发", () => {
  /**
   * 可重发的失败通知：未命中拉黑、且本身不是补发件。拉黑分支另有专门用例。
   * 名单存脱敏值、通知存原值，故这里也要按 maskTarget 比一遍 —— 只比原值会把 AN50004 当成"干净"的。
   */
  const resendable = () => alarmNotices.find((n) =>
    n.status === "FAILED" && !n.resendOf &&
    !notifyBlacklist.some((b) =>
      (b.target === n.target || b.target === maskTarget(n.target)) &&
      (b.channel === "ALL" || b.channel === n.channel)))!;
  /** seed 里刻意埋的「目标已退订」那条（AN50004）。 */
  const blacklisted = () => alarmNotices.find((n) => n.target === "+9715012345678")!;

  it("必须带幂等键（空键直接拒，不给'先发了再说'的机会）", () => {
    const before = alarmNotices.length;
    expect(() => resendAlarmNotice(resendable().noticeNo, { idempotencyKey: "  " }))
      .toThrow(AlarmNoticeSendError);
    expect(alarmNotices).toHaveLength(before);
  });

  it("重发是新增一条，原记录一字不改（审计要看得见发了两次）", () => {
    const src = resendable();
    const snapshot = { ...src };
    const before = alarmNotices.length;

    const fresh = resendAlarmNotice(src.noticeNo, { idempotencyKey: "ANR-TEST-1" });
    expect(alarmNotices).toHaveLength(before + 1);
    expect(fresh.noticeNo).not.toBe(src.noticeNo);
    expect(fresh.resendOf).toBe(src.noticeNo);
    expect(fresh.status).toBe("SENT");
    expect(fresh.failReason).toBeNull();
    expect(fresh.idempotencyKey).toBe("ANR-TEST-1");
    // 渠道/目标/告警号沿用原记录：不给运营在重发时偷偷换目标的口子
    expect({ ch: fresh.channel, target: fresh.target, alarm: fresh.alarmNo })
      .toEqual({ ch: snapshot.channel, target: snapshot.target, alarm: snapshot.alarmNo });
    // 原记录逐字段不变（含 status/failReason —— 失败史不许被补发抹掉）
    expect(src).toEqual(snapshot);
  });

  it("同一幂等键第二次直接拒绝（刷新重放/双击不会多发一条）", () => {
    const src = resendable();
    resendAlarmNotice(src.noticeNo, { idempotencyKey: "ANR-TEST-DUP" });
    const after = alarmNotices.length;
    expect(() => resendAlarmNotice(src.noticeNo, { idempotencyKey: "ANR-TEST-DUP" }))
      .toThrow(/拒绝重复发送/);
    expect(alarmNotices).toHaveLength(after);
  });

  it("已发送的不许重发（那是重复轰炸值班人 + 重复计费）", () => {
    const sent = alarmNotices.find((n) => n.status === "SENT")!;
    expect(() => resendAlarmNotice(sent.noticeNo, { idempotencyKey: "ANR-TEST-SENT" })).toThrow(/不允许重发/);
  });

  it("目标已在触达拉黑：拒绝，且**不烧掉幂等键**（换合法记录仍可用同一把键）", () => {
    const before = alarmNotices.length;
    expect(() => resendAlarmNotice(blacklisted().noticeNo, { idempotencyKey: "ANR-TEST-KEEPKEY" }))
      .toThrow(/触达拉黑/);
    expect(alarmNotices).toHaveLength(before);
    // 键在校验全过之后才登记，所以这把键没被污染——否则运营改完就永远发不出去
    expect(resendAlarmNotice(resendable().noticeNo, { idempotencyKey: "ANR-TEST-KEEPKEY" }).status).toBe("SENT");
  });

  it("告警已关闭：不再重发通知（事已了结还催值班人是纯噪音）", () => {
    const src = resendable();
    const alarm = alarmRecords.find((a) => a.alarmNo === src.alarmNo)!;
    const st = alarm.status;
    alarm.status = "CLOSED"; // 就地改再还原：别的用例仍要用这条 OPEN 告警
    try {
      expect(() => resendAlarmNotice(src.noticeNo, { idempotencyKey: "ANR-TEST-CLOSED" })).toThrow(/已关闭/);
    } finally {
      alarm.status = st;
    }
  });

  it("通知号不存在直接抛（前端不该调到不存在的行）", () => {
    expect(() => resendAlarmNotice("AN-NOPE", { idempotencyKey: "ANR-TEST-404" })).toThrow(/不存在/);
  });
});

// 故障 → 自动开工单联动（S9）。补的是「告警规则引擎」缺的最后一环：
// autoWorkOrder 开关与转工单端点都早就存在，但没有东西把两者连起来。
// 最大的风险是重复开单 —— 一个反复上报的故障刷出一堆工单是运维成本事故，故幂等必须钉死。
describe("故障自动开工单", () => {
  it("只给「勾了 autoWorkOrder 的码 + 未关闭 + 尚无工单」的告警开单", () => {
    const autoCodes = new Set(alarmCodes.filter((c) => c.autoWorkOrder).map((c) => c.code));
    const r = autoRaiseWorkOrders();
    for (const c of r.created) {
      const a = alarmRecords.find((x) => x.alarmNo === c.alarmNo)!;
      expect(autoCodes.has(a.alarmCode)).toBe(true);
      expect(a.status).not.toBe("CLOSED");
      expect(a.workOrderNo).toBe(c.woNo);
    }
  });

  it("幂等：连跑两次，第二次一张新单都不开、全部计入 skipped", () => {
    autoRaiseWorkOrders();                 // 先跑一次，把该开的都开掉
    const again = autoRaiseWorkOrders();
    expect(again.created).toEqual([]);
    expect(again.skipped).toBe(again.eligible);
  });

  it("不动未勾选的码：BATTERY_LOW 是 WARN 但字典里不自动开单，不该被带上", () => {
    autoRaiseWorkOrders();
    const low = alarmRecords.filter((a) => a.alarmCode === "BATTERY_LOW" && a.status === "OPEN");
    for (const a of low) expect(a.workOrderNo).toBeNull();
  });

  it("不翻历史：已关闭的告警不会被批量开单", () => {
    const closedBefore = alarmRecords
      .filter((a) => a.status === "CLOSED")
      .map((a) => ({ no: a.alarmNo, wo: a.workOrderNo }));
    autoRaiseWorkOrders();
    for (const c of closedBefore) {
      expect(alarmRecords.find((a) => a.alarmNo === c.no)!.workOrderNo).toBe(c.wo);
    }
  });
});

describe("告警状态机表（A3-2 第一步）", () => {
  it("只有一条边：OPEN --ack--> ACKED", () => {
    expect(Object.keys(ALARM_TRANSITIONS)).toEqual(["ack"]);
    expect(ALARM_TRANSITIONS.ack).toMatchObject({ from: ["OPEN"], to: "ACKED" });
  });

  it("canAlarmAction 是页面按钮与 mock 守卫共用的那一份判据", () => {
    expect(canAlarmAction("OPEN", "ack")).toBe(true);
    expect(canAlarmAction("ACKED", "ack")).toBe(false);
    expect(canAlarmAction("CLOSED", "ack")).toBe(false);
  });

  it("⚠️ 表里没有任何边通往 CLOSED —— 这不是漏写，是后端也走不到", () => {
    // 后端 AlarmStateMachine 有 CLOSE 边，但主源码里没有一处发这个事件
    // （AlarmService 六个方法里没有 close）。告警只能 OPEN→ACKED 然后停住。
    // 这条断言是**故意钉住现状**的：补上关闭动作时它会红，提醒同时更新本表与台账。
    // 补关闭动作缺权限码，见执行计划 A3-2 第二步。
    const tos = Object.values(ALARM_TRANSITIONS).map((t) => t.to);
    expect(tos).not.toContain("CLOSED");
  });
});
