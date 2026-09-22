// 单一 API 契约（组合根）。按域切片在 lib/api/contracts/*.ts，本文件只做组合与再导出。
// mock 与真实后端各实现一份（lib/api/mock.ts / http.ts），页面只依赖此接口，
// 切换靠 lib/api/index.ts 一处开关（消除散落的 if(USE_MOCK)）。
//
// 新增 API 该放哪：找到对应域的 contracts/<域>.ts 加方法签名，
// 再到 mocks/<域>.ts 与 https/<域>.ts 各补一处实现，最后把方法名加进 contract.test.ts 的锚数组。
// 本文件与 mock.ts / http.ts 三个组合根不需要改。
import type { DashboardApi } from "./contracts/dashboard";
import type { DeviceApi } from "./contracts/device";
import type { AlarmApi } from "./contracts/alarm";
import type { WorkOrderApi } from "./contracts/workorder";
import type { LocationApi } from "./contracts/location";
import type { AgentApi } from "./contracts/agent";
import type { OrderApi } from "./contracts/order";
import type { PricingApi } from "./contracts/pricing";
import type { FinanceApi } from "./contracts/finance";
import type { UserApi } from "./contracts/user";
import type { MarketingApi } from "./contracts/marketing";
import type { CsApi } from "./contracts/cs";
import type { ReportApi } from "./contracts/report";
import type { OrgApi } from "./contracts/org";
import type { SystemApi } from "./contracts/system";
import type { OperationApi } from "./contracts/operation";

// 查询参数集中在 query.ts；此处再导出，保持 `@/lib/api` 的对外导出面不变。
export * from "./query";
export type { LoginResp } from "./contracts/dashboard";
export type {
  DashboardApi, DeviceApi, AlarmApi, WorkOrderApi, LocationApi, AgentApi, OrderApi,
  PricingApi, FinanceApi, UserApi, MarketingApi, CsApi, ReportApi, OrgApi, SystemApi,
  OperationApi,
};
export type { OverviewQ, SiteStatsQ } from "./contracts/operation";

export interface Api extends
  DashboardApi, DeviceApi, AlarmApi, WorkOrderApi, LocationApi, AgentApi, OrderApi,
  PricingApi, FinanceApi, UserApi, MarketingApi, CsApi, ReportApi, OrgApi, SystemApi,
  OperationApi {}
