// 工单状态机单测（G6 闭环的防复发机制）。
//
// 背景：闭环之前工单只有 dispatch 一个迁移，且 mock 直接 `w.status = "DISPATCHED"` 无校验——
// 任何状态都能被改成任何状态，「看板 5 列」只是展示。本文件把状态机钉死：
//   ① 全部合法迁移都能走通（含 CREATED→…→CLOSED 全链路，以及告警/投诉转来的工单）
//   ② 非法迁移必须**抛错**，不能默默通过
//   ③ 关单必须有验收结论、驳回必须有原因
import { describe, it, expect, beforeEach } from "vitest";
import type { WorkOrderAction, WorkOrderStatus } from "../../types";
import {
  workOrders, WO_TRANSITIONS, WorkOrderTransitionError, canTransition, nextActions,
  createWorkOrder, dispatchWorkOrder, acceptWorkOrder, processWorkOrder,
  completeWorkOrder, closeWorkOrder, rejectWorkOrder, reworkWorkOrder, transitionWorkOrder,
} from "./workorder";

/** 造一条指定状态的工单（直接落数组，绕开状态机——测试夹具允许，业务代码不允许）。 */
let seq = 0;
function fixture(status: WorkOrderStatus) {
  const w = createWorkOrder({
    type: "FAULT", cabinetNo: workOrders[0].cabinetNo!, priority: "MEDIUM",
    description: `状态机测试 ${++seq}`,
  });
  w.status = status;
  return w;
}

describe("状态机定义", () => {
  it("七个动作齐备（开单之外的全部流转）", () => {
    expect(Object.keys(WO_TRANSITIONS).sort()).toEqual(
      ["accept", "close", "complete", "dispatch", "process", "reject", "rework"],
    );
  });

  it("nextActions 给出当前状态的下一步（看板按钮据此渲染）", () => {
    expect(nextActions("CREATED")).toEqual(["dispatch"]);
    expect(nextActions("DISPATCHED").sort()).toEqual(["accept", "reject"]);
    expect(nextActions("ACCEPTED").sort()).toEqual(["process", "reject"]);
    expect(nextActions("PROCESSING").sort()).toEqual(["complete", "process", "reject"]);
    expect(nextActions("DONE")).toEqual(["close", "rework"]);
    expect(nextActions("CLOSED")).toEqual([]); // 终态：没有下一步
  });
});

