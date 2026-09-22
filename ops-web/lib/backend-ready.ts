// 运营管理新菜单：逐页登记「后端接口是否已就绪」（TDD-运营管理菜单-前端 §1.3）。
//
// 线上是 NEXT_PUBLIC_USE_MOCK=0（真实后端）。后端没实现的页面一调就报错，
// 所以真实后端模式下这些页面在菜单里灰显（soon）；mock 模式照常可点，方便开发与演示。
// 后端上线一项，把对应值改成 true 即可放开，不用改页面。
//
// 只登记「整页」级别。页面内局部功能（如站点的暂停/统计）由页面自己按 PARTIAL 判断。
//
// ⚠️ 本文件被 nav.ts（纯数据 + 纯函数）引用，不能 import lib/api（那会把整个 mock 库带进来）。

/** 与 lib/api/index.ts 的判定保持一致：只有显式设为 "0" 才走真实后端。 */
export const USE_MOCK = process.env.NEXT_PUBLIC_USE_MOCK !== "0";

export type OperationPage =
  | "overview" | "sites" | "fee-plans" | "fee-adjustments" | "site-sharing" | "payee-sharing"
  | "app-versions" | "banks" | "problems" | "notices";

/** 整页后端就绪度。依据：后端 Controller 实际存在的端点（2026-09-22 核对）。 */
export const BACKEND_READY: Record<OperationPage, boolean> = {
  overview: false,           // 需新增聚合接口 /api/ops/operation/overview
  sites: true,               // 列表/新增/编辑/归档已有；暂停、统计见 PARTIAL
  "fee-plans": true,         // 列表/编辑/归档已有；试算、启停、命中站点见 PARTIAL
  "fee-adjustments": false,  // 新表 + 定时任务，全部未实现
  "site-sharing": false,     // 契约取决于清单 D2
  "payee-sharing": false,    // 同上，且依赖真实分润明细
  "app-versions": true,
  banks: true,
  problems: true,
  notices: true,
};

/** 页内局部功能的后端就绪度（整页已就绪、但个别按钮依赖新接口）。 */
export const PARTIAL_READY = {
  "sites.pause": false,      // POST /api/ops/sites/{no}/pause|resume
  "sites.stats": false,      // GET /api/ops/sites/{no}/stats
  "fee-plans.simulate": false,
  "fee-plans.status": false,
  "fee-plans.sites": false,
} as const;

export type PartialFeature = keyof typeof PARTIAL_READY;

/** 整页是否可用：mock 模式恒可用；真实后端模式看登记表。 */
export function pageReady(page: OperationPage, useMock: boolean = USE_MOCK): boolean {
  return useMock || BACKEND_READY[page];
}

/** 页内功能是否可用（不可用时页面应不渲染对应按钮，而非点了再报错）。 */
export function featureReady(feature: PartialFeature, useMock: boolean = USE_MOCK): boolean {
  return useMock || PARTIAL_READY[feature];
}
