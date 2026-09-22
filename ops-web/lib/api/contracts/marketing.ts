// 覆盖范围：优惠券、活动、推送、裂变推荐、广告位 / 广告计划 / 投放、公告管理。
import type { PageQ, ArchiveQ, CouponIssueQ, CampaignQ , ReportQ } from "../query";
import type {
  PageResult, Coupon, Campaign, CampaignAction, PushMessage, Referral,
  AdSlot, AdCampaign, AdDelivery, Notice,
  CouponIssueRecord, CouponIssuePayload, CouponIssueResult, PushSendPayload,

  AdCampaignAction,
  ReferralRule,} from "../../types";

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
  listCampaigns(q?: CampaignQ): Promise<PageResult<Campaign>>;
  listPushMessages(q?: PageQ): Promise<PageResult<PushMessage>>;
  listReferrals(q?: PageQ): Promise<PageResult<Referral>>;
  listAdSlots(q?: PageQ): Promise<PageResult<AdSlot>>;
  listAdCampaigns(q?: PageQ): Promise<PageResult<AdCampaign>>;
  /** 曝光事实按周期过滤（period 复用报表域 ReportQ）。 */
  listAdDeliveries(q?: ReportQ): Promise<PageResult<AdDelivery>>;
  /**
   * 广告上线 / 暂停 / 下线。**动作挂在广告活动上**，不挂「投放与曝光」——
   * 后者是按天回传的曝光事实行，没有生命周期。
   */
  transitionAdCampaign(adNo: string, action: AdCampaignAction): Promise<AdCampaign>;
  /** 邀请奖励规则：奖多少 / 奖给谁 / 什么条件触发。裂变页此前只有只读统计。 */
  listReferralRules(q?: PageQ): Promise<PageResult<ReferralRule>>;
  saveReferralRule(x: Partial<ReferralRule> & { ruleNo?: string }): Promise<ReferralRule>;
  /** `status` 由启停动作独占，服务端剥离本入参里的状态字段（表单能改状态＝闸门形同虚设）。 */
  saveCampaign(x: Partial<Campaign> & { campaignNo?: string }): Promise<Campaign>;
  /**
   * 活动启停（`marketing:campaign:update`）。合法迁移由 `CAMPAIGN_TRANSITIONS` 单点定义：
   * DRAFT/PAUSED →启动→ RUNNING →暂停→ PAUSED，RUNNING/PAUSED →结束→ ENDED（终态）。
   * 已结束不可复活、窗口已过不可启动、DRAFT 不可暂停，一律由服务端拒绝。
   */
  transitionCampaign(campaignNo: string, action: CampaignAction): Promise<Campaign>;
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
