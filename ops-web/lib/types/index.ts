// powerbank 运营端领域类型（镜像 docs/api + powerbank-common-api 契约）。
// 后端就绪后由 openapi 生成替换，避免漂移（tech-stack-frontend §六）。
//
// 本文件只做 re-export：公开导入路径始终是 `@/lib/types`，页面不必关心类型住在哪个域文件。
// 新增类型时：先按「这条数据属于哪个菜单模块」（对齐 lib/nav.ts）落到对应域文件，
// 只有所有域都用得上的基础类型才进 common.ts；本文件无需改动。

export * from "./common";
export * from "./dashboard";
export * from "./device";
export * from "./alarm";
export * from "./workorder";
export * from "./location";
export * from "./agent";
export * from "./order";
export * from "./pricing";
export * from "./finance";
export * from "./user";
export * from "./marketing";
export * from "./cs";
export * from "./report";
export * from "./org";
export * from "./system";
