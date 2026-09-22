// 报障单两个「出口」动作的幂等性单测。
//
// 背景：两个动作在后端都以 ticketNo 为幂等键，而重复执行的代价是**真实损失**——
//   · 转退款重复 → 同一笔订单退两次钱（资金事故）
//   · 转工单重复 → 同一个故障派两次现场（运维成本）
// 页面上的「转工单」此前是 `alert("...（mock）")` 伪实现：点几次都只弹提示、什么都没发生，
// 看起来能用其实完全没接后端（后端 POST /cs/tickets/{no}/work-order 一直存在却从未被调用）。
// 故这里把两个动作的幂等语义一起钉死，防止再退化成「看起来能用」。
import { describe, it, expect } from "vitest";
import { csTickets, refundCsTicket, woCsTicket } from "./cs";

/** 取一条还没转过工单的报障单（用例会改状态，故每次现取）。 */
const anyWithoutWo = () => csTickets.find((x) => !x.woNo && x.status !== "CLOSED")!;
/** 取一条有订单号、还没转退款的报障单。 */
const anyRefundable = () => csTickets.find((x) => x.orderNo && !x.refundNo && x.status !== "CLOSED")!;

describe("报障转工单", () => {
  it("首次转单：生成工单号，OPEN 推进到 PROCESSING", () => {
    const tk = anyWithoutWo();
    const wasOpen = tk.status === "OPEN";
    const r = woCsTicket(tk.ticketNo);
    expect(r.woNo).toMatch(/^WO\d+$/);
    if (wasOpen) expect(r.status).toBe("PROCESSING");
  });

  it("重复转单：幂等 —— 沿用原工单号，不开第二张", () => {
    const tk = anyWithoutWo();
    const first = woCsTicket(tk.ticketNo).woNo;
    const again = woCsTicket(tk.ticketNo).woNo;
    expect(again).toBe(first);
  });

  it("已 CLOSED 的单不因转工单复活（与 refundCsTicket 同一约束）", () => {
    const closed = csTickets.find((x) => x.status === "CLOSED")!;
    woCsTicket(closed.ticketNo);
    expect(closed.status).toBe("CLOSED");
  });

  it("工单号段不与告警(70200)/投诉(70300)转单撞号", () => {
    const tk = anyWithoutWo();
    const no = Number(woCsTicket(tk.ticketNo).woNo.replace("WO", ""));
    expect(no).toBeGreaterThanOrEqual(70400);
  });

  it("报障单不存在直接抛", () => {
    expect(() => woCsTicket("TK-NOPE")).toThrow();
  });
});

describe("报障转退款", () => {
  it("重复转退款：幂等 —— 沿用原退款单号，绝不建第二笔", () => {
    const tk = anyRefundable();
    const first = refundCsTicket(tk.ticketNo).refundNo;
    const again = refundCsTicket(tk.ticketNo).refundNo;
    expect(again).toBe(first);
    expect(first).toBeTruthy();
  });

  it("未关联订单的报障单不可转退款（退款必须挂在一笔真实交易上）", () => {
    const noOrder = csTickets.find((x) => !x.orderNo && !x.refundNo);
    if (!noOrder) return; // fixture 里没有这种行时跳过，不硬造
    expect(() => refundCsTicket(noOrder.ticketNo)).toThrow();
  });
});
