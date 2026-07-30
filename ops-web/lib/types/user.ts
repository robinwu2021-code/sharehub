// 覆盖范围：C 端用户域（user）——用户主体、风控、黑名单、会员、钱包、
// 免费用户白名单（含共用的免费来源枚举）、充值套餐。

import type { Archivable } from "./common";

export interface CUser {
  cUserNo: string;
  nickname: string;
  phone: string;
  creditScore: number;
  blacklisted: boolean;
  orders: number;
  registeredAt: string;
}

// —— 用户风控与黑名单（用户域 · P2）——
export interface UserRisk {
  riskNo: string;
  userNo: string;
  nickname: string;
  phone: string;
  creditScore: number;
  riskLevel: "HIGH" | "MEDIUM" | "LOW";
  reason: string;
  flaggedAt: string;
}

// —— 信用分调整（S2：权限码 user:risk:update 早已定义，风控页却没有调分动作）——
/**
 * 信用分上下限。**全站唯一定义**（页面输入校验、mock 校验、将来后端校验共用一份）。
 * 取 0–1000：与 mock 现有分值区间（550~849）同尺度，也给运营留出封顶/清零的余地。
 */
export const CREDIT_SCORE_MIN = 0;
export const CREDIT_SCORE_MAX = 1000;

/**
 * 风险等级阈值 —— 调分后**自动重算**风险等级（这就是「调分 → 风控等级」的联动实现）。
 * 阈值取自现有风控名单的分数分布（551/558 判高危、565/572 判中等），改分即改档。
 *   score < 560           HIGH   高危
 *   560 ≤ score < 640     MEDIUM 中等
 *   score ≥ 640           LOW    低风险
 */
export const RISK_HIGH_BELOW = 560;
export const RISK_MEDIUM_BELOW = 640;
export const riskLevelOf = (score: number): UserRisk["riskLevel"] =>
  score < RISK_HIGH_BELOW ? "HIGH" : score < RISK_MEDIUM_BELOW ? "MEDIUM" : "LOW";

/**
 * 观察名单门槛：调分后分数低于 `RISK_MEDIUM_BELOW` 且该用户尚不在风控名单里，
 * **自动补一条风控记录**（进观察名单）。
 *
 * 反向不做：分数回升到门槛以上时**不自动移出**名单，只把风险等级降为 LOW ——
 * 风控名单是审计痕迹，「曾被标记过」本身是信息，静默消失会让复核无从查起；
 * 移出由人工在黑名单/风控流程里显式操作。此口径写在这里，不留半吊子。
 */
export interface CreditScoreChange {
  changeNo: string;
  cUserNo: string;
  before: number;
  after: number;
  /** after - before，正数加分、负数减分。 */
  delta: number;
  reason: string;
  operatorName: string;
  createdAt: string;
}

/** 调分入参：`delta` 有正负（加分/减分），原因**必填**。 */
export interface CreditScoreAdjustPayload {
  delta: number;
  reason: string;
  operatorName?: string;
}

/** 调分返回：落库后的用户 + 联动后的风控记录（无联动时为 null）+ 刚写入的变更留痕。 */
export interface CreditScoreAdjustResult {
  user: CUser;
  risk: UserRisk | null;
  change: CreditScoreChange;
}

export interface UserBlacklist {
  blacklistNo: string;
  userNo: string;
  nickname: string;
  phone: string;
  reason: string;
  blacklistedAt: string;
  releasedAt: string | null;
  status: "ACTIVE" | "RELEASED";
}

// —— 用户 · 待建功能补全（user 域）——
export interface Member {
  userNo: string;
  nickname: string;
  level: "SILVER" | "GOLD" | "PLATINUM";
  points: number;
  cardType: string;
  expireAt: string;
}
export interface Wallet {
  userNo: string;
  nickname: string;
  balance: number;
  bonus: number;
  currency: string;
  updatedAt: string;
  // —— 用户价值画像（对标补齐：钱包页直接看消费/充值贡献）——
  orderCount: number; // 累计订单数
  orderAmount: number; // 累计订单金额
  rechargeCount: number; // 累计充值次数
  rechargeAmount: number; // 累计充值金额
}

// 免费来源：白名单用途枚举（免费订单与白名单共用，保证两页口径一致）
export type WhitelistReason = "INTERNAL_TEST" | "VIP" | "BD_DEMO" | "MERCHANT_SELF";

// —— 免费用户白名单（阶段 2）——
// 竞品「免费用户」放订单域；我们归**用户域**（它本质是用户属性），并强制标注用途。
export interface FreeUserWhitelist {
  userNo: string;
  nickname: string;
  phone: string;
  reason: WhitelistReason; // 必填用途（枚举，非自由文本）
  quotaType: "UNLIMITED" | "TIMES" | "AMOUNT";
  quotaValue: number; // 额度（次数 / 金额）
  usedValue: number; // 已用
  validFrom: string;
  validTo: string;
  grantedBy: string; // 授予人（审计用）
  status: "ACTIVE" | "EXPIRED" | "REVOKED";
}

// —— 充值套餐（阶段 3）——
// 竞品只有「充值 + 赠送」；我们加**有效期**与**适用市场**（MENA 多国家）。
export interface RechargePackage extends Archivable {
  packageNo: string;
  name: string;
  payAmount: number; // 充值金额
  giftAmount: number; // 赠送金额
  currency: string;
  markets: string; // 适用市场，ISO alpha-2 逗号分隔，如 "AE,SA"
  validDays: number; // 赠送金额有效期（天）
  sortNo: number;
  status: "ENABLED" | "DISABLED";
}
