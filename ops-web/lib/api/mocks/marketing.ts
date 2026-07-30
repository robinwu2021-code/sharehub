// 覆盖范围：优惠券、活动、推送、裂变推荐、广告位 / 广告计划 / 投放、公告管理。
import * as db from "../../mock/db";
import type { MarketingApi } from "../contracts/marketing";
import type { PageQ, ArchiveQ, CouponIssueQ } from "../query";
import { wait } from "./_wait";

export const marketingMock: MarketingApi = {
  listCoupons: (q: ArchiveQ = {}) =>
    wait(db.paginate(db.coupons, q.page, q.size, (c) => db.liveHit(c, q.showArchived) && db.kwHit(q.keyword, c.couponNo, c.name))),
  saveCoupon: (c) => wait(db.saveCoupon(c), 350),

  // S2 发放：库存/状态/有效期校验全在 db 层，错误由全局 MutationCache 弹出
  issueCoupon: (no, x) => wait(db.issueCoupon(no, x), 350),
  listCouponIssueRecords: (q: CouponIssueQ = {}) => wait(db.listCouponIssueRecords(q)),

  // 营销扩展
  listCampaigns: (q: PageQ = {}) => wait(db.listCampaigns(q)),
  listPushMessages: (q: PageQ = {}) => wait(db.listPushMessages(q)),
  listReferrals: (q: PageQ = {}) => wait(db.listReferrals(q)),
  listAdSlots: (q: PageQ = {}) => wait(db.listAdSlots(q)),
  listAdCampaigns: (q: PageQ = {}) => wait(db.listAdCampaigns(q)),
  listAdDeliveries: (q: PageQ = {}) => wait(db.listAdDeliveries(q)),
  saveCampaign: (x) => wait(db.saveCampaign(x), 350),
  savePushMessage: (x) => wait(db.savePushMessage(x), 350),
  sendPushMessage: (no, x) => wait(db.sendPushMessage(no, x), 350),
  saveAdSlot: (x) => wait(db.saveAdSlot(x), 350),
  saveAdCampaign: (x) => wait(db.saveAdCampaign(x), 350),

  // 公告管理
  listNotices: (q: ArchiveQ = {}) => wait(db.listNotices(q)),
  saveNotice: (x) => wait(db.saveNotice(x), 350),

  // G1 软删除：归档 / 恢复（禁止物理删除）
  archiveCoupon: async (no) => wait(db.archiveCoupon(no), 350),
  unarchiveCoupon: async (no) => wait(db.unarchiveCoupon(no), 350),
  archiveNotice: async (no) => wait(db.archiveNotice(no), 350),
  unarchiveNotice: async (no) => wait(db.unarchiveNotice(no), 350),
};
