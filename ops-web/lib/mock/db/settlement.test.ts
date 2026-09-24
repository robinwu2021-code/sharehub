// 结算单（S1）：生成 → 确认 的守卫测试。
//
// 结算单页从前是纯只读，`finance:settlement:generate/:confirm` 两个权限码定义了从没被用过。
// 补上写操作后，三件事必须由服务端（本 db 层）兜住，不能指望页面自觉：
//  ① 金额只能来自分润明细汇总（不凭空造数）；② 同 对象+周期 幂等，不许重复出账；
//  ③ 状态机强制——GEN 之外的状态不允许再确认。
import { describe, expect, it } from "vitest";
import {
  settlements, generateSettlements, confirmSettlement, listSettlementRecords,
  aggregateShareRecords, listShareSummaries, SettlementError,
} from "./finance";
import { venues } from "./location";

// 种子只出到 2026-06，2026-07 留给「真去生成一次」；2026-04 压根没有明细。
const OPEN_PERIOD = "2026-07";
const NO_RECORD_PERIOD = "2026-04";
const venueNoAt = (i: number) => venues[i].venueNo;

describe("生成结算单", () => {
  it("金额 = 该周期分润明细汇总，状态为 GEN，明细笔数对得上", () => {
    const payeeNo = venueNoAt(0);
    const agg = aggregateShareRecords("VENUE", payeeNo, OPEN_PERIOD);
    expect(agg.recordCount).toBeGreaterThan(0);

    const [s] = generateSettlements({ payeeType: "VENUE", payeeNos: [payeeNo], period: OPEN_PERIOD });

    expect(s.status).toBe("GEN");
    expect(s.totalAmount).toBe(agg.totalAmount);
    expect(s.recordCount).toBe(agg.recordCount);
    expect(s.confirmedBy).toBeNull();
    // 落库了：能在列表里查到
    expect(settlements.some((x) => x.settleNo === s.settleNo)).toBe(true);
  });

  it("详情明细就是构成金额的那几笔，逐笔加起来 = 结算金额", () => {
    const payeeNo = venueNoAt(1);
    const [s] = generateSettlements({ payeeType: "VENUE", payeeNos: [payeeNo], period: OPEN_PERIOD });
    const rows = listSettlementRecords(s.settleNo, { page: 1, size: 100 }).list;

    expect(rows).toHaveLength(s.recordCount);
    expect(rows.every((r) => r.payeeNo === payeeNo && r.period === OPEN_PERIOD)).toBe(true);
    expect(Number(rows.reduce((sum, r) => sum + r.amount, 0).toFixed(2))).toBe(s.totalAmount);
  });

  it("幂等：同 对象+周期 已存在结算单则拒绝重复生成（原单不受影响）", () => {
    const payeeNo = venueNoAt(2);
    const [first] = generateSettlements({ payeeType: "VENUE", payeeNos: [payeeNo], period: OPEN_PERIOD });
    const before = settlements.length;

    expect(() => generateSettlements({ payeeType: "VENUE", payeeNos: [payeeNo], period: OPEN_PERIOD }))
      .toThrow(SettlementError);
    expect(() => generateSettlements({ payeeType: "VENUE", payeeNos: [payeeNo], period: OPEN_PERIOD }))
      .toThrow(new RegExp(first.settleNo));
    expect(settlements.length).toBe(before);
  });

  it("批量里只要有一个重复，整批拒绝——不做半成功", () => {
    const dupNo = venueNoAt(3);
    generateSettlements({ payeeType: "VENUE", payeeNos: [dupNo], period: OPEN_PERIOD });
    const freshNo = venueNoAt(4);
    const before = settlements.length;

    expect(() => generateSettlements({ payeeType: "VENUE", payeeNos: [freshNo, dupNo], period: OPEN_PERIOD }))
      .toThrow(SettlementError);
    expect(settlements.length).toBe(before);
    expect(settlements.some((x) => x.payeeNo === freshNo && x.period === OPEN_PERIOD)).toBe(false);
  });

  it("该周期无分润明细 → 拒绝（金额不凭空生成）", () => {
    expect(() => generateSettlements({ payeeType: "VENUE", payeeNos: [venueNoAt(0)], period: NO_RECORD_PERIOD }))
      .toThrow(/无分润明细/);
  });

  it("入参校验：周期格式、对象类型、空对象列表", () => {
    expect(() => generateSettlements({ payeeType: "VENUE", payeeNos: [venueNoAt(0)], period: "2026/07" }))
      .toThrow(/结算周期格式/);
    expect(() => generateSettlements({ payeeType: "VENUE", payeeNos: [], period: OPEN_PERIOD }))
      .toThrow(/至少选择一个结算对象/);
    expect(() => generateSettlements({ payeeType: "VENUE", payeeNos: ["VEN999"], period: OPEN_PERIOD }))
      .toThrow(/不存在/);
  });
});

describe("确认结算：状态机强制", () => {
  it("GEN → CONFIRMED，记确认人与确认时间", () => {
    const [s] = generateSettlements({ payeeType: "AGENT", payeeNos: ["AG001"], period: OPEN_PERIOD });
    const done = confirmSettlement(s.settleNo, "Sara Ahmed");

    expect(done.status).toBe("CONFIRMED");
    expect(done.confirmedBy).toBe("Sara Ahmed");
    expect(done.confirmedAt).toBeTruthy();
  });

  it("已确认的单再次确认 → 非法迁移被拒", () => {
    const [s] = generateSettlements({ payeeType: "AGENT", payeeNos: ["AG002"], period: OPEN_PERIOD });
    confirmSettlement(s.settleNo);
    expect(() => confirmSettlement(s.settleNo)).toThrow(SettlementError);
    expect(() => confirmSettlement(s.settleNo)).toThrow(/不允许执行/);
  });

  it("已打款的单不允许再确认，不存在的单直接报错", () => {
    const paid = settlements.find((x) => x.status === "PAID")!;
    expect(() => confirmSettlement(paid.settleNo)).toThrow(/不允许执行/);
    expect(() => confirmSettlement("STL99999")).toThrow(/不存在/);
  });

  it("确认后分润统计的「已结算/待结算」跟着变（三页同口径）", () => {
    const payeeNo = "AG003";
    const summaryOf = () => listShareSummaries({ dimension: "AGENT", period: OPEN_PERIOD, size: 100 })
      .list.find((x) => x.payeeNo === payeeNo)!;
    const before = summaryOf();
    expect(before.pendingAmount).toBe(before.shareAmount);

    const [s] = generateSettlements({ payeeType: "AGENT", payeeNos: [payeeNo], period: OPEN_PERIOD });
    // 草稿不算已结算
    expect(summaryOf().settledAmount).toBe(0);

    confirmSettlement(s.settleNo);
    const after = summaryOf();
    expect(after.settledAmount).toBe(s.totalAmount);
    expect(after.pendingAmount).toBe(Number((after.shareAmount - s.totalAmount).toFixed(2)));
  });
});
