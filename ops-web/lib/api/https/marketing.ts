// 覆盖范围：优惠券、活动、推送、裂变推荐、广告位 / 广告计划 / 投放、公告管理。
// 端点前缀：多数在 /api/user/**（营销与 C 端用户同库），广告位在 /api/ops/**，公告在 /api/ops/marketing/**（沿用现状）。
import { client } from "../http-client";
import type { MarketingApi } from "../contracts/marketing";
import type { PageQ } from "../query";

export const marketingHttp: MarketingApi = {
  listCoupons: (q?: PageQ) => client.get("/api/user/coupons", q),
  saveCoupon: (c) => client.post(c.couponNo ? `/api/user/coupons/${c.couponNo}` : "/api/user/coupons", c),

  // 营销扩展
  listCampaigns: (q?: PageQ) => client.get("/api/user/campaigns", q),
  listPushMessages: (q?: PageQ) => client.get("/api/user/push-messages", q),
  listReferrals: (q?: PageQ) => client.get("/api/user/referrals", q),
  listAdSlots: (q?: PageQ) => client.get("/api/ops/ad-slots", q),
  listAdCampaigns: (q?: PageQ) => client.get("/api/user/ad-campaigns", q),
  listAdDeliveries: (q?: PageQ) => client.get("/api/user/ad-deliveries", q),
  saveCampaign: (x) => client.post(x.campaignNo ? `/api/user/campaigns/${x.campaignNo}` : "/api/user/campaigns", x),
  savePushMessage: (x) => client.post(x.pushNo ? `/api/user/push-messages/${x.pushNo}` : "/api/user/push-messages", x),
  saveAdSlot: (x) => client.post(x.slotNo ? `/api/ops/ad-slots/${x.slotNo}` : "/api/ops/ad-slots", x),
  saveAdCampaign: (x) => client.post(x.adNo ? `/api/user/ad-campaigns/${x.adNo}` : "/api/user/ad-campaigns", x),

  // 公告管理
  listNotices: (q?: PageQ) => client.get("/api/ops/marketing/notices", q),
  saveNotice: (x) => client.post(x.noticeNo ? `/api/ops/marketing/notices/${x.noticeNo}` : "/api/ops/marketing/notices", x),
};
