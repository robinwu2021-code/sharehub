// Mock 实现（组合根）。按域切片在 lib/api/mocks/*.ts，全部走 lib/mock/db 内存数据 + 模拟延迟。
// 本文件只做组合：切片之间方法名不得重叠（后者会静默覆盖前者），由 contract.test.ts 断言。
import type { Api } from "./contract";
import { dashboardMock } from "./mocks/dashboard";
import { deviceMock } from "./mocks/device";
import { alarmMock } from "./mocks/alarm";
import { workOrderMock } from "./mocks/workorder";
import { locationMock } from "./mocks/location";
import { agentMock } from "./mocks/agent";
import { orderMock } from "./mocks/order";
import { pricingMock } from "./mocks/pricing";
import { financeMock } from "./mocks/finance";
import { userMock } from "./mocks/user";
import { marketingMock } from "./mocks/marketing";
import { csMock } from "./mocks/cs";
import { reportMock } from "./mocks/report";
import { orgMock } from "./mocks/org";
import { systemMock } from "./mocks/system";
import { operationMock } from "./mocks/operation";

/** 各域 mock 切片。contract.test.ts 用它逐片校验 key 集合、检测重复实现。 */
export const MOCK_SLICES = {
  dashboard: dashboardMock,
  device: deviceMock,
  alarm: alarmMock,
  workorder: workOrderMock,
  location: locationMock,
  agent: agentMock,
  order: orderMock,
  pricing: pricingMock,
  finance: financeMock,
  user: userMock,
  marketing: marketingMock,
  cs: csMock,
  report: reportMock,
  org: orgMock,
  system: systemMock,
  operation: operationMock,
} as const;

export const mockApi: Api = {
  ...dashboardMock, ...deviceMock, ...alarmMock, ...workOrderMock, ...locationMock,
  ...agentMock, ...orderMock, ...pricingMock, ...financeMock, ...userMock,
  ...marketingMock, ...csMock, ...reportMock, ...orgMock, ...systemMock, ...operationMock,
};
