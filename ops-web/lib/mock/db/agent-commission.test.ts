import { describe, it, expect } from "vitest";
import { saveAgentCommission, agentCommissions } from "./agent";

/**
 * 商家分润规则 mock 回归 —— 钉的是与后端 AgentCommissionServiceImpl 的一致性：
 *  · checkDimension：计佣基数决定必填项（GMV → 比例 0..1；ORDER_COUNT → 单均固定额 + 币种）；
 *  · beforeUpdate：编辑不改归属商家；
 *  · 写入参没有 status：编辑带上 status 也不生效。
 *
 * 为什么要钉：表单此前不论基数都只收比例 —— 选「订单量」保存，mock 照收、真后端必报错，
 * 而种子里那条「订单量 + 比例」的规则让这个缺陷在 mock 上一直看不出来。
 */
describe("商家分润规则写入（mock 与后端 checkDimension 同口径）", () => {
  it("GMV：比例必填且在 0~1，固定额归零", () => {
    expect(() => saveAgentCommission({ agentNo: "AG001", basis: "GMV", effectiveAt: "2026-11-01" })).toThrow(/比例|Rate/);
    expect(() => saveAgentCommission({ agentNo: "AG001", basis: "GMV", rate: 20, effectiveAt: "2026-11-01" })).toThrow(/0~1|between/);
    const r = saveAgentCommission({ agentNo: "AG001", basis: "GMV", rate: 0.2, fixedAmount: 9, effectiveAt: "2026-11-01" });
    expect(r.rate).toBe(0.2);
    expect(r.fixedAmount).toBe(0);
    expect(r.status).toBe("ACTIVE");
    expect(r.agentName).toBe(agentCommissions.find((c) => c.agentNo === "AG001" && c.ruleNo !== r.ruleNo)?.agentName);
  });

  it("ORDER_COUNT：单均固定额与币种必填，比例不参与", () => {
    expect(() => saveAgentCommission({ agentNo: "AG002", basis: "ORDER_COUNT", currency: "AED", effectiveAt: "2026-11-01" }))
      .toThrow(/固定额|Fixed amount/);
    expect(() => saveAgentCommission({ agentNo: "AG002", basis: "ORDER_COUNT", fixedAmount: -1, currency: "AED", effectiveAt: "2026-11-01" }))
      .toThrow(/不可为负|negative/);
    expect(() => saveAgentCommission({ agentNo: "AG002", basis: "ORDER_COUNT", fixedAmount: 0.5, effectiveAt: "2026-11-01" }))
      .toThrow(/币种|Currency/);
    const r = saveAgentCommission({ agentNo: "AG002", basis: "ORDER_COUNT", fixedAmount: 0.5, rate: 0.3, currency: "AED", effectiveAt: "2026-11-01" });
    expect(r.fixedAmount).toBe(0.5);
    expect(r.rate).toBe(0);
  });

  it("编辑不改归属、不改状态", () => {
    const created = saveAgentCommission({ agentNo: "AG003", basis: "GMV", rate: 0.1, effectiveAt: "2026-11-01" });
    const edited = saveAgentCommission({ ruleNo: created.ruleNo, agentNo: "AG004", basis: "GMV", rate: 0.15, status: "INACTIVE" });
    expect(edited.agentNo).toBe("AG003");
    expect(edited.status).toBe("ACTIVE");
    expect(edited.rate).toBe(0.15);
  });

  it("新建必须指定存在的商家", () => {
    expect(() => saveAgentCommission({ basis: "GMV", rate: 0.1 })).toThrow(/商家|Merchant/);
    expect(() => saveAgentCommission({ agentNo: "AG999", basis: "GMV", rate: 0.1 })).toThrow();
  });

  it("种子里没有违反基数口径的规则", () => {
    for (const c of agentCommissions) {
      if (c.basis === "ORDER_COUNT") {
        expect(c.fixedAmount, `${c.ruleNo} 按订单数计佣却没有单均固定额`).not.toBeNull();
        expect(c.currency, `${c.ruleNo} 按订单数计佣却没有币种`).toBeTruthy();
      } else {
        expect(c.rate, `${c.ruleNo} 比例越界`).toBeGreaterThanOrEqual(0);
        expect(c.rate, `${c.ruleNo} 比例越界`).toBeLessThanOrEqual(1);
      }
    }
  });
});
