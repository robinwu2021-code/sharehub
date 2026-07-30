// 订单干预 + 押金处置状态机单测（S1 的防复发机制）。
//
// 背景：`interveneOrder` 在 mock 层长期只 `wait({ok:true})` —— eject / force_return /
// waive / compensate 四个动作点了**订单状态一点不变**（清单里判定为唯一的 F0 伪实现）。
// 光把逻辑补上不够：没有测试钉住，下次重构又会退化成"点了没反应"。本文件钉住三件事：
//   ① 四个干预动作真的改了订单（状态 / 时间 / 费用 / 免单额 / 补偿额）且都落了干预记录
//   ② 干预原因必填、非法状态迁移必须**抛错**，不能默默通过
//   ③ 押金三个处置动作（解冻/买断/催缴）走状态机，终态不可再处置、非法迁移被拒
import { describe, it, expect } from "vitest";
import type { OrderStatus, DepositStatus, RentOrder, DepositRecord } from "../../types";
import { ORDER_INTERVENTIONS, canIntervene, interveneActions, depositActions } from "../../types";
import {
  orders, depositRecords, orderInterventions, listOrderInterventions,
  interveneOrder, OrderInterventionError,
  releaseDeposit, buyoutDeposit, dunArrears, DepositTransitionError,
} from "./order";

/** 造一条指定状态的订单（直接落数组，绕开状态机——测试夹具允许，业务代码不允许）。 */
let seq = 0;
function order(status: OrderStatus, patch: Partial<RentOrder> = {}): RentOrder {
  const src = orders[0];
  const o: RentOrder = {
    ...src,
    orderNo: `ORD9${String(900 + ++seq)}`,
    status,
    rentStartAt: new Date(Date.UTC(2026, 6, 11, 10, 0, 0)).toISOString(), // mock「当前」时间前 2 小时
    rentEndAt: null,
    durationMin: null,
    feeAmount: 12,
    ...patch,
  };
  orders.push(o);
  return o;
}

/** 造一条指定状态的押金单。 */
function deposit(status: DepositStatus, patch: Partial<DepositRecord> = {}): DepositRecord {
  const d: DepositRecord = {
    depositNo: `DEP9${String(900 + ++seq)}`,
    orderNo: orders[0].orderNo,
    userNo: orders[0].cUserNo,
    amount: 99,
    currency: "AED",
    status,
    arrearsAmount: status === "ARREARS" ? 25 : 0,
    createdAt: new Date().toISOString(),
    dunCount: 0,
    ...patch,
  };
  depositRecords.push(d);
  return d;
}

const REASON = "单测：客服人工处置";
const ivOf = (orderNo: string) => listOrderInterventions({ orderNo, size: 50 }).list;

describe("干预状态机定义", () => {
  it("五个动作齐备（四个处置 + 申请退款）", () => {
    expect(Object.keys(ORDER_INTERVENTIONS).sort()).toEqual(
      ["compensate", "eject", "force_return", "refund_apply", "waive"],
    );
  });

  it("interveneActions 给出当前状态可执行的动作（详情抽屉按钮据此渲染）", () => {
    // 已创建：还没弹出，只能补弹
    expect(interveneActions("CREATED")).toEqual(["eject"]);
    // 使用中：宝已在用户手上，不能再弹；可强制归还 / 免单 / 补偿
    expect(interveneActions("IN_USE").sort()).toEqual(["compensate", "force_return", "waive"]);
    // 已关闭：不再动账，只能事后补偿 / 申请退款
    expect(interveneActions("CLOSED").sort()).toEqual(["compensate", "refund_apply"]);
    expect(canIntervene("CLOSED", "waive")).toBe(false);
  });
});

