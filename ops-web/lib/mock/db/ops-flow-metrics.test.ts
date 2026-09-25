import { describe, it, expect } from "vitest";
import { opsFlowMetrics } from "./ops-flow-metrics";
import { leads } from "./location";

/**
 * 运营核心流程指标（mock）。口径镜像后端 OpsFlowMetricsService：
 * **分母为 0 返回 null**（「无数据」与「0%」是两回事），比率 0..1。
 */
describe("运营核心流程指标", () => {
  it("★ 分母为 0 的比率是 null，不是 0——「没有样本」与「全都没做到」是两回事", () => {
    const m = opsFlowMetrics({ from: "1999-01-01", to: "1999-01-02" });
    expect(m.woWithSla).toBe(0);
    expect(m.woSlaRate).toBeNull();
    expect(m.leadConversionRate).toBeNull();
    expect(m.renewalRate).toBeNull();
    expect(m.expiredNotRenewedRatio).toBeNull();
  });

  it("比率都落在 0..1；到期未续 = 1 − 续约率", () => {
    const m = opsFlowMetrics({ from: "2000-01-01", to: "2100-01-01" });
    for (const k of ["woSlaRate", "renewalRate", "expiredNotRenewedRatio", "leadConversionRate"] as const) {
      const v = m[k];
      if (v === null) continue;
      expect(v, k).toBeGreaterThanOrEqual(0);
      expect(v, k).toBeLessThanOrEqual(1);
    }
    if (m.renewalRate !== null) expect(m.expiredNotRenewedRatio! + m.renewalRate).toBeCloseTo(1, 4);
  });

  it("★ 从 db 现算：签下一条线索，转化率跟着变（写死的一组数验不出这个）", () => {
    const win = { from: "2000-01-01", to: "2100-01-01" };
    const before = opsFlowMetrics(win);
    const l = leads.find((x) => x.stage !== "SIGNED" && x.stage !== "LOST")!;
    const prev = l.stage;
    l.stage = "SIGNED";
    try {
      expect(opsFlowMetrics(win).leadsSigned).toBe(before.leadsSigned + 1);
    } finally {
      l.stage = prev;
    }
  });

  it("缺省窗口 = 最近 30 天，回带 from / to", () => {
    const m = opsFlowMetrics();
    expect(m.from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(new Date(m.to).getTime() - new Date(m.from).getTime()).toBe(30 * 86400_000);
  });
});
