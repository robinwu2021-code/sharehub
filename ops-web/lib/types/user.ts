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
