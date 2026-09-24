// 覆盖范围：优惠券、活动、推送、裂变推荐、广告位 / 广告计划 / 投放、公告管理。
// 端点前缀：多数在 /api/user/**（营销与 C 端用户同库），广告位在 /api/ops/**，公告在 /api/ops/marketing/**（沿用现状）。
import { client } from "../http-client";
import type { MarketingApi } from "../contracts/marketing";
import type { PageQ, ArchiveQ, CouponIssueQ, CampaignQ , ReportQ } from "../query";

export const marketingHttp: MarketingApi = {
  listCoupons: (q?: ArchiveQ) => client.get("/api/user/coupons", q),
  saveCoupon: (c) => client.post(c.couponNo ? `/api/user/coupons/${c.couponNo}` : "/api/user/coupons", c),

  // S2 发放：REST 上是券的子动作（不是新建资源），故 POST 到 /{couponNo}/issue
  issueCoupon: (no, x) => client.post(`/api/user/coupons/${no}/issue`, x),
  listCouponIssueRecords: (q?: CouponIssueQ) => client.get("/api/user/coupon-issue-records", q),

  // 营销扩展
  // status 后端 GET /api/user/campaigns 已支持（与 kind 同批入参），无需后端改动
  listCampaigns: (q?: CampaignQ) => client.get("/api/user/campaigns", q),
  listPushMessages: (q?: PageQ) => client.get("/api/user/push-messages", q),
  listReferrals: (q?: PageQ) => client.get("/api/user/referrals", q),
  listAdSlots: (q?: PageQ) => client.get("/api/ops/ad-slots", q),
  listAdCampaigns: (q?: PageQ) => client.get("/api/user/ad-campaigns", q),
  listAdDeliveries: (q?: ReportQ) => client.get("/api/user/ad-deliveries", q),
  // ⚠️ 后端缺口：广告投放动作端点不存在（MarketingController 只有 ad-campaigns 的增查）。
  //    路径按现有 `/{no}/{action}` 约定先占位；后端补齐时状态机校验必须在服务端做。
  transitionAdCampaign: (adNo, action) => client.post(`/api/user/ad-campaigns/${adNo}/${action}`),
  // ⚠️ 后端缺口：邀请奖励规则无端点（MarketingController 只有 referrals 只读列表）。
  listReferralRules: (q?: PageQ) => client.get("/api/user/referral-rules", q),
  saveReferralRule: (x) => client.post(x.ruleNo ? `/api/user/referral-rules/${x.ruleNo}` : "/api/user/referral-rules", x),
  saveCampaign: (x) => client.post(x.campaignNo ? `/api/user/campaigns/${x.campaignNo}` : "/api/user/campaigns", x),
  // ⚠️ 后端缺口：活动只有 GET /campaigns 与 POST /campaigns[/{no}] 两个 upsert
  // （MarketingController:113-134，权限码 marketing:campaign:read / :update），**无启停动作端点**。
  // 不退化成「upsert 里传 status」：那等于把状态机搬到客户端，前端改个字段就能复活已结束的活动。
  // 待后端补 POST /api/user/campaigns/{no}/{action}（action ∈ start|pause|end，挂 marketing:campaign:update）。
  transitionCampaign: (no, action) => client.post(`/api/user/campaigns/${no}/${action}`, {}),
  savePushMessage: (x) => client.post(x.pushNo ? `/api/user/push-messages/${x.pushNo}` : "/api/user/push-messages", x),
  // 幂等键随 body 走（同 order 域退款），后端按 (pushNo, idempotencyKey) 去重
  sendPushMessage: (no, x) => client.post(`/api/user/push-messages/${no}/send`, x),
  finishPushMessage: (no, x) => client.post(`/api/user/push-messages/${no}/finish`, x),
  saveAdSlot: (x) => client.post(x.slotNo ? `/api/ops/ad-slots/${x.slotNo}` : "/api/ops/ad-slots", x),
  saveAdCampaign: (x) => client.post(x.adNo ? `/api/user/ad-campaigns/${x.adNo}` : "/api/user/ad-campaigns", x),

  // 公告管理
  listNotices: (q?: ArchiveQ) => client.get("/api/ops/marketing/notices", q),
  saveNotice: (x) => client.post(x.noticeNo ? `/api/ops/marketing/notices/${x.noticeNo}` : "/api/ops/marketing/notices", x),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  archiveCoupon: (no) => client.post(`/api/user/coupons/${no}/archive`, {}),
  unarchiveCoupon: (no) => client.post(`/api/user/coupons/${no}/unarchive`, {}),
  archiveNotice: (no) => client.post(`/api/ops/marketing/notices/${no}/archive`, {}),
  unarchiveNotice: (no) => client.post(`/api/ops/marketing/notices/${no}/unarchive`, {}),
};
