import { describe, it, expect } from "vitest";
import { BACKEND_READY, PARTIAL_READY, pageReady, featureReady, type OperationPage } from "./backend-ready";

describe("运营管理：后端就绪开关", () => {
  it("mock 模式下所有页面和页内功能都可用（开发与演示不受后端进度影响）", () => {
    for (const p of Object.keys(BACKEND_READY) as OperationPage[]) expect(pageReady(p, true)).toBe(true);
    for (const f of Object.keys(PARTIAL_READY) as (keyof typeof PARTIAL_READY)[]) expect(featureReady(f, true)).toBe(true);
  });

  /*
   * 2026-09-23：运营管理这一批后端补齐后，登记表整体翻成 true。
   * 这条用例保留的价值不再是「哪些页没就绪」，而是**这张表不许被顺手改**：
   * 把某一项改回 false 会让对应菜单灰掉，是一次产品可见的变化，必须在 review 里显形。
   */
  it("真实后端模式下运营管理十页全部可用（登记表整体翻绿的锚点）", () => {
    for (const p of Object.keys(BACKEND_READY) as OperationPage[]) {
      expect(pageReady(p, false), p).toBe(true);
    }
  });

  it("页内功能同样全部可用（站点暂停 / 单站统计 / 方案启停）", () => {
    for (const f of Object.keys(PARTIAL_READY) as (keyof typeof PARTIAL_READY)[]) {
      expect(featureReady(f, false), f).toBe(true);
    }
  });
});
