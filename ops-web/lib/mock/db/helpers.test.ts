// nextNo 撞号回归测试。
// 背景：旧实现 `base + arr.length` 在「数据从 base+1 起编号」与「删后新增」两种情况下必然撞号，
// 2026-07-29 结构治理时实测 saveProblem/saveNotifyBlacklist 会 upsert 出同号两条。
import { describe, it, expect } from "vitest";
import { nextNo, upsert } from "./helpers";

describe("nextNo 不撞号", () => {
  it("空数组 → base", () => {
    expect(nextNo("PB", [])).toBe("PB900");
  });
  it("从 base 起编号（PB900..PB908 共 9 条）→ PB909", () => {
    const arr = Array.from({ length: 9 }, (_, i) => ({ problemNo: `PB${900 + i}` }));
    expect(nextNo("PB", arr)).toBe("PB909");
  });
  it("⚠️ 从 base+1 起编号（PB901..PB909 共 9 条）→ PB910，不再撞 PB909", () => {
    const arr = Array.from({ length: 9 }, (_, i) => ({ problemNo: `PB${901 + i}` }));
    expect(nextNo("PB", arr)).toBe("PB910");
  });
  it("⚠️ 删一条再新增不撞号", () => {
    const arr = [{ no: "BL903" }, { no: "BL902" }, { no: "BL901" }];
    arr.splice(1, 1); // 删掉中间一条，length 变小
    expect(nextNo("BL", arr)).toBe("BL904");
  });
  it("忽略同数组里其它前缀的号（PB 与 BL 混放互不干扰）", () => {
    const arr = [{ a: "PB905", b: "BL999" }];
    expect(nextNo("PB", arr)).toBe("PB906");
    expect(nextNo("BL", arr)).toBe("BL1000");
  });
  it("keyField 限定只扫指定字段（防同记录里别的字段带同前缀号）", () => {
    const arr = [{ problemNo: "PB901", relatedNo: "PB998" }];
    expect(nextNo("PB", arr, 900, "problemNo")).toBe("PB902");
  });
  it("与 upsert 连用：连续新增不产生重复键", () => {
    const arr: { no: string; name: string }[] = [{ no: "X901", name: "a" }];
    upsert(arr, { name: "b" }, "no", () => nextNo("X", arr));
    upsert(arr, { name: "c" }, "no", () => nextNo("X", arr));
    const nos = arr.map((x) => x.no);
    expect(new Set(nos).size).toBe(nos.length);
    expect(nos).toContain("X902");
    expect(nos).toContain("X903");
  });
});
