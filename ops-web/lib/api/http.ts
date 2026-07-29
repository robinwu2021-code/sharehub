// 真实后端实现（组合根）。按域切片在 lib/api/https/*.ts，端点对齐 docs/api/README.md。
// 本文件只做组合：切片之间方法名不得重叠（后者会静默覆盖前者），由 contract.test.ts 断言。
import type { Api } from "./contract";
import { dashboardHttp } from "./https/dashboard";
import { deviceHttp } from "./https/device";
import { alarmHttp } from "./https/alarm";
import { workOrderHttp } from "./https/workorder";
import { locationHttp } from "./https/location";
import { agentHttp } from "./https/agent";
import { orderHttp } from "./https/order";
import { pricingHttp } from "./https/pricing";
import { financeHttp } from "./https/finance";
import { userHttp } from "./https/user";
import { marketingHttp } from "./https/marketing";
import { csHttp } from "./https/cs";
import { reportHttp } from "./https/report";
import { orgHttp } from "./https/org";
import { systemHttp } from "./https/system";

/** 各域 http 切片。contract.test.ts 用它逐片校验 key 集合、检测重复实现。 */
export const HTTP_SLICES = {
  dashboard: dashboardHttp,
  device: deviceHttp,
  alarm: alarmHttp,
  workorder: workOrderHttp,
  location: locationHttp,
  agent: agentHttp,
  order: orderHttp,
  pricing: pricingHttp,
  finance: financeHttp,
  user: userHttp,
  marketing: marketingHttp,
  cs: csHttp,
  report: reportHttp,
  org: orgHttp,
  system: systemHttp,
} as const;

export const httpApi: Api = {
  ...dashboardHttp, ...deviceHttp, ...alarmHttp, ...workOrderHttp, ...locationHttp,
  ...agentHttp, ...orderHttp, ...pricingHttp, ...financeHttp, ...userHttp,
  ...marketingHttp, ...csHttp, ...reportHttp, ...orgHttp, ...systemHttp,
};
