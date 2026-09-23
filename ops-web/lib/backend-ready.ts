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
  | "overview" | "sites" | "fee-plans" | "fee-adjustments" | "site-sharing" | "payee-sharing";

/** 整页后端就绪度。依据：后端 Controller 实际存在的端点（2026-09-22 核对）。 */
export const BACKEND_READY: Record<OperationPage, boolean> = {
  // 2026-09-23：运营管理这一批后端全部补齐（OperationController + V40/V41），
  // 端到端用例见 backend `OperationMenuTest`。此前这里登记的「未实现」是如实的 ——
  // 菜单灰着，总好过点进去一片 404 而运营以为是自己权限不够。
  overview: true,
  sites: true,
  "fee-plans": true,
  "fee-adjustments": true,
  "site-sharing": true,
  "payee-sharing": true,
  // app-versions / banks / problems / notices 已于 2026-09-23 撤销（与系统设置、营销重复），
  // 页面与登记一并删除 —— 留着登记项会让人以为还有四个页面没做。
};

/** 页内局部功能的后端就绪度（整页已就绪、但个别按钮依赖新接口）。 */
export const PARTIAL_READY = {
  "sites.pause": true,       // POST /api/ops/sites/{no}/pause|resume（V40 加了 pause_reason 列）
  "sites.stats": true,       // GET /api/ops/sites/{no}/stats
  // 试算纯前端算（lib/rules/pricing-rules#simulate），不依赖后端，故恒可用
  /*
   * 启用/停用：**本来就不需要新端点** —— 页面做的是 `savePricePlan({...p, status})`，
   * 而保存接口一直都吃 status。这个标志此前写着 false，纯属登记时想当然，
   * 于是一个能用的按钮被藏了起来。
   */
  "fee-plans.status": true,
  // 原有 "fee-plans.sites" 已删：登记了却**没有任何页面用它**，留着只会让人以为还有一块没做
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
