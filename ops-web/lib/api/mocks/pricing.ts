// 覆盖范围：计费定价 —— 价格方案、差异化定价规则、分时定价规则。
import * as db from "../../mock/db";
import type { PricingApi } from "../contracts/pricing";
import type { PageQ } from "../query";
import { wait } from "./_wait";

export const pricingMock: PricingApi = {
  listPricePlans: (q: PageQ = {}) => wait(db.paginate(db.pricePlans, q.page, q.size, (p) => db.kwHit(q.keyword, p.name, p.scope))),

  // 定价扩展
  listPricingDiffs: (q: PageQ = {}) => wait(db.listPricingDiffs(q)),
  listPricingSchedules: (q: PageQ = {}) => wait(db.listPricingSchedules(q)),
  savePricePlan: (x) => wait(db.savePricePlan(x), 350),
  savePricingDiff: (x) => wait(db.savePricingDiff(x), 350),
  savePricingSchedule: (x) => wait(db.savePricingSchedule(x), 350),
};
