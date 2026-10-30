import { describe, it, expect } from "vitest";
import { tally, totalRow } from "./stock-tally";

describe("库存余额汇总", () => {
  const items = [
    { wh: "WH1", st: "IN_STOCK" }, { wh: "WH1", st: "IN_STOCK" }, { wh: "WH1", st: "FAULT" },
    { wh: "WH2", st: "IN_STOCK" },
  ];
  it("按维度分组、逐状态计数，多的在前", () => {
    const rows = tally(items, (x) => x.wh, (x) => x.st);
    expect(rows.map((r) => [r.key, r.total, r.counts])).toEqual([
      ["WH1", 3, { IN_STOCK: 2, FAULT: 1 }],
      ["WH2", 1, { IN_STOCK: 1 }],
    ]);
  });
  it("合计行是各行之和，状态逐项相加", () => {
    const t = totalRow(tally(items, (x) => x.wh, (x) => x.st));
    expect(t).toEqual({ key: "合计", total: 4, counts: { IN_STOCK: 3, FAULT: 1 } });
  });
  it("同数时按取值排序，结果稳定", () => {
    const rows = tally([{ k: "B" }, { k: "A" }], (x) => x.k, () => "S");
    expect(rows.map((r) => r.key)).toEqual(["A", "B"]);
  });
  it("空集没有行，合计为 0", () => {
    expect(tally([], () => "", () => "")).toEqual([]);
    expect(totalRow([])).toEqual({ key: "合计", total: 0, counts: {} });
  });
});
