// 运营管理域 mock 切片：站点概览 / 单站统计 / 营业状态。
import type { OperationApi } from "../contracts/operation";
import * as db from "../../mock/db";
import { wait } from "./_wait";

export const operationMock: OperationApi = {
  // 概览要遍历站点 × 机柜 × 订单，比普通列表慢一些，延迟给大一点更接近真实
  getOperationOverview: (q = {}) => wait(db.getOperationOverview(q), 350),
  getSiteStats: (siteNo, q = {}) => wait(db.getSiteStats(siteNo, q), 300),
  pauseSite: async (siteNo, reason) => wait(db.pauseSite(siteNo, reason), 400),
  resumeSite: async (siteNo) => wait(db.resumeSite(siteNo), 400),
};
