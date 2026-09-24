import { describe, it, expect } from "vitest";
import { mockApi } from "../../api/mock";

/**
 * 分润规则必须存**分成方编号**。
 *
 * 取价按 `payeeNo` 精确匹配：只有名字的规则在分账时一条都命中不了 ——
 * 界面上看着配好了，那个分成方却拿不到钱，而且**不报错**。
 * 守卫落在服务端（mock db），绕过表单直调同样被拒。
 */
describe("分润规则的分成方", () => {
  it("缺编号时拒绝保存", async () => {
    await expect(mockApi.saveShareRule({
      dimension: "AGENT", payeeName: "只有名字没有编号", mode: "LEDGER", rate: 0.2, priority: 1,
    })).rejects.toThrow();
  });

  it("悬空的编号也拒绝", async () => {
    // 编号对不上任何主数据 = 规则永远命中不了，而且看不出为什么
    await expect(mockApi.saveShareRule({
      dimension: "AGENT", payeeNo: "AG-NOT-EXIST", mode: "LEDGER", rate: 0.2, priority: 1,
    })).rejects.toThrow();
  });

  it("名字以主数据为准，不采信入参", async () => {
    // 传一个错名字进去，存下来的应该是编号对应的真实名称 ——
    // 否则列表里会出现「编号是 A、名字写着 B」这种对不上的行
    const saved = await mockApi.saveShareRule({
      dimension: "AGENT", payeeNo: "AG001", payeeName: "随便写的名字",
      mode: "LEDGER", rate: 0.2, priority: 1,
    });
    expect(saved.payeeNo).toBe("AG001");
    expect(saved.payeeName).not.toBe("随便写的名字");
  });
});
