/**
 * 这份产物连的是真后端还是 mock。**零依赖**，只读一个构建期常量。
 *
 * 单独一个文件而不是从 `lib/api` 里导出：根布局与 Providers 也要判这个，
 * 而在它们里面 import `lib/api` 会把整个 api 模块（mock 数据、契约、db）
 * 都拉进来 —— ai-shop 那边这样做过一次，构建挂在一个毫不相干的地方
 * （循环依赖导致某个导出还没初始化）。
 *
 * 判据与 `lib/api/index.ts` 必须一致：**`!== "0"` 就是 mock**。
 * 默认值是 mock，所以漏配 = 静默退回 mock —— 这正是这个标记要暴露的东西。
 */
export const IS_MOCK = process.env.NEXT_PUBLIC_USE_MOCK !== "0";