describe("合法迁移", () => {
  it("CREATED --dispatch--> DISPATCHED（并落派单对象与时间）", () => {
    const w = fixture("CREATED");
    const r = dispatchWorkOrder(w.woNo, "Ali");
    expect(r.status).toBe("DISPATCHED");
    expect(r.assigneeName).toBe("Ali");
    expect(r.dispatchedAt).toBeTruthy();
  });

  it("DISPATCHED --accept--> ACCEPTED（处理人默认取派单对象）", () => {
    const w = fixture("CREATED");
    dispatchWorkOrder(w.woNo, "Omar");
    const r = acceptWorkOrder(w.woNo);
    // **不是 PROCESSING**：后端 WoStateMachine 的 ACCEPT 边落 ACCEPTED，
    // 接单与到场处理是两步。此前这里写 PROCESSING，于是真后端接完单之后
    // 工单停在 ACCEPTED，而当时没有任何动作的 from 含它 —— 界面上一个按钮都没有。
    expect(r.status).toBe("ACCEPTED");
    expect(r.handlerName).toBe("Omar");
    expect(r.acceptedAt).toBeTruthy();
  });

  it("★ ACCEPTED 必须有下一步——没有的话工单在界面上就卡死了", () => {
    expect(nextActions("ACCEPTED").sort()).toEqual(["process", "reject"]);
  });

  it("PROCESSING --process--> PROCESSING（只留痕不改状态，可多次追加）", () => {
    const w = fixture("PROCESSING");
    processWorkOrder(w.woNo, { handleNote: "已远程弹仓", handlerName: "Sara" });
    const r = processWorkOrder(w.woNo, { handleNote: "现场复核锁扣", partsReplaced: "锁扣模块 ×1" });
    expect(r.status).toBe("PROCESSING");
    expect(r.handleNote).toBe("现场复核锁扣");
    expect(r.partsReplaced).toBe("锁扣模块 ×1");
    expect(r.handledAt).toBeTruthy();
  });

  it("PROCESSING --complete--> DONE（留处理人/时间/说明）", () => {
    const w = fixture("PROCESSING");
    const r = completeWorkOrder(w.woNo, { handleNote: "更换锁扣后复测通过", handlerName: "Wang", partsReplaced: "锁扣模块 ×1" });
    expect(r.status).toBe("DONE");
    expect(r.handlerName).toBe("Wang");
    expect(r.completedAt).toBeTruthy();
  });

  it("DONE --close--> CLOSED（留验收人/时间/结论）", () => {
    const w = fixture("DONE");
    const r = closeWorkOrder(w.woNo, { auditResult: "PASS", auditNote: "抽检一次弹出正常", auditorName: "主管" });
    expect(r.status).toBe("CLOSED");
    expect(r.auditResult).toBe("PASS");
    expect(r.auditorName).toBe("主管");
    expect(r.auditedAt).toBeTruthy();
  });

  it("DISPATCHED --reject--> CREATED（退回重派，清空处理人并累计驳回次数）", () => {
    const w = fixture("CREATED");
    dispatchWorkOrder(w.woNo, "Ali");
    const r = rejectWorkOrder(w.woNo, "该柜机不在我负责区域");
    expect(r.status).toBe("CREATED");
    expect(r.assigneeName).toBeNull();
    expect(r.rejectReason).toBe("该柜机不在我负责区域");
    expect(r.rejectCount).toBe(1);
  });

  it("PROCESSING --reject--> CREATED（处理中也能退回）", () => {
    const w = fixture("PROCESSING");
    expect(rejectWorkOrder(w.woNo, "缺配件，需换人处理").status).toBe("CREATED");
  });

  it("全链路：开单 → 派单 → 接单 → 处理 → 完成 → 关单", () => {
    const w = createWorkOrder({
      type: "FAULT", cabinetNo: workOrders[0].cabinetNo!, priority: "URGENT",
      description: "全链路测试：卡槽卡宝", expectedAt: "2026-08-01",
    });
    expect(w.status).toBe("CREATED");
    expect(w.source).toBe("MANUAL");
    dispatchWorkOrder(w.woNo, "Ali");
    acceptWorkOrder(w.woNo);
    processWorkOrder(w.woNo, { handleNote: "到场检查" });
    completeWorkOrder(w.woNo, { handleNote: "更换卡槽弹簧" });
    expect(closeWorkOrder(w.woNo, { auditResult: "PASS" }).status).toBe("CLOSED");
  });

  it("驳回后可重新派单，第二轮仍能走到 CLOSED", () => {
    const w = fixture("CREATED");
    dispatchWorkOrder(w.woNo, "Ali");
    rejectWorkOrder(w.woNo, "排班冲突");
    dispatchWorkOrder(w.woNo, "Omar");
    acceptWorkOrder(w.woNo);
    processWorkOrder(w.woNo, { handleNote: "到场处理" });   // ACCEPTED→PROCESSING，少了这步 complete 会被拒
    completeWorkOrder(w.woNo, { handleNote: "已处理" });
    expect(closeWorkOrder(w.woNo, { auditResult: "PASS_WITH_ISSUE", auditNote: "遗留：需下次巡检复查" }).status).toBe("CLOSED");
  });
});

describe("非法迁移必须被拒（不能默默通过）", () => {
  const illegal: [string, WorkOrderStatus, WorkOrderAction][] = [
    ["CREATED 不能直接接单（跳过派单）", "CREATED", "accept"],
    ["CREATED 不能直接关单（跳过全部处理）", "CREATED", "close"],
    ["DISPATCHED 不能直接完成（未接单）", "DISPATCHED", "complete"],
    ["DONE 不能再次完成", "DONE", "complete"],
    ["CLOSED 是终态：不能再派单", "CLOSED", "dispatch"],
    ["CLOSED 是终态：不能被驳回", "CLOSED", "reject"],
    ["DONE 不能被驳回（只能关单）", "DONE", "reject"],
    ["CREATED 不能提交处理结果", "CREATED", "process"],
  ];
  it.each(illegal)("%s", (_name, from, action) => {
    const w = fixture(from);
    expect(() => transitionWorkOrder(w.woNo, action)).toThrow(WorkOrderTransitionError);
    expect(w.status).toBe(from); // 状态未被污染
    expect(canTransition(from, action)).toBe(false);
  });

  it("不存在的工单号一律拒绝", () => {
    expect(() => dispatchWorkOrder("WO-NOT-EXIST", "Ali")).toThrow(WorkOrderTransitionError);
  });

  it("命名动作函数与 transitionWorkOrder 同一套校验（不能绕过）", () => {
    const w = fixture("CLOSED");
    expect(() => acceptWorkOrder(w.woNo)).toThrow(WorkOrderTransitionError);
    expect(() => completeWorkOrder(w.woNo, { handleNote: "x" })).toThrow(WorkOrderTransitionError);
    expect(() => closeWorkOrder(w.woNo, { auditResult: "PASS" })).toThrow(WorkOrderTransitionError);
  });
});

