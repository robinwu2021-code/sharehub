// 分润规则 · 双向视图（S7）：竞品把「场地方分成」「代理商分成」做成两个菜单两套规则，
// 我们是同一份规则按分成主体分开看——切视角只换 dimension 参数。
//
// 这个筛选必须落在服务端（mock 层）：若在页面里筛当前页，翻到第 2 页就会出现
// 「明明有 12 条，按代理商看只剩 1 条」这种谁也解释不清的结果。本测试钉住服务端筛选与分页。
import { describe, expect, it } from "vitest";
import { financeMock } from "./mocks/finance";
import { shareRules } from "../mock/db/finance";

describe("分润规则双向视图", () => {
  it("按场地方看 / 按代理商看：各只返回该维度，两侧加总 = 全量", async () => {
    const venue = await financeMock.listShareRules({ page: 1, size: 100, dimension: "VENUE" });
    const agent = await financeMock.listShareRules({ page: 1, size: 100, dimension: "AGENT" });

    expect(venue.list.every((r) => r.dimension === "VENUE")).toBe(true);
    expect(agent.list.every((r) => r.dimension === "AGENT")).toBe(true);
    expect(venue.total + agent.total).toBe(shareRules.length);
    // 两个视角都要有数据，否则「切视角」在演示里等于切到一片空白
    expect(venue.total).toBeGreaterThan(0);
    expect(agent.total).toBeGreaterThan(0);
  });

  it("total 是筛过之后的总数——分页器不能按全量条数画", async () => {
    const first = await financeMock.listShareRules({ page: 1, size: 2, dimension: "AGENT" });
    const all = await financeMock.listShareRules({ page: 1, size: 100, dimension: "AGENT" });
    expect(first.total).toBe(all.total);
    expect(first.list.length).toBeLessThanOrEqual(2);
    expect(first.list.every((r) => r.dimension === "AGENT")).toBe(true);
  });

  it("不传 dimension 仍返回全量（深链/导出等旧调用不被这次改动破坏）", async () => {
    const r = await financeMock.listShareRules({ page: 1, size: 100 });
    expect(r.total).toBe(shareRules.length);
  });

  it("视角与关键词可叠加：搜索只在当前视角内命中", async () => {
    const agent = await financeMock.listShareRules({ page: 1, size: 100, dimension: "AGENT" });
    const name = agent.list[0].payeeName;
    const hit = await financeMock.listShareRules({ page: 1, size: 100, dimension: "AGENT", keyword: name });
    expect(hit.total).toBeGreaterThan(0);
    expect(hit.list.every((r) => r.dimension === "AGENT" && r.payeeName === name)).toBe(true);
  });
});