describe("四个干预动作真落库（修 F0：原先状态一点不变）", () => {
  it("eject 远程弹出：CREATED → DISPENSING，记一次弹出并落干预记录", () => {
    const o = order("CREATED");
    const { order: after, intervention } = interveneOrder(o.orderNo, "eject", { reason: REASON, operatorName: "Sara" });
    expect(after.status).toBe("DISPENSING");
    expect(orders.find((x) => x.orderNo === o.orderNo)!.status).toBe("DISPENSING"); // 真改了库里的那一条
    expect(after.ejectCount).toBe(1);
    expect(after.lastEjectAt).toBeTruthy();
    expect(intervention).toMatchObject({
      orderNo: o.orderNo, action: "eject", operatorName: "Sara",
      reason: REASON, beforeStatus: "CREATED", afterStatus: "DISPENSING",
    });
    expect(ivOf(o.orderNo)).toHaveLength(1);

    // 可重复补弹：次数累加、记录再落一条
    const again = interveneOrder(o.orderNo, "eject", { reason: "第二次补弹" });
    expect(again.order.ejectCount).toBe(2);
    expect(ivOf(o.orderNo)).toHaveLength(2);
  });

  it("force_return 强制归还：IN_USE → SETTLED，写结束时间 / 时长 / 费用 / 归还柜机", () => {
    const o = order("IN_USE", { returnCabinetNo: null });
    const { order: after, intervention } = interveneOrder(o.orderNo, "force_return", { reason: REASON });
    expect(after.status).toBe("SETTLED");
    expect(after.rentEndAt).toBeTruthy();
    expect(after.durationMin).toBe(120); // 借出 2 小时前 → 120 分钟
    expect(after.feeAmount).toBe(12); // 每 30 分钟 3 元：ceil(120/30)*3
    expect(after.returnCabinetNo).toBe(o.cabinetNo); // 缺省按借出柜机记
    expect(intervention.amount).toBe(12);
    expect(intervention.afterStatus).toBe("SETTLED");
  });

  it("waive 免单：应收置 0、减免额单独记账，状态不变", () => {
    const o = order("SETTLED", { feeAmount: 9 });
    const { order: after, intervention } = interveneOrder(o.orderNo, "waive", { reason: REASON });
    expect(after.feeAmount).toBe(0);
    expect(after.waivedAmount).toBe(9);
    expect(after.status).toBe("SETTLED");
    expect(intervention).toMatchObject({ action: "waive", amount: 9, beforeStatus: "SETTLED", afterStatus: "SETTLED" });
  });

  it("waive 免单：应收已为 0 的单拒绝重复免单", () => {
    const o = order("SETTLED", { feeAmount: 0 });
    expect(() => interveneOrder(o.orderNo, "waive", { reason: REASON })).toThrow(/无需免单/);
    expect(ivOf(o.orderNo)).toHaveLength(0); // 失败不留痕
  });

  it("compensate 补偿：累加补偿金额（mock 口径为补至余额），状态不变", () => {
    const o = order("SETTLED");
    interveneOrder(o.orderNo, "compensate", { reason: REASON, amount: 5 });
    const { order: after } = interveneOrder(o.orderNo, "compensate", { reason: REASON, amount: 2.5 });
    expect(after.compensateAmount).toBe(7.5);
    expect(after.status).toBe("SETTLED");
    expect(ivOf(o.orderNo).map((x) => x.amount)).toEqual([2.5, 5]); // 最新在前
  });

  it("compensate 补偿：金额缺失或非正数被拒", () => {
    const o = order("SETTLED");
    expect(() => interveneOrder(o.orderNo, "compensate", { reason: REASON })).toThrow(/补偿金额必须大于 0/);
    expect(() => interveneOrder(o.orderNo, "compensate", { reason: REASON, amount: 0 })).toThrow(/补偿金额必须大于 0/);
  });

  it("refund_apply 只留痕不改状态（真正出款走退款审批队列）", () => {
    const o = order("SETTLED", { feeAmount: 15 });
    const { order: after, intervention } = interveneOrder(o.orderNo, "refund_apply", { reason: REASON });
    expect(after.status).toBe("SETTLED");
    expect(intervention).toMatchObject({ action: "refund_apply", amount: 15 });
  });
});

describe("干预的两条硬约束", () => {
  it("原因必填 —— 空白原因一律拒绝（沿用退款审批口径）", () => {
    const o = order("CREATED");
    expect(() => interveneOrder(o.orderNo, "eject", { reason: "   " })).toThrow(OrderInterventionError);
    expect(orders.find((x) => x.orderNo === o.orderNo)!.status).toBe("CREATED"); // 状态没动
    expect(ivOf(o.orderNo)).toHaveLength(0);
  });

  it("非法迁移必须抛错，不能默默通过", () => {
    const inUse = order("IN_USE");
    expect(() => interveneOrder(inUse.orderNo, "eject", { reason: REASON })).toThrow(OrderInterventionError); // 宝已弹出
    const created = order("CREATED");
    expect(() => interveneOrder(created.orderNo, "waive", { reason: REASON })).toThrow(OrderInterventionError); // 还没产生费用
    expect(() => interveneOrder(created.orderNo, "refund_apply", { reason: REASON })).toThrow(OrderInterventionError);
    const closed = order("CLOSED");
    expect(() => interveneOrder(closed.orderNo, "force_return", { reason: REASON })).toThrow(OrderInterventionError);
  });

  it("订单不存在直接抛错", () => {
    expect(() => interveneOrder("ORD_NOT_EXIST", "eject", { reason: REASON })).toThrow(/不存在/);
  });

  it("种子数据里的干预记录与订单状态天然一致（记录都是走干预入口生成的）", () => {
    expect(orderInterventions.length).toBeGreaterThanOrEqual(4);
    for (const iv of orderInterventions) {
      const o = orders.find((x) => x.orderNo === iv.orderNo);
      expect(o, `干预记录 ${iv.interventionNo} 指向不存在的订单`).toBeTruthy();
      const to = ORDER_INTERVENTIONS[iv.action].to;
      if (to) expect(iv.afterStatus).toBe(to);
      else expect(iv.afterStatus).toBe(iv.beforeStatus);
      expect(iv.reason.trim()).not.toBe("");
    }
  });
});

