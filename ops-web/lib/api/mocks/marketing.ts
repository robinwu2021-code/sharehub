// 覆盖范围：优惠券、活动、推送、裂变推荐、广告位 / 广告计划 / 投放、公告管理。
import * as db from "../../mock/db";
import type { MarketingApi } from "../contracts/marketing";
import type { PageQ } from "../query";
import { wait } from "./_wait";

export const marketingMock: MarketingApi = {
  listCoupons: (q: PageQ = {}) => wait(db.paginate(db.coupons, q.page, q.size, (c) => db.kwHit(q.keyword, c.name))),
  saveCoupon: (c) => wait(db.saveCoupon(c), 350),

  // 营销扩展
  listCampaigns: (q: PageQ = {}) => wait(db.listCampaigns(q)),
  listPushMessages: (q: PageQ = {}) => wait(db.listPushMessages(q)),
  listReferrals: (q: PageQ = {}) => wait(db.listReferrals(q)),
  listAdSlots: (q: PageQ = {}) => wait(db.listAdSlots(q)),
  listAdCampaigns: (q: PageQ = {}) => wait(db.listAdCampaigns(q)),
  listAdDeliveries: (q: PageQ = {}) => wait(db.listAdDeliveries(q)),
  saveCampaign: (x) => wait(db.saveCampaign(x), 350),
  savePushMessage: (x) => wait(db.savePushMessage(x), 350),
  saveAdSlot: (x) => wait(db.saveAdSlot(x), 350),
  saveAdCampaign: (x) => wait(db.saveAdCampaign(x), 350),

  // 公告管理
  listNotices: (q: PageQ = {}) => wait(db.listNotices(q)),
  saveNotice: (x) => wait(db.saveNotice(x), 350),
};
