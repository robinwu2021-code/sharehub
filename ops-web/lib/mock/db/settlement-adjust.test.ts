import { describe, it, expect } from "vitest";
import * as sa from "./settlement-adjust";
import { settlements } from "./finance";
import { ADJUSTMENT_TRANSITIONS, canAdjustmentTransition } from "../../types";

/**
 * 结算调整项 / 结算单详情 / 对账单的 mock 回归。
 * 钉的是与后端 AdjustmentServiceImpl 的一致性：确认只认待确认、改了金额必须写说明、
 * 作废必须写原因且已并入结算单的不能作废。
 */
describe("确认", () => {
  it("按建议值确认：不写说明也放行，落 CONFIRMED 并记确认人", () => {
    const a = sa._pushAdjustment();
    const r = sa.confirmSettlementAdjustment(a.adjNo, {}, "Sara Ahmed");
    expect(r.status).toBe("CONFIRMED");
    expect(r.amount).toBe(-1000);
    expect(r.confirmedBy).toBe("Sara Ahmed");
    expect(r.confirmedAt).toBeTruthy();
  });

  it("★ 金额与建议值不同必须写说明 —— 押金退多少写在合同的自由文本里，改了不留痕就无从追溯", () => {
    const a = sa._pushAdjustment();
    expect(() => sa.confirmSettlementAdjustment(a.adjNo, { amount: -800 })).toThrow(/原因|note/);
    expect(a.status).toBe("PENDING");   // 被拒之后不能把单子改坏
    const r = sa.confirmSettlementAdjustment(a.adjNo, { amount: -800, note: "扣除 200 设备损坏" });
    expect(r.amount).toBe(-800);
    expect(r.suggestedAmount).toBe(-1000);   // 建议值留着，审计要看差了多少
    expect(r.note).toMatch(/确认：扣除 200 设备损坏/);
    expect(r.note).toMatch(/^撤场退还押金/);   // 追加，不覆盖系统依据
  });

  it("只有待确认的能确认", () => {
    const a = sa._pushAdjustment({ status: "CONFIRMED" });
    expect(() => sa.confirmSettlementAdjustment(a.adjNo, {})).toThrow(/待确认|not pending/);
  });
});

describe("作废", () => {
  it("原因必填", () => {
    const a = sa._pushAdjustment();
    expect(() => sa.voidSettlementAdjustment(a.adjNo, " ")).toThrow(/原因|Reason/);
    expect(a.status).toBe("PENDING");
  });

  it.each(["PENDING", "CONFIRMED"] as const)("%s 可作废，原因追加进说明", (status) => {
    const a = sa._pushAdjustment({ status });
    const r = sa.voidSettlementAdjustment(a.adjNo, "合同约定不退");
    expect(r.status).toBe("VOID");
    expect(r.note).toMatch(/作废：合同约定不退/);
  });

  it.each(["SETTLED", "VOID"] as const)("★ %s 不能作废 —— 已并入结算单的钱已经算进去了", (status) => {
    const a = sa._pushAdjustment({ status });
    expect(() => sa.voidSettlementAdjustment(a.adjNo, "x")).toThrow();
    expect(a.status).toBe(status);
  });

  it("迁移表与 mock 判据是同一份（页面按钮用它决定显不显示）", () => {
    expect(ADJUSTMENT_TRANSITIONS.confirm.from).toEqual(["PENDING"]);
    expect(canAdjustmentTransition("SETTLED", "void")).toBe(false);
    expect(canAdjustmentTransition("CONFIRMED", "void")).toBe(true);
  });
});

describe("列表筛选", () => {
  it("状态 / 种类 / 来源都能筛，且真落在返回行上", () => {
    const byKind = sa.listSettlementAdjustments({ kind: "GUARANTEE_TOPUP", size: 100 });
    expect(byKind.total).toBeGreaterThan(0);
    expect(byKind.list.every((a) => a.kind === "GUARANTEE_TOPUP")).toBe(true);
    const bySource = sa.listSettlementAdjustments({ source: "SITE_CLOSED", status: "PENDING", size: 100 });
    expect(bySource.list.every((a) => a.source === "SITE_CLOSED" && a.status === "PENDING")).toBe(true);
  });

  it("保底补差来自合同计算，种子里是已确认 —— 不需要人再拍一次板", () => {
    const g = sa.listSettlementAdjustments({ source: "GUARANTEE", size: 100 }).list;
    expect(g.length).toBeGreaterThan(0);
    expect(g.every((a) => a.status !== "PENDING")).toBe(true);
  });
});

describe("结算单详情与对账单", () => {
  const stl = () => settlements.find((s) => s.payeeType === "VENUE")!;

  it("详情 = 本体 + 构成行；构成行金额之和 = 分润合计", () => {
    const v = sa.getSettlementView(stl().settleNo);
    expect(v.settlement.settleNo).toBe(stl().settleNo);
    const shareSum = v.details.filter((d) => d.refType === "SHARE").reduce((t, d) => t + d.amount, 0);
    expect(Math.round(shareSum * 100) / 100).toBe(stl().totalAmount);
  });

  it("对账单：本期应付 = 分成合计 + 调整项合计", () => {
    const st = sa.getSettlementStatement(stl().settleNo);
    expect(st.total).toBeCloseTo(st.shareTotal + st.adjustTotal, 2);
    expect(st.shareTotal).toBeCloseTo(st.shares.reduce((t, x) => t + x.amount, 0), 2);
  });

  it("可打印版：ar 右到左，内容做了转义", () => {
    const html = sa.settlementStatementHtml(stl().settleNo, "ar");
    expect(html).toMatch(/dir="rtl"/);
    expect(html).toContain(stl().settleNo);
    expect(sa.settlementStatementHtml(stl().settleNo, "en")).toMatch(/dir="ltr"/);
  });

  it("不存在的结算单报 404", () => {
    expect(() => sa.getSettlementView("STL-NOPE")).toThrow(/不存在|not found/);
  });
});
