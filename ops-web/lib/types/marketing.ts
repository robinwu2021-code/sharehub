// 覆盖范围：营销域（user/ad）——公告、优惠券、活动、Push 推送、裂变邀请、
// 广告位/广告计划/投放数据。

// —— 营销：优惠券（user 域）——
import type { Archivable } from "./common";

export interface Coupon extends Archivable {
  couponNo: string;
  name: string;
  type: "CUT" | "DISCOUNT";
  value: number;
  threshold: number;
  /** 发行总量（库存上限）。发放只增 `issued`，不改 `stock`——剩余 = stock - issued。 */
  stock: number;
  issued: number;
  /** 有效期止（ISO）。过期券不可再发放（S2）。 */
  expireAt: string;
  status: "ACTIVE" | "PAUSED"; // PAUSED = 已下线，不可发放
}

// —— 营销投放人群（S2）——
// 优惠券发放与推送触达共用同一套人群口径，维度全部落在**既有 mock 主数据**上：
//   ALL          → 全体 C 端用户（cUsers）
//   MEMBER_LEVEL → 会员等级（members.level：SILVER / GOLD / PLATINUM）
//   SEGMENT      → 消费者分层（consumerSegments.segmentNo，如 SEG901 高频通勤用户）
//   USER_LIST    → 指定用户号列表（cUsers.cUserNo，逗号分隔；不存在的号在 mock 层拒绝）
// 不新造「凭空的人群维度」——人群规模必须能从主数据算出来，否则发放张数/触达人数就是假的。
export type AudienceType = "ALL" | "MEMBER_LEVEL" | "SEGMENT" | "USER_LIST";
export interface AudienceSpec {
  targetType: AudienceType;
  /** MEMBER_LEVEL → 等级码；SEGMENT → segmentNo；USER_LIST → 逗号分隔用户号；ALL → 空 */
  targetValue?: string;
}
/** 人群解析结果：给页面显示口径、给 mock 算规模。 */
export interface AudienceResolved {
  targetType: AudienceType;
  targetDesc: string;
  size: number;
}

// —— 优惠券发放（S2：`marketing:coupon:issue` 已定义未用）——
/** 发放记录：谁在什么时候向哪个人群发了多少张。发放是不可撤销的权益动作，必须留痕。 */
export interface CouponIssueRecord {
  issueNo: string;
  couponNo: string;
  couponName: string;
  targetType: AudienceType;
  targetDesc: string; // 人群口径的可读描述（含规模），如「消费者分层：高频通勤用户（3820 人）」
  quantity: number; // 本次发放张数
  operatorName: string;
  createdAt: string;
}
export interface CouponIssuePayload extends AudienceSpec {
  quantity: number;
  operatorName?: string;
}
/** 发放结果：回带发放后的券（已发/剩余已变）与本次流水，页面无需再拉一次。 */
export interface CouponIssueResult {
  coupon: Coupon;
  record: CouponIssueRecord;
}

// 可发放判定放在 types 层：页面（要不要出「发放」按钮）与 mock（拒不拒绝）**共用同一个函数**，
// 否则必然出现「按钮亮着、点了报错」或反过来「明明能发但按钮不出」。
export const couponRemaining = (c: Coupon) => Math.max(0, c.stock - c.issued);
export const couponExpired = (c: Coupon) => !!c.expireAt && new Date(c.expireAt).getTime() < Date.now();
export const couponIssuable = (c: Coupon) =>
  c.status === "ACTIVE" && !c.archivedAt && !couponExpired(c) && couponRemaining(c) > 0;

