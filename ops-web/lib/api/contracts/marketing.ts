// 覆盖范围：优惠券、活动、推送、裂变推荐、广告位 / 广告计划 / 投放、公告管理。
import type { PageQ, ArchiveQ, CouponIssueQ } from "../query";
import type {
  PageResult, Coupon, Campaign, PushMessage, Referral,
  AdSlot, AdCampaign, AdDelivery, Notice,
  CouponIssueRecord, CouponIssuePayload, CouponIssueResult, PushSendPayload,
} from "../../types";

export interface MarketingApi {
  listCoupons(q?: ArchiveQ): Promise<PageResult<Coupon>>;
  saveCoupon(c: Partial<Coupon> & { couponNo?: string }): Promise<Coupon>;

  /**
   * S2 · 发放优惠券（`marketing:coupon:issue`）。批量权益动作：
   * 已下线/已过期/超出剩余库存一律由服务端拒绝，成功后 `issued` 增加并落发放流水。
   */
  issueCoupon(couponNo: string, x: CouponIssuePayload): Promise<CouponIssueResult>;
  listCouponIssueRecords(q?: CouponIssueQ): Promise<PageResult<CouponIssueRecord>>;

  // === 营销扩展 tab ===
  listCampaigns(q?: PageQ): Promise<PageResult<Campaign>>;
  listPushMessages(q?: PageQ): Promise<PageResult<PushMessage>>;
  listReferrals(q?: PageQ): Promise<PageResult<Referral>>;
  listAdSlots(q?: PageQ): Promise<PageResult<AdSlot>>;
  listAdCampaigns(q?: PageQ): Promise<PageResult<AdCampaign>>;
  listAdDeliveries(q?: PageQ): Promise<PageResult<AdDelivery>>;
  saveCampaign(x: Partial<Campaign> & { campaignNo?: string }): Promise<Campaign>;
  savePushMessage(x: Partial<PushMessage> & { pushNo?: string }): Promise<PushMessage>;
  /**
   * S2 · 发送推送（`marketing:push:send`）。状态机 DRAFT →（定时）SCHEDULED → SENDING → SENT；
   * **必须携带幂等键**，同键重复提交由服务端拒绝（重复提交＝消息真发两遍）。
   */
  sendPushMessage(pushNo: string, x: PushSendPayload): Promise<PushMessage>;
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
