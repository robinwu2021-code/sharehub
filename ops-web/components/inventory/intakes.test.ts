import { describe, it, expect } from "vitest";
import { parseLines } from "./intakes";

describe("parseLines（入库单批量导入）", () => {
  it("编号 / 编号,单价 / 制表符 / 一行多台", () => {
    expect(parseLines("A1\nA2,12.5\nA3\t8\nB1,B2")).toEqual({ nos: ["A1", "A2", "A3", "B1", "B2"], costs: { A2: 12.5, A3: 8 } });
  });
});