describe("必填校验", () => {
  it("关单没有验收结论 → 拒绝", () => {
    const w = fixture("DONE");
    // @ts-expect-error 故意传空结论，模拟前端漏传
    expect(() => closeWorkOrder(w.woNo, { auditResult: "" })).toThrow(/验收结论/);
    expect(w.status).toBe("DONE");
  });
  it("驳回没有原因 → 拒绝（沿用退款审批口径）", () => {
    const w = fixture("PROCESSING");
    expect(() => rejectWorkOrder(w.woNo, "   ")).toThrow(/原因/);
    expect(w.status).toBe("PROCESSING");
  });
  it("处理/完成没有处理说明 → 拒绝", () => {
    const w = fixture("PROCESSING");
    expect(() => processWorkOrder(w.woNo, { handleNote: "" })).toThrow(/处理说明/);
    expect(() => completeWorkOrder(w.woNo, { handleNote: "" })).toThrow(/处理说明/);
  });
  it("派单没有处理人 → 拒绝", () => {
    const w = fixture("CREATED");
    expect(() => dispatchWorkOrder(w.woNo, "")).toThrow(/处理人/);
  });
  it("开单缺机柜号 / 描述 → 拒绝", () => {
    expect(() => createWorkOrder({ type: "FAULT", cabinetNo: "", priority: "LOW", description: "x" })).toThrow(/机柜号/);
    expect(() => createWorkOrder({ type: "FAULT", cabinetNo: "CAB1", priority: "LOW", description: " " })).toThrow(/问题描述/);
  });
});

describe("联动：告警 / 投诉转来的工单能一路走到 CLOSED", () => {
  const walk = (woNo: string) => {
    dispatchWorkOrder(woNo, "Ali");
    acceptWorkOrder(woNo);
    processWorkOrder(woNo, { handleNote: "现场处理完毕" });
    completeWorkOrder(woNo, { handleNote: "已修复并复测" });
    return closeWorkOrder(woNo, { auditResult: "PASS" });
  };

  it("告警转工单 WO70200（source=ALERT）走完闭环", () => {
    const w = workOrders.find((x) => x.woNo === "WO70200")!;
    expect(w.source).toBe("ALERT");
    expect(w.sourceNo).toMatch(/^ALM/);
    expect(w.status).toBe("CREATED");
    expect(walk("WO70200").status).toBe("CLOSED");
  });

  it("投诉转工单 WO70300（source=USER）走完闭环", () => {
    const w = workOrders.find((x) => x.woNo === "WO70300")!;
    expect(w.source).toBe("USER");
    expect(w.sourceNo).toMatch(/^CPL/);
    expect(w.status).toBe("CREATED");
    expect(walk("WO70300").status).toBe("CLOSED");
  });
});

describe("开单编号", () => {
  let before: number;
  beforeEach(() => { before = workOrders.length; });
  it("新工单置顶插入且编号不与告警(70200+)/投诉(70300+)号段撞车", () => {
    const w = createWorkOrder({ type: "CLEAN", cabinetNo: workOrders[1].cabinetNo!, priority: "LOW", description: "清洁" });
    expect(workOrders.length).toBe(before + 1);
    expect(workOrders[0].woNo).toBe(w.woNo);
    expect(Number(w.woNo.slice(2))).toBeGreaterThanOrEqual(70400);
    expect(workOrders.filter((x) => x.woNo === w.woNo).length).toBe(1);
  });
});

describe("验收不合格退回返工（rework）", () => {
  const toDone = (no: string) => {
    dispatchWorkOrder(no, "王工");
    acceptWorkOrder(no, "王工");
    processWorkOrder(no, { handlerName: "王工", handleNote: "到场处理" });   // ACCEPTED→PROCESSING
    completeWorkOrder(no, { handlerName: "王工", handleNote: "已更换仓门电机" });
  };
  it("DONE 验收不合格 → 退回 PROCESSING，处理人保留（无需重新派单）", () => {
    const no = fixture("CREATED").woNo;
    toDone(no);
    const r = reworkWorkOrder(no, "仓门仍卡顿，未真正修复");
    expect(r.status).toBe("PROCESSING");
    expect(r.handlerName).toBe("王工");      // 与 reject 不同：不清空处理人
    expect(r.auditResult).toBe("FAIL");
    expect(r.completedAt).toBeNull();         // 完成时间作废
  });
  it("退回原因必填", () => {
    const no = fixture("CREATED").woNo;
    toDone(no);
    expect(() => reworkWorkOrder(no, "  ")).toThrow(/必须填写/);
  });
  it("只有 DONE 能返工：PROCESSING 状态返工被拒", () => {
    const no = fixture("CREATED").woNo;
    dispatchWorkOrder(no, "王工");
    acceptWorkOrder(no, "王工");
    expect(() => reworkWorkOrder(no, "不合格")).toThrow();
  });
  it("返工后可再次走完 完成 → 关单", () => {
    const no = fixture("CREATED").woNo;
    toDone(no);
    reworkWorkOrder(no, "未修好");
    completeWorkOrder(no, { handlerName: "王工", handleNote: "二次更换并测试通过" });
    const r = closeWorkOrder(no, { auditResult: "PASS", auditNote: "复检合格" });
    expect(r.status).toBe("CLOSED");
  });
});