// —— 营销 · 待建功能补全（user/ad 域）——
export interface Campaign {
  campaignNo: string;
  name: string;
  kind: string;
  rule: string;
  status: "DRAFT" | "RUNNING" | "ENDED";
  startAt: string;
  endAt: string;
}
// —— 推送触达（S2：`marketing:push:send` 已定义未用）——
// 渠道沿用 PushMessage 自己的枚举（**不是** NotifyLogChannel，见 types/system.ts 顶部注释：
// 这里是推送**类型**）。S2 补 SMS，凑齐「App 推送 / 站内订阅消息 / 短信」三条触达通路。
export type PushChannel = "APP_PUSH" | "SUBSCRIBE" | "SMS";
/** 推送状态机：草稿 → （定时）已排期 → 发送中 → 已发送。SENT 是终态，不可重发。 */
export type PushStatus = "DRAFT" | "SCHEDULED" | "SENDING" | "SENT";
export type PushAction = "schedule" | "send" | "finish";
export const PUSH_TRANSITIONS: Record<PushAction, { from: readonly PushStatus[]; to: PushStatus; label: string }> = {
  schedule: { from: ["DRAFT"], to: "SCHEDULED", label: "定时发送" },
  send: { from: ["DRAFT", "SCHEDULED"], to: "SENDING", label: "发送" },
  finish: { from: ["SENDING"], to: "SENT", label: "完成发送" },
};
export const canPushAction = (status: PushStatus, action: PushAction) =>
  PUSH_TRANSITIONS[action].from.includes(status);
/** 当前状态是否还能发（列表按钮据此渲染：已发送的不出「发送」按钮）。 */
export const canSendPush = (status: PushStatus) => canPushAction(status, "send");

export interface PushMessage {
  pushNo: string;
  title: string;
  content: string;
  channel: PushChannel;
  /** 人群可读口径（列表直接展示），与 audienceType/audienceValue 同源 */
  audience: string;
  audienceType: AudienceType;
  audienceValue: string;
  /** 定时发送时间；立即发送为 null */
  scheduledAt: string | null;
  /** 目标人数（发送时按人群规模落库） */
  targetCount: number;
  /** 成功触达人数（≤ targetCount） */
  successCount: number;
  /** 兼容既有列表列「触达数」，与 successCount 同值 */
  sentCount: number;
  status: PushStatus;
  sentAt: string;
  /**
   * 幂等键（同一键只发一次）。发送/重发都必须携带，mock 层拒绝重复提交——
   * 触达是**批量对外动作**，重复提交＝真的把消息发两遍，口径同退款（见 types/order.ts RefundRecord）。
   */
  idempotencyKey: string | null;
  operatorName: string | null;
}
/** 发送入参：幂等键必填；`scheduledAt` 有值 = 定时（转 SCHEDULED），空 = 立即（转 SENDING → SENT）。 */
export interface PushSendPayload {
  idempotencyKey: string;
  scheduledAt?: string | null;
  operatorName?: string;
}
export interface Referral {
  inviteNo: string;
  inviter: string;
  invitee: string;
  reward: number;
  status: "PENDING" | "REWARDED";
  createdAt: string;
  currency: string;
}
export interface AdSlot {
  slotNo: string;
  cabinetNo: string;
  position: "SCREEN" | "BODY";
  size: string;
  status: "IDLE" | "OCCUPIED";
  createdAt: string;
}
export interface AdCampaign {
  adNo: string;
  advertiser: string;
  creative: string;
  targeting: string;
  status: "DRAFT" | "RUNNING" | "ENDED";
  startAt: string;
  endAt: string;
}
export interface AdDelivery {
  deliveryNo: string;
  adNo: string;
  slotNo: string;
  impressions: number;
  plays: number;
  date: string;
}

// —— 公告管理（营销域 · P1，对标简电云 E1）——
// c-app 首页 Hub 的「公告条」需要运营端发布口（原功能清单遗漏）。
// 三语（zh/en/ar）+ 生效期 + 置顶：竞品公告只有单语，我们要覆盖 MENA 多语市场。
export interface Notice extends Archivable {
  noticeNo: string;
  title: string; // 中文标题
  titleEn: string;
  titleAr: string;
  content: string; // 中文正文
  contentEn: string;
  contentAr: string;
  type: "SYSTEM" | "PROMO" | "MAINTENANCE"; // 系统公告 / 活动公告 / 维护公告
  pinned: boolean; // 置顶（C 端公告条优先展示）
  startAt: string; // 生效期起
  endAt: string; // 生效期止
  status: "DRAFT" | "PUBLISHED" | "OFFLINE"; // 草稿 / 已发布 / 已下线
  publishedBy: string;
  createdAt: string;
}
