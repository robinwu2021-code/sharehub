// 覆盖范围：优惠券、活动、推送、裂变推荐、广告位 / 广告计划 / 投放、公告管理。
import type { PageQ, ArchiveQ } from "../query";
import type {
  PageResult, Coupon, Campaign, PushMessage, Referral,
  AdSlot, AdCampaign, AdDelivery, Notice,
} from "../../types";

export interface MarketingApi {
  listCoupons(q?: ArchiveQ): Promise<PageResult<Coupon>>;
  saveCoupon(c: Partial<Coupon> & { couponNo?: string }): Promise<Coupon>;

  // === 营销扩展 tab ===
  listCampaigns(q?: PageQ): Promise<PageResult<Campaign>>;
  listPushMessages(q?: PageQ): Promise<PageResult<PushMessage>>;
  listReferrals(q?: PageQ): Promise<PageResult<Referral>>;
  listAdSlots(q?: PageQ): Promise<PageResult<AdSlot>>;
  listAdCampaigns(q?: PageQ): Promise<PageResult<AdCampaign>>;
  listAdDeliveries(q?: PageQ): Promise<PageResult<AdDelivery>>;
  saveCampaign(x: Partial<Campaign> & { campaignNo?: string }): Promise<Campaign>;
  savePushMessage(x: Partial<PushMessage> & { pushNo?: string }): Promise<PushMessage>;
  saveAdSlot(x: Partial<AdSlot> & { slotNo?: string }): Promise<AdSlot>;
  saveAdCampaign(x: Partial<AdCampaign> & { adNo?: string }): Promise<AdCampaign>;

  // === 公告管理（P1，补齐清单 E1）===
  listNotices(q?: ArchiveQ): Promise<PageResult<Notice>>;
  saveNotice(x: Partial<Notice> & { noticeNo?: string }): Promise<Notice>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  archiveCoupon(couponNo: string): Promise<Coupon>;
  unarchiveCoupon(couponNo: string): Promise<Coupon>;
  archiveNotice(noticeNo: string): Promise<Notice>;
  unarchiveNotice(noticeNo: string): Promise<Notice>;
}
