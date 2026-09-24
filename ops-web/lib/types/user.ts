// 覆盖范围：C 端用户域（user）——用户主体、风控、黑名单、会员、钱包、
// 免费用户白名单（含共用的免费来源枚举）、充值套餐。

import type { Archivable } from "./common";
// 用户详情把订单一并摆出来，故引用订单域的行类型。仅类型引用（order.ts 也 import 本文件的
// WhitelistReason），编译期擦除、运行期无依赖，不构成循环。
import type { RentOrder } from "./order";

export interface CUser {
  cUserNo: string;
  nickname: string;
  phone: string;
  creditScore: number;
  blacklisted: boolean;
  /**
   * 反范式的累计订单数（后端 c_user 表同样有这一列）。
   * **必须等于订单表里该用户的实存单数** —— 用户列表写 21 单、详情抽屉只列出 3 单，
   * 抽屉就是在当场自证数据是假的。mock 侧由 lib/mock/db/user-profile.test.ts 钉住。
   */
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
  /** 谁拉黑、谁解除 —— 合规要能回答这两个问题。 */
  blacklistedBy: string | null;
  releasedBy: string | null;
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

// —— 会员权益（S4：会员/次卡此前是一张只读名单，「等级」列背后没有任何口径）——
/**
 * 一个等级的权益。等级本身是**固定三档**（SILVER/GOLD/PLATINUM，与 Member.level 同源），
 * 所以权益只有「改」没有「增删」—— 凭空多一档权益却没有会员能落到该档，是假功能。
 */
export interface MemberBenefit {
  level: Member["level"];
  /** 等级展示名（白银/黄金/铂金），与页面徽标共用一份文案。 */
  name: string;
  /** 租金折扣，`0.9` = 九折；`1` = 不打折。 */
  rentDiscount: number;
  /** 每单免费时长（分钟），0 = 无。 */
  freeMinutes: number;
  /** 是否免押金。 */
  depositFree: boolean;
  /** 每月赠券张数。 */
  monthlyCoupons: number;
  /** 消费 1 元累计的积分数。 */
  pointsRate: number;
  /** 升到本级所需累计积分（最低档为 0）。 */
  upgradePoints: number;
  status: "ENABLED" | "DISABLED";
  updatedBy: string;
  updatedAt: string;
}

/**
 * 等级由低到高的顺序 —— **全站唯一定义**（权益单调性校验、页面排序共用）。
 * 权益必须随等级单调变好：黄金比铂金还便宜，会员体系当场失去意义，故在写入时强制。
 */
export const MEMBER_LEVEL_ORDER: Member["level"][] = ["SILVER", "GOLD", "PLATINUM"];

// —— 次卡（S4：会员/次卡的「发放」）——
/** 时长卡（MONTH/QUARTER/YEAR，有效期内不限次）与次数卡（TIMES，按次核销）。 */
export type MemberCardType = "MONTH" | "QUARTER" | "YEAR" | "TIMES";
/**
 * 次卡类型文案 —— **唯一定义**。`Member.cardType` 存的就是这里的文案，
 * 两处各写一份的话，会员名单的「次卡」列会和次卡记录对不上。
 */
export const MEMBER_CARD_LABEL: Record<MemberCardType, string> = {
  MONTH: "月卡", QUARTER: "季卡", YEAR: "年卡", TIMES: "次卡",
};
/** 无卡时 `Member.cardType` 的取值（种子数据沿用「无」，此处收敛为常量）。 */
export const MEMBER_CARD_NONE = "无";

export interface MemberCard {
  cardNo: string;
  cardType: MemberCardType;
  userNo: string;
  nickname: string;
  /** 次数卡的总次数；时长卡为 0（表示不限次）。 */
  totalTimes: number;
  usedTimes: number;
  validFrom: string;
  validTo: string;
  status: "ACTIVE" | "EXPIRED" | "REVOKED";
  /** GRANT 运营发放（本功能）/ PURCHASE 用户自购（走充值支付，不在运营端产生）。 */
  source: "GRANT" | "PURCHASE";
  grantedBy: string;
  grantedAt: string;
  note: string;
}

/** 发放次卡入参：备注**必填**（免费权益要能事后归责，同免费白名单的口径）。 */
export interface MemberCardGrantPayload {
  userNo: string;
  cardType: MemberCardType;
  /** 仅次数卡需要；时长卡传 0 或不传。 */
  totalTimes?: number;
  validFrom: string;
  validTo: string;
  note: string;
  operatorName?: string;
}

/** 发放返回：新卡 + **同步后**的会员行（会员名单的次卡/到期两列由生效卡派生，见 db 层）。 */
export interface MemberCardGrantResult {
  card: MemberCard;
  member: Member;
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

// —— 钱包流水（后端 GET /api/user/wallets/{userNo}/txns 早已实现，运营端一直没有入口）——
/**
 * 一条钱包流水，字段逐一镜像后端 `WalletTxnRow`（user/asset/dto/UserAssetDtos.java），**不多不少**。
 *
 * 后端行里**没有「变动后余额」**，这里也就不补一个前端算的余额列：分页只能看到当页，
 * 跨页推算必然错，显示一个可能骗人的余额比不显示更糟（详见 app/users/page.tsx 流水抽屉）。
 *
 * `amount` **带符号**：`direction=IN` 为正、`OUT` 为负（与后端 usr_wallet_txn 同口径），
 * 所以「某用户全部流水求和」直接等于余额，不需要按方向分开累加。
 */
export interface WalletTxn {
  txnNo: string;
  /** RECHARGE 充值 / SPEND 消费 / REFUND 退款 / BONUS 赠额变动。 */
  type: "RECHARGE" | "SPEND" | "REFUND" | "BONUS";
  direction: "IN" | "OUT";
  /** 展示标题，运营视角的「事由」（如「套餐充值」「租借扣费」）。 */
  title: string;
  amount: number;
  currency: string;
  /** 关联业务类型与业务键（如 RENT_ORDER + 订单号）；无关联单据时为空串。 */
  bizType: string;
  bizNo: string;
  createdAt: string;
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
  /** 额度币种（`quotaType=AMOUNT` 时才有意义）；多市场下缺币种金额含义不明。 */
  currency: string | null;
  usedValue: number; // 已用
  validFrom: string;
  validTo: string;
  grantedBy: string; // 授予人（审计用）
  status: "ACTIVE" | "EXPIRED" | "REVOKED";
}

// —— 用户详情（S4：用户列表有拉黑，却没有一处能把一个人看全）——
/**
 * 一个用户的全貌：档案 + 风控 + 钱包 + 会员/次卡 + 订单。
 *
 * **由服务端一次组装**，前端不再自己按用户号去各列表接口捞一遍 —— 各列表的关键词是模糊匹配，
 * 前端拼装迟早捞出别人的单；而抽屉里的每一条都必须**就是**它所属 tab 里的那一条，
 * 否则抽屉一开就在跟它的来源列表互相打脸。一致性由 lib/mock/db/user-profile.test.ts 钉住。
 */
export interface UserProfile {
  user: CUser;
  /** 风控名单里的那一条；不在名单里为 null（不在名单 ≠ 无风险，页面按此区分文案）。 */
  risk: UserRisk | null;
  /** 该用户的全部拉黑记录（含已解除），最新在前 —— 「曾被拉黑过」本身是风控信息。 */
  blacklist: UserBlacklist[];
  /** 免费用户白名单（一人一条）；不在白名单为 null。 */
  whitelist: FreeUserWhitelist | null;
  /** 信用分调整留痕，最新在前。 */
  creditChanges: CreditScoreChange[];
  wallet: Wallet | null;
  /** 最近 `PROFILE_RECENT_TXNS` 条流水；全量看钱包流水抽屉（同一份数据，同一个接口）。 */
  walletTxns: WalletTxn[];
  member: Member | null;
  /** 该用户的次卡，最新发放在前。 */
  cards: MemberCard[];
  /** 该用户的**全部**订单，最新在前（与订单列表同序）。 */
  orders: RentOrder[];
  /** 订单聚合，全部由上面那批 orders 现算 —— 不另存一份计数，免得两个数打架。 */
  orderStats: {
    count: number;
    amount: number;
    currency: string;
    /** 未结束的单数（CREATED/DISPENSING/IN_USE），运营最先看这个。 */
    openCount: number;
  };
}

/** 详情抽屉里「最近流水」的条数。抽屉是概览，全量走钱包流水抽屉。 */
export const PROFILE_RECENT_TXNS = 5;

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
