import { describe, it, expect } from "vitest";
import { BACKEND_READY, PARTIAL_READY, pageReady, featureReady, type OperationPage } from "./backend-ready";

describe("运营管理：后端就绪开关", () => {
  it("mock 模式下所有页面和页内功能都可用（开发与演示不受后端进度影响）", () => {
    for (const p of Object.keys(BACKEND_READY) as OperationPage[]) expect(pageReady(p, true)).toBe(true);
    for (const f of Object.keys(PARTIAL_READY) as (keyof typeof PARTIAL_READY)[]) expect(featureReady(f, true)).toBe(true);
  });

  it("真实后端模式按登记表：已有接口的页面可用，新接口页面不可用", () => {
    expect(pageReady("banks", false)).toBe(true);
    expect(pageReady("notices", false)).toBe(true);
    expect(pageReady("sites", false)).toBe(true);
    expect(pageReady("fee-adjustments", false)).toBe(false);
    expect(pageReady("overview", false)).toBe(false);
  });

  it("整页可用但页内新功能未就绪：站点的暂停和统计在真实后端下不可用", () => {
    expect(pageReady("sites", false)).toBe(true);
    expect(featureReady("sites.pause", false)).toBe(false);
    expect(featureReady("sites.stats", false)).toBe(false);
  });
});