describe("押金状态机", () => {
  it("depositActions：终态没有下一步", () => {
    expect(depositActions("HELD").sort()).toEqual(["buyout", "release"]);
    expect(depositActions("ARREARS")).toEqual(["dun"]);
    expect(depositActions("RELEASED")).toEqual([]);
    expect(depositActions("BOUGHT_OUT")).toEqual([]);
  });

  it("解冻：HELD → RELEASED，落解冻时间与原因", () => {
    const d = deposit("HELD");
    const r = releaseDeposit(d.depositNo, "订单已结清");
    expect(r.status).toBe("RELEASED");
    expect(r.releasedAt).toBeTruthy();
    expect(r.note).toBe("订单已结清");
  });

  it("买断：HELD → BOUGHT_OUT，写买断金额", () => {
    const d = deposit("HELD", { amount: 199 });
    const r = buyoutDeposit(d.depositNo, { amount: 150, reason: "超时未归还" });
    expect(r.status).toBe("BOUGHT_OUT");
    expect(r.buyoutAmount).toBe(150);
    expect(r.buyoutAt).toBeTruthy();
  });

  it("催缴：ARREARS 记一次催缴（次数 +1、最后催缴时间/渠道），状态不变", () => {
    const d = deposit("ARREARS");
    const r1 = dunArrears(d.depositNo, { channel: "SMS" });
    expect(r1.status).toBe("ARREARS");
    expect(r1.dunCount).toBe(1);
    expect(r1.lastDunChannel).toBe("SMS");
    expect(r1.lastDunAt).toBeTruthy();
    const r2 = dunArrears(d.depositNo, { channel: "PHONE", note: "已联系用户本人" });
    expect(r2.dunCount).toBe(2);
    expect(r2.note).toBe("已联系用户本人");
  });

  it("非法迁移被拒：重复解冻 / 已买断再解冻 / 非欠费催缴 / 欠费单解冻", () => {
    const released = deposit("RELEASED");
    expect(() => releaseDeposit(released.depositNo, "再解冻一次")).toThrow(DepositTransitionError);
    const bought = deposit("BOUGHT_OUT");
    expect(() => releaseDeposit(bought.depositNo, "解冻")).toThrow(DepositTransitionError);
    expect(() => buyoutDeposit(bought.depositNo, { amount: 10, reason: "再买断" })).toThrow(DepositTransitionError);
    const held = deposit("HELD");
    expect(() => dunArrears(held.depositNo, { channel: "SMS" })).toThrow(DepositTransitionError); // 没欠费催什么
    const arrears = deposit("ARREARS");
    expect(() => releaseDeposit(arrears.depositNo, "解冻")).toThrow(DepositTransitionError); // 欠着钱不能解冻
  });

  it("必填校验：解冻原因 / 买断金额与原因 / 催缴渠道", () => {
    const d1 = deposit("HELD", { amount: 99 });
    expect(() => releaseDeposit(d1.depositNo, "  ")).toThrow(/必须填写原因/);
    expect(() => buyoutDeposit(d1.depositNo, { amount: 0, reason: "x" })).toThrow(/必须大于 0/);
    expect(() => buyoutDeposit(d1.depositNo, { amount: 120, reason: "x" })).toThrow(/不得超过押金额/);
    expect(() => buyoutDeposit(d1.depositNo, { amount: 50, reason: " " })).toThrow(/必须填写原因/);
    expect(d1.status).toBe("HELD"); // 全部失败，状态没动
    const d2 = deposit("ARREARS");
    // 渠道必选：前端是 Select 不会漏，后端/mock 仍要兜住
    expect(() => dunArrears(d2.depositNo, {} as { channel: never })).toThrow(/催缴渠道/);
  });

  it("押金单不存在直接抛错", () => {
    expect(() => releaseDeposit("DEP_NOT_EXIST", "x")).toThrow(/不存在/);
  });
});
