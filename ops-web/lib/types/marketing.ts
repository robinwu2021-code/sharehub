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
  stock: number;
  issued: number;
  status: "ACTIVE" | "PAUSED";
}

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
export interface PushMessage {
  pushNo: string;
  title: string;
  channel: "APP_PUSH" | "SUBSCRIBE";
  audience: string;
  sentCount: number;
  status: "DRAFT" | "SENT";
  sentAt: string;
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
