// 用户域（C 端）：用户档案 cUsers / 风险名单 userRisks / 黑名单 userBlacklist /
// 会员 members / 钱包 wallets（含流水 walletTxns）/ 免费用户白名单 freeWhitelist /
// 消费者分层 consumerSegments。
// 跨域自洽：wallets 的用户号与 cUsers 一一对应，orderCount 直接取 cUsers 的订单数。
import type {
  CUser, UserRisk, UserBlacklist, Member, Wallet, WalletTxn,
  FreeUserWhitelist, ConsumerSegment, PageQuery,
  CreditScoreChange, CreditScoreAdjustPayload, CreditScoreAdjustResult,
  MemberBenefit, MemberCard, MemberCardType, MemberCardGrantPayload, MemberCardGrantResult,
  UserProfile,
} from "../../types";
import {
  CREDIT_SCORE_MIN, CREDIT_SCORE_MAX, RISK_MEDIUM_BELOW, riskLevelOf,
  MEMBER_LEVEL_ORDER, MEMBER_CARD_LABEL, MEMBER_CARD_NONE, PROFILE_RECENT_TXNS,
} from "../../types";
import { OPERATORS, REASONS, p, iso } from "./internal";
import { notFound } from "@/lib/biz-error";
import { paginate, kwHit, upsert, nextNo } from "./helpers";

/**
 * 每位「有单用户」的订单数 —— 与 order.ts 的播种口径绑定：120 单轮询挂在 U3000~U3039 上，
 * 故前 40 位各 3 单、其余 0 单。
 *
 * 为什么写死而不是求实数：order.ts 已经 import 本文件（取 cUsers/freeWhitelist），本文件再反向
 * import 就成环；而 wallets 又必须在本文件里按这个计数铺流水，没法推迟到运行时算。
 * 漂移风险由 user-profile.test.ts 的「反范式计数 === 订单表实存条数」兜住 —— 这就是把
 * 「两个数各自编」换成「一个数被钉住」。
 */
const ORDERS_PER_SEEDED_USER = 3;
const SEEDED_ORDER_USERS = 40;

export const cUsers: CUser[] = Array.from({ length: 60 }, (_, i) => ({
  cUserNo: `U${3000 + i}`, nickname: p(["Ahmed", "Mohammed", "Fatima", "Layla", "Yusuf", "李明"], i),
  phone: `+9715${String(5000000 + i * 173).slice(0, 7)}`, creditScore: 550 + (i * 7) % 300,
  blacklisted: i % 17 === 0,
  orders: i < SEEDED_ORDER_USERS ? ORDERS_PER_SEEDED_USER : 0,
  registeredAt: iso(i * 86400_000),
}));

// 风险名单 / 黑名单都必须挂在**真实存在的 C 端用户**上（台账 M7：原先用 `U-00xx` 带横杠的
// 第三套号，cUsers 里根本没有，点进去查无此人）。昵称/手机号/信用分一律由 cUsers 反查，不再手写。
const userOf = (no: string): CUser => {
  const u = cUsers.find((x) => x.cUserNo === no);
  // 这条**不翻译**：它不是业务拒绝，是 mock 种子数据自相矛盾的 bug 信号，只给开发看
  if (!u) throw new Error(`mock 数据不自洽：用户 ${no} 不存在于 cUsers`);
  return u;
};

// 挑的都是 cUsers 里信用分最低的几位（551/558/565/572），风险等级与分数同向。
export const userRisks: UserRisk[] = [
  { riskNo: "RK0001", userNo: "U3043", riskLevel: "HIGH" as const, reason: "多次逾期未还", flaggedAt: "2026-07-10T09:00:00Z" },
  { riskNo: "RK0002", userNo: "U3044", riskLevel: "HIGH" as const, reason: "疑似欺诈", flaggedAt: "2026-07-08T14:00:00Z" },
  { riskNo: "RK0003", userNo: "U3045", riskLevel: "MEDIUM" as const, reason: "异常订单", flaggedAt: "2026-07-05T10:00:00Z" },
  { riskNo: "RK0004", userNo: "U3046", riskLevel: "MEDIUM" as const, reason: "信用不足", flaggedAt: "2026-07-03T08:00:00Z" },
].map((r) => {
  const u = userOf(r.userNo);
  return { ...r, nickname: u.nickname, phone: u.phone, creditScore: u.creditScore };
});

// ACTIVE 的四条恰好是 cUsers 里 `blacklisted: true` 的四位（U3000/U3017/U3034/U3051），
// RELEASED 的一条挂在已解封（blacklisted: false）的用户上——两页状态互相印证。
export const userBlacklist: UserBlacklist[] = [
  { blacklistNo: "BL0001", userNo: "U3000", reason: "恶意刷单", blacklistedAt: "2026-07-01T12:00:00Z", releasedAt: null, status: "ACTIVE" as const },
  { blacklistNo: "BL0002", userNo: "U3017", reason: "骚扰客服", blacklistedAt: "2026-06-20T10:00:00Z", releasedAt: null, status: "ACTIVE" as const },
  { blacklistNo: "BL0003", userNo: "U3034", reason: "超时未还且拒不沟通", blacklistedAt: "2026-06-02T08:00:00Z", releasedAt: null, status: "ACTIVE" as const },
  { blacklistNo: "BL0004", userNo: "U3051", reason: "多设备批量薅免费额度", blacklistedAt: "2026-05-28T16:00:00Z", releasedAt: null, status: "ACTIVE" as const },
  { blacklistNo: "BL0005", userNo: "U3009", reason: "历史黑名单（申诉成立已解除）", blacklistedAt: "2026-05-15T09:00:00Z", releasedAt: "2026-07-01T00:00:00Z", status: "RELEASED" as const },
].map((b) => {
  const u = userOf(b.userNo);
  return { ...b, nickname: u.nickname, phone: u.phone };
});

// 以下三张表（members/wallets/freeWhitelist）的昵称与手机一律由 cUsers 反查。
// 原先各用 NICKS / phone() 另编一套，于是 U3006 在「用户」页叫 Ahmed、在「钱包·会员」页叫 Noura；
// 详情抽屉把这几张表并到一屏，这种分歧当场就露出来。做法同 order.ts 里投诉反查昵称。

// 四人里一人无卡；持卡的那三种由**枚举**挑，文案统一取 MEMBER_CARD_LABEL ——
// 会员行的「次卡」列与次卡记录必须是同一套词，否则名单和记录看着像两码事。
// 无卡就没有到期时间（原先「无」卡也带一个到期日，本身就是自相矛盾）。
const seedCardType = (i: number): MemberCardType | null =>
  p([null, "MONTH", "QUARTER", "YEAR"] as const, i);

export const members: Member[] = Array.from({ length: 24 }, (_, i) => {
  const ct = seedCardType(i);
  return {
    userNo: `U${3000 + i}`, nickname: userOf(`U${3000 + i}`).nickname,
    level: p(["SILVER", "GOLD", "PLATINUM"] as const, i),
    points: (i * 137) % 5000,
    cardType: ct ? MEMBER_CARD_LABEL[ct] : MEMBER_CARD_NONE,
    expireAt: ct ? iso(-(30 + i * 15) * 86400_000) : "",
  };
});
// 钱包用户号与 cUsers 一一对应（U3000+）；orderCount 直接取该用户在 cUsers 里的订单数，保证两页数据自洽
const RECHARGE_DENOM = [20, 50, 100];
export const wallets: Wallet[] = Array.from({ length: 24 }, (_, i) => {
  const userNo = `U${3000 + i}`;
  const orderCount = cUsers.find((u) => u.cUserNo === userNo)?.orders ?? (i * 3) % 40;
  const rechargeCount = Math.floor(orderCount / 4) + 1; // 约每 4 单充值一次
  const denom = p(RECHARGE_DENOM, i);
  return {
    userNo, nickname: userOf(userNo).nickname, balance: Number(((i * 7) % 200 + (i % 10) / 10).toFixed(2)),
    bonus: Number(((i * 3) % 50).toFixed(2)), currency: "AED", updatedAt: iso(i * 43200_000),
    orderCount,
    // 客单价 4.5~7.5 AED（与租借计费口径一致）
    orderAmount: Number((orderCount * (4.5 + (i % 7) * 0.5)).toFixed(2)),
    rechargeCount,
    rechargeAmount: Number((rechargeCount * denom).toFixed(2)),
  };
});

// —— §7 免费用户白名单：用途强制枚举，额度可限次/限额/不限 ——
export const freeWhitelist: FreeUserWhitelist[] = Array.from({ length: 14 }, (_, i) => {
  const quotaType = p(["TIMES", "AMOUNT", "UNLIMITED"] as const, i);
  const status: FreeUserWhitelist["status"] = i % 7 === 3 ? "EXPIRED" : i % 11 === 6 ? "REVOKED" : "ACTIVE";
  const quotaValue = quotaType === "UNLIMITED" ? 0 : quotaType === "TIMES" ? 10 + (i % 4) * 10 : 100 + (i % 5) * 50;
  const u = userOf(`U${3000 + i}`);
  return {
    userNo: u.cUserNo,
    nickname: u.nickname,
    phone: u.phone,
    reason: p(REASONS, i),
    quotaType,
    quotaValue,
    usedValue: quotaType === "UNLIMITED" ? 0 : Math.round(quotaValue * ((i % 5) / 5)),
    validFrom: iso((30 + i) * 86400_000).slice(0, 10),
    validTo: iso((status === "EXPIRED" ? 3 : -(60 + i * 5)) * 86400_000).slice(0, 10),
    grantedBy: p(OPERATORS, i),
    status,
  };
});

export const consumerSegments: ConsumerSegment[] = [
  { segmentNo: "SEG901", segment: "高频通勤用户", userCount: 3820, repeatRate: 0.62, avgOrderValue: 6.4, currency: "AED" },
  { segmentNo: "SEG902", segment: "机场/差旅人群", userCount: 2140, repeatRate: 0.31, avgOrderValue: 12.8, currency: "AED" },
  { segmentNo: "SEG903", segment: "商场休闲用户", userCount: 5670, repeatRate: 0.44, avgOrderValue: 5.1, currency: "AED" },
  { segmentNo: "SEG904", segment: "医院/景区场景", userCount: 1290, repeatRate: 0.27, avgOrderValue: 9.3, currency: "AED" },
  { segmentNo: "SEG905", segment: "新客(30日内)", userCount: 4410, repeatRate: 0.18, avgOrderValue: 4.7, currency: "AED" },
  { segmentNo: "SEG906", segment: "会员/次卡用户", userCount: 980, repeatRate: 0.71, avgOrderValue: 7.9, currency: "AED" },
];

export const listMembers = (q: PageQuery = {}) => paginate(members, q.page, q.size, (x) => kwHit(q.keyword, x.userNo, x.nickname));
export const listWallets = (q: PageQuery = {}) => paginate(wallets, q.page, q.size, (x) => kwHit(q.keyword, x.userNo, x.nickname));
export const listUserRisks = (q: PageQuery = {}) => paginate(userRisks, q.page, q.size, (x) => kwHit(q.keyword, x.riskNo, x.userNo, x.nickname, x.phone));
export const listUserBlacklist = (q: PageQuery = {}) => paginate(userBlacklist, q.page, q.size, (x) => kwHit(q.keyword, x.blacklistNo, x.userNo, x.nickname));
export const listConsumerSegments = (q: PageQuery = {}) => paginate(consumerSegments, q.page, q.size, (x) => kwHit(q.keyword, x.segmentNo, x.segment));

// ————————————————————————————————————————————————————————————————
// 信用分调整（S2：F2 → F3，权限码 user:risk:update 早已定义、风控页从没用过）
//
// 口径三件事：
//   ① 分数的**唯一真相**是 cUsers[].creditScore；userRisks[].creditScore 是它的投影，调分后同步。
//   ② 上下限在 mock 层强制（越界抛错，不做静默截断——静默截断会让运营以为改成功了）。
//   ③ 每次调分落一条 CreditScoreChange 留痕，并按阈值重算风险等级；
//      分数掉到 640 以下且不在名单里的，自动补一条风控记录（进观察名单）。
//      分数回升**不自动移出**名单（见 lib/types/user.ts 的口径说明）。
// ————————————————————————————————————————————————————————————————
const nowIso = () => new Date().toISOString();
/** 号码生成沿用本域既有格式（RK0001 / CS0001 四位补零），`nextNo` 不补零故不复用。 */
const padNo = (prefix: string, rows: readonly unknown[], key: string) => {
  const re = new RegExp(`^${prefix}(\\d+)$`);
  const max = rows.reduce<number>((m, r) => {
    const v = (r as Record<string, unknown>)[key];
    const hit = typeof v === "string" ? re.exec(v) : null;
    return hit ? Math.max(m, Number(hit[1])) : m;
  }, 0);
  return `${prefix}${String(max + 1).padStart(4, "0")}`;
};

/** 信用分变更留痕（审计）。最新在前。 */
export const creditScoreChanges: CreditScoreChange[] = [];

export class CreditScoreError extends Error {
  constructor(readonly cUserNo: string, msg: string) {
    super(msg);
    this.name = "CreditScoreError";
  }
}

/**
 * 调整信用分：查人 → 校验必填与上下限 → 改分 → 联动风控等级/观察名单 → 落留痕。
 * `delta` 有正负；越界（<0 或 >1000）一律拒绝，用户分数保持不变。
 */
export function adjustCreditScore(cUserNo: string, payload: CreditScoreAdjustPayload): CreditScoreAdjustResult {
  const u = cUsers.find((x) => x.cUserNo === cUserNo);
  if (!u) throw new CreditScoreError(cUserNo, `用户 ${cUserNo} 不存在`);
  const reason = payload?.reason?.trim();
  if (!reason) throw new CreditScoreError(cUserNo, "调整信用分必须填写原因");
  const delta = Number(payload?.delta);
  if (!Number.isFinite(delta) || delta === 0) throw new CreditScoreError(cUserNo, "调整分值必须是非 0 的数字");
  if (!Number.isInteger(delta)) throw new CreditScoreError(cUserNo, "调整分值必须是整数");

  const before = u.creditScore;
  const after = before + delta;
  if (after < CREDIT_SCORE_MIN || after > CREDIT_SCORE_MAX) {
    throw new CreditScoreError(
      cUserNo,
      `调整后信用分 ${after} 超出允许范围 ${CREDIT_SCORE_MIN}~${CREDIT_SCORE_MAX}（当前 ${before}）`,
    );
  }
  u.creditScore = after;

  // —— 联动：风控名单里的同步分数与等级；掉到门槛以下的自动进观察名单 ——
  let risk = userRisks.find((r) => r.userNo === cUserNo) ?? null;
  if (risk) {
    risk.creditScore = after;
    risk.riskLevel = riskLevelOf(after);
  } else if (after < RISK_MEDIUM_BELOW) {
    risk = {
      riskNo: padNo("RK", userRisks, "riskNo"),
      userNo: cUserNo,
      nickname: u.nickname,
      phone: u.phone,
      creditScore: after,
      riskLevel: riskLevelOf(after),
      reason: `信用分调整至 ${after}（低于 ${RISK_MEDIUM_BELOW}），自动进入风控观察名单`,
      flaggedAt: nowIso(),
    };
    userRisks.push(risk);
  }

  const change: CreditScoreChange = {
    changeNo: padNo("CS", creditScoreChanges, "changeNo"),
    cUserNo, before, after, delta,
    reason,
    operatorName: payload.operatorName?.trim() || "admin",
    createdAt: nowIso(),
  };
  creditScoreChanges.unshift(change);
  return { user: { ...u }, risk: risk ? { ...risk } : null, change };
}

/** 调分历史：`cUserNo` 精确过滤（用户/风控抽屉的时间线），keyword 覆盖单号/用户/人/原因。 */
export const listCreditScoreChanges = (q: PageQuery & { cUserNo?: string } = {}) =>
  paginate(creditScoreChanges, q.page, q.size, (x) => {
    if (q.cUserNo && x.cUserNo !== q.cUserNo) return false;
    return kwHit(q.keyword, x.changeNo, x.cUserNo, x.operatorName, x.reason);
  });

// —— 定点演示数据：**走真实调分入口生成**，保证「留痕 ↔ 用户分数 ↔ 风控等级」天然一致 ——
adjustCreditScore("U3043", { delta: -20, reason: "连续两单逾期未还，按风控规则扣分", operatorName: "Sara Ahmed" });
adjustCreditScore("U3045", { delta: 30, reason: "申诉成立，恢复此前误扣分值", operatorName: "admin" });

// ————————————————————————————————————————————————————————————————
// 钱包流水（后端 GET /api/user/wallets/{userNo}/txns 早已实现，运营端此前没有入口）
//
// 自洽口径 —— 抽屉是从余额列表点开的，两边对不上就等于当场自证数据是假的：
//   ① 本金流水（RECHARGE/SPEND/REFUND）带符号求和 === wallets[].balance
//   ② 赠额流水（BONUS）求和 === wallets[].bonus
//
// 难点在于现有钱包夹具**本来就不自洽**：balance 与「充值金额 − 订单金额」各按自己的公式生成，
// 两者毫无关系。所以这里先按 rechargeCount/rechargeAmount、orderCount/orderAmount 逐笔铺开
// （让流水条数与金额跟列表上的用户价值画像四列对得上），再用一条**最老的「期初结转」**把差额
// 一次性补平 —— 真实系统的流水表也只留窗口内明细，窗口之前的沉淀就是一笔期初，不是造假。
// 期初那条刻意用 REFUND/SPEND 而不是 RECHARGE，免得「充值次数」列被它多算一次。
// ————————————————————————————————————————————————————————————————
const round2 = (n: number) => Number(n.toFixed(2));
let txnSeq = 0;
const nextTxnNo = () => `WT${String(++txnSeq).padStart(6, "0")}`;

function buildWalletTxns(w: Wallet, i: number): WalletTxn[] {
  const rows: WalletTxn[] = [];
  // 时间轴：同一用户每笔往前推 12 小时。数组顺序（最新在前）必须与 createdAt 同向，
  // 否则后端换成真库按主键倒序返回时，翻页看到的顺序会跟 mock 不一样。
  let step = 0;
  const at = () => iso((i * 24 + ++step * 12) * 3600_000);
  const row = (
    type: WalletTxn["type"], title: string, amount: number, bizType: string, bizNo: string,
  ): WalletTxn => ({
    txnNo: nextTxnNo(), type, direction: amount >= 0 ? "IN" : "OUT", title,
    amount: round2(amount), currency: w.currency, bizType, bizNo, createdAt: at(),
  });

  // 赠额单独一条：赠金不参与本金求和（余额与赠额在列表上就是两列）
  if (w.bonus > 0) rows.push(row("BONUS", "充值赠送到账", w.bonus, "RECHARGE_GIFT", ""));

  const denom = w.rechargeCount > 0 ? round2(w.rechargeAmount / w.rechargeCount) : 0;
  const unit = w.orderCount > 0 ? round2(w.orderAmount / w.orderCount) : 0;
  for (let k = 0; k < Math.max(w.rechargeCount, w.orderCount); k++) {
    if (k < w.orderCount) rows.push(row("SPEND", "租借扣费", -unit, "RENT_ORDER", `RO${9000 + i * 40 + k}`));
    if (k < w.rechargeCount) rows.push(row("RECHARGE", "套餐充值", denom, "RECHARGE", `RC${7000 + i * 10 + k}`));
  }

  const carry = round2(w.balance - sumPrincipal(rows));
  if (carry > 0) rows.push(row("REFUND", "期初余额结转", carry, "CARRY_FORWARD", ""));
  else if (carry < 0) rows.push(row("SPEND", "期初欠额结转", carry, "CARRY_FORWARD", ""));
  return rows;
}

/** 本金合计：赠额（BONUS）不计入 —— 它对应的是钱包的「赠额」列，不是「余额」列。 */
export const sumPrincipal = (rows: readonly WalletTxn[]) =>
  round2(rows.filter((r) => r.type !== "BONUS").reduce((s, r) => s + r.amount, 0));
/** 赠额合计。 */
export const sumBonus = (rows: readonly WalletTxn[]) =>
  round2(rows.filter((r) => r.type === "BONUS").reduce((s, r) => s + r.amount, 0));

/** 按用户号分桶：后端接口本身就是「某用户的流水」，行里不带用户号，故用 Record 而非扁平数组。 */
export const walletTxns: Record<string, WalletTxn[]> = Object.fromEntries(
  wallets.map((w, i) => [w.userNo, buildWalletTxns(w, i)]),
);

/** 某用户的钱包流水，最新在前；`type` 精确筛流水类型。 */
export const listWalletTxns = (userNo: string, q: PageQuery & { type?: string } = {}) =>
  paginate(walletTxns[userNo] ?? [], q.page, q.size, (x) => !q.type || x.type === q.type);

/**
 * 会员增改。**`cardType` / `expireAt` 一律剥掉**：这两列自「次卡发放」派生（见 syncCardColumns），
 * 表单里手改就能造出「名单说有月卡、次卡记录里一张都没有」的假象。
 */
export const saveMember = (x: Partial<Member>) => {
  const { cardType: _c, expireAt: _e, ...rest } = x;
  const row = upsert(members, rest, "userNo", () => nextNo("U", members));
  syncCardColumns(row.userNo);
  return members.find((m) => m.userNo === row.userNo)!;
};
/**
 * 手工调整钱包：改完**必须补一条流水**，否则「流水合计 === 余额」当场被破坏，
 * 抽屉会跟它打开来源的那张列表自相矛盾（这正是加流水入口后最容易漏的一处）。
 */
export const saveWallet = (x: Partial<Wallet>) => {
  const before = x.userNo ? wallets.find((w) => w.userNo === x.userNo) : undefined;
  const beforeBalance = before?.balance ?? 0;
  const beforeBonus = before?.bonus ?? 0;
  const row = upsert(wallets, x, "userNo", () => nextNo("U", wallets));
  const rows = (walletTxns[row.userNo] ??= []);
  const push = (type: WalletTxn["type"], title: string, amount: number) => {
    rows.unshift({
      txnNo: nextTxnNo(), type, direction: amount >= 0 ? "IN" : "OUT", title,
      amount: round2(amount), currency: row.currency, bizType: "MANUAL_ADJUST", bizNo: "",
      createdAt: nowIso(),
    });
  };
  const dBalance = round2(row.balance - beforeBalance);
  const dBonus = round2(row.bonus - beforeBonus);
  // 加钱记 REFUND（入账）而不是 RECHARGE：运营补款不是用户充值，不该混进「充值次数」
  if (dBalance !== 0) push(dBalance > 0 ? "REFUND" : "SPEND", "运营手工调整余额", dBalance);
  if (dBonus !== 0) push("BONUS", "运营手工调整赠额", dBonus);
  return row;
};

export const listFreeWhitelist = (q: PageQuery & { status?: string; reason?: string } = {}) =>
  paginate(freeWhitelist, q.page, q.size, (x) => {
    if (!kwHit(q.keyword, x.userNo, x.nickname, x.phone, x.grantedBy)) return false;
    if (q.status && x.status !== q.status) return false;
    if (q.reason && x.reason !== q.reason) return false;
    return true;
  });
export const saveFreeWhitelist = (x: Partial<FreeUserWhitelist>) =>
  upsert(freeWhitelist, x, "userNo", () => nextNo("U", freeWhitelist, 3900));
/** 撤销白名单：置 REVOKED（软撤销，保留审计痕迹，对齐决策 §八-4）。 */
export const revokeFreeWhitelist = (userNo: string): FreeUserWhitelist => {
  const i = freeWhitelist.findIndex((w) => w.userNo === userNo);
  if (i < 0) throw notFound("白名单", "Whitelist entry", userNo);
  freeWhitelist[i] = { ...freeWhitelist[i], status: "REVOKED" };
  return freeWhitelist[i];
};

// ————————————————————————————————————————————————————————————————
// 会员权益（S4：会员/次卡此前只有一张只读名单，「等级」列背后没有任何口径）
//
// 只有「改」没有「增删」：等级是固定三档（与 Member.level 同源），凭空多一档权益却没有
// 会员能落进去，就是假功能。写入时强制**权益随等级单调变好** —— 黄金比铂金还便宜的话，
// 会员体系当场失去意义，这种错必须在落库前拦住而不是靠人看。
// ————————————————————————————————————————————————————————————————
export const memberBenefits: MemberBenefit[] = [
  { level: "SILVER", name: "白银", rentDiscount: 1, freeMinutes: 0, depositFree: false, monthlyCoupons: 0, pointsRate: 1, upgradePoints: 0, status: "ENABLED", updatedBy: "admin", updatedAt: iso(30 * 86400_000) },
  { level: "GOLD", name: "黄金", rentDiscount: 0.9, freeMinutes: 10, depositFree: false, monthlyCoupons: 2, pointsRate: 1.5, upgradePoints: 1000, status: "ENABLED", updatedBy: "admin", updatedAt: iso(30 * 86400_000) },
  { level: "PLATINUM", name: "铂金", rentDiscount: 0.8, freeMinutes: 30, depositFree: true, monthlyCoupons: 5, pointsRate: 2, upgradePoints: 5000, status: "ENABLED", updatedBy: "admin", updatedAt: iso(30 * 86400_000) },
];

export class MemberBenefitError extends Error {
  constructor(readonly level: string, msg: string) {
    super(msg);
    this.name = "MemberBenefitError";
  }
}

/** 按等级由低到高排出权益表（页面顺序 = 校验顺序，两处不能各排一套）。 */
const benefitsByLevel = () =>
  MEMBER_LEVEL_ORDER.map((lv) => memberBenefits.find((b) => b.level === lv)!).filter(Boolean);

/** 单调性校验：只看相邻两档，报错必须指名道姓说清哪一项反了。 */
function assertMonotonic(level: string) {
  const rows = benefitsByLevel();
  for (let i = 1; i < rows.length; i++) {
    const lo = rows[i - 1], hi = rows[i];
    const bad = (what: string, l: number | string, h: number | string) =>
      new MemberBenefitError(level, `${hi.name}的${what}（${h}）不得差于${lo.name}（${l}）—— 权益必须随等级变好`);
    if (hi.rentDiscount > lo.rentDiscount) throw bad("租金折扣", lo.rentDiscount, hi.rentDiscount);
    if (hi.freeMinutes < lo.freeMinutes) throw bad("每单免费时长", lo.freeMinutes, hi.freeMinutes);
    if (hi.monthlyCoupons < lo.monthlyCoupons) throw bad("每月赠券", lo.monthlyCoupons, hi.monthlyCoupons);
    if (hi.pointsRate < lo.pointsRate) throw bad("积分倍率", lo.pointsRate, hi.pointsRate);
    if (hi.upgradePoints <= lo.upgradePoints) throw bad("升级积分门槛", lo.upgradePoints, hi.upgradePoints);
    if (lo.depositFree && !hi.depositFree) throw bad("免押", "免押", "不免押");
  }
}

/**
 * 改权益：校验字段范围 → 试写 → 校验单调性，**不过就整表回滚**。
 * 回滚而不是「先校验再写」，是因为单调性要看改完之后的全表。
 */
export function saveMemberBenefit(x: Partial<MemberBenefit>): MemberBenefit {
  const level = x.level;
  const i = memberBenefits.findIndex((b) => b.level === level);
  if (!level || i < 0) throw new MemberBenefitError(String(level), `会员等级 ${level} 不存在（等级是固定三档，不能新增）`);
  const next: MemberBenefit = {
    ...memberBenefits[i], ...x,
    name: memberBenefits[i].name, // 等级名不给改：它同时是页面徽标文案
    updatedBy: x.updatedBy?.trim() || "admin",
    updatedAt: nowIso(),
  };
  if (!(next.rentDiscount > 0 && next.rentDiscount <= 1)) throw new MemberBenefitError(level, "租金折扣须落在 (0,1]，1 表示不打折");
  for (const [k, v] of [["每单免费时长", next.freeMinutes], ["每月赠券", next.monthlyCoupons], ["积分倍率", next.pointsRate], ["升级积分门槛", next.upgradePoints]] as const) {
    if (!Number.isFinite(v) || v < 0) throw new MemberBenefitError(level, `${k}不得为负`);
  }
  const backup = memberBenefits[i];
  memberBenefits[i] = next;
  try {
    assertMonotonic(level);
  } catch (e) {
    memberBenefits[i] = backup;
    throw e;
  }
  return next;
}

export const listMemberBenefits = (q: PageQuery = {}) =>
  paginate(benefitsByLevel(), q.page, q.size, (x) => kwHit(q.keyword, x.level, x.name));

// ————————————————————————————————————————————————————————————————
// 次卡发放（S4：会员/次卡的写操作）
//
// 一致性口径 —— 会员名单和次卡记录在同一个菜单叶下，两边打架最容易被当场发现：
//   会员行的「次卡 / 到期」两列**由生效中的卡派生**（取有效期最晚的那张），无卡即「无」+ 空到期。
//   派生点只有 syncCardColumns 一处；会员表单里这两个字段已被 saveMember 剥掉。
// 发放**不动钱包**：它是运营赠予的权益，不是充值也不是退款，混进流水会破坏
// 「流水合计 === 余额」这条不变量（见本文件钱包流水一节）。
// ————————————————————————————————————————————————————————————————
/** 归一化日期入参：表单给的是 `YYYY-MM-DD`，落库统一存 ISO，免得同一列两种格式。 */
const toIso = (d: string): string => {
  const s = (d || "").trim();
  if (!s) return "";
  const iso8601 = /^\d{4}-\d{2}-\d{2}$/.test(s) ? `${s}T00:00:00Z` : s;
  const t = new Date(iso8601);
  if (Number.isNaN(t.getTime())) throw new MemberCardError("", `日期格式不正确：${d}`);
  return t.toISOString();
};

export class MemberCardError extends Error {
  constructor(readonly userNo: string, msg: string) {
    super(msg);
    this.name = "MemberCardError";
  }
}

/** 卡状态由有效期现算：过期就是过期，不靠定时任务回写（REVOKED 是人工态，现算不会覆盖）。 */
const cardStatusOf = (c: MemberCard): MemberCard["status"] =>
  c.status === "REVOKED" ? "REVOKED" : c.validTo && c.validTo < nowIso() ? "EXPIRED" : "ACTIVE";

export const memberCards: MemberCard[] = members
  .map((m, i) => ({ m, ct: seedCardType(i) }))
  .filter((x): x is { m: Member; ct: MemberCardType } => !!x.ct)
  // 种子卡**从会员行反推**（有效期就是该会员的 expireAt），所以两张表天然对得上
  .map(({ m, ct }, k) => ({
    cardNo: `MC${String(k + 1).padStart(4, "0")}`,
    cardType: ct,
    userNo: m.userNo,
    nickname: m.nickname,
    totalTimes: 0,
    usedTimes: 0,
    validFrom: iso(15 * 86400_000),
    validTo: m.expireAt,
    // 存量 status 只区分「是否人工撤销」，过期与否一律由 cardStatusOf 按有效期现算
    status: "ACTIVE" as const,
    source: "PURCHASE" as const, // 历史卡都是用户自购；运营发放的卡由 grantMemberCard 产生
    grantedBy: "system",
    grantedAt: iso(15 * 86400_000),
    note: "用户自购次卡（历史数据）",
  }));

/**
 * 把会员行的「次卡 / 到期」两列同步成**生效卡**的样子（有效期最晚的那张说话）。
 * 会员行不存在就不动 —— 没开会员的人可以持卡，但不该被这里凭空建出一条会员。
 */
function syncCardColumns(userNo: string): void {
  const m = members.find((x) => x.userNo === userNo);
  if (!m) return;
  const active = memberCards
    .filter((c) => c.userNo === userNo && cardStatusOf(c) === "ACTIVE")
    .sort((a, b) => (a.validTo < b.validTo ? 1 : -1));
  m.cardType = active.length ? MEMBER_CARD_LABEL[active[0].cardType] : MEMBER_CARD_NONE;
  m.expireAt = active.length ? active[0].validTo : "";
}
// 种子建完立刻同步一遍：会员行的两列从此只有一个来源，日期推移导致卡过期时两边一起变。
members.forEach((m) => syncCardColumns(m.userNo));

/** 次卡列表：按用户 / 类型 / 状态筛；状态是现算值，不读存量字段。 */
export const listMemberCards = (q: PageQuery & { userNo?: string; cardType?: string; status?: string } = {}) =>
  paginate(
    memberCards.map((c) => ({ ...c, status: cardStatusOf(c) })),
    q.page, q.size,
    (x) => {
      if (q.userNo && x.userNo !== q.userNo) return false;
      if (q.cardType && x.cardType !== q.cardType) return false;
      if (q.status && x.status !== q.status) return false;
      return kwHit(q.keyword, x.cardNo, x.userNo, x.nickname, x.grantedBy, x.note);
    },
  );

/**
 * 发放次卡：查人 → 校验 → 落卡 → 同步会员行两列。
 * 黑名单用户一律拒发：给拉黑的人送免费权益说不通，且事后没人认账。
 */
export function grantMemberCard(payload: MemberCardGrantPayload): MemberCardGrantResult {
  const userNo = payload?.userNo?.trim() ?? "";
  const u = cUsers.find((x) => x.cUserNo === userNo);
  if (!u) throw new MemberCardError(userNo, `用户 ${userNo || "(空)"} 不存在，无法发放次卡`);
  if (u.blacklisted) throw new MemberCardError(userNo, `${userNo} 在黑名单中，请先解除拉黑再发放次卡`);
  const cardType = payload.cardType;
  if (!cardType || !(cardType in MEMBER_CARD_LABEL)) throw new MemberCardError(userNo, `次卡类型 ${cardType} 不存在`);
  const note = payload.note?.trim();
  if (!note) throw new MemberCardError(userNo, "发放次卡必须填写事由（免费权益要能事后归责）");
  const validFrom = toIso(payload.validFrom);
  const validTo = toIso(payload.validTo);
  if (!validFrom || !validTo) throw new MemberCardError(userNo, "生效日期与失效日期都必填");
  if (validTo <= validFrom) throw new MemberCardError(userNo, "失效日期必须晚于生效日期");
  // 次数卡才有次数；时长卡的次数一律 0（不限次），不把无意义的数字存进去
  const totalTimes = cardType === "TIMES" ? Number(payload.totalTimes) : 0;
  if (cardType === "TIMES" && (!Number.isInteger(totalTimes) || totalTimes <= 0)) {
    throw new MemberCardError(userNo, "次数卡的总次数必须是正整数");
  }

  const card: MemberCard = {
    cardNo: padNo("MC", memberCards, "cardNo"),
    cardType, userNo, nickname: u.nickname,
    totalTimes, usedTimes: 0,
    validFrom, validTo,
    status: "ACTIVE",
    source: "GRANT",
    grantedBy: payload.operatorName?.trim() || "admin",
    grantedAt: nowIso(),
    note,
  };
  memberCards.unshift(card);
  // 会员行不存在就补一条（最低档、0 积分）：名单里查不到这个人，发出去的卡就没人管
  if (!members.some((m) => m.userNo === userNo)) {
    members.unshift({ userNo, nickname: u.nickname, level: "SILVER", points: 0, cardType: MEMBER_CARD_NONE, expireAt: "" });
  }
  syncCardColumns(userNo);
  return { card, member: { ...members.find((m) => m.userNo === userNo)! } };
}

// ————————————————————————————————————————————————————————————————
// 用户详情（S4：用户列表有拉黑，却没有一处能把一个人看全）
//
// 这里只组装**用户域**的部分。订单在 trade 域（order.ts 已经 import 本文件，反向 import 会成环），
// 由 lib/api/mocks/user.ts 把订单补上 —— 那是唯一同时看得见两域的层。
// 每一块都直接取各 tab 用的那同一份数组，不复制、不重算，抽屉才不会跟来源列表打架。
// ————————————————————————————————————————————————————————————————
export type UserProfileBase = Omit<UserProfile, "orders" | "orderStats">;

export function getUserProfileBase(cUserNo: string): UserProfileBase {
  const user = cUsers.find((x) => x.cUserNo === cUserNo);
  if (!user) notFound("用户", "User", cUserNo);
  return {
    user: { ...user },
    risk: userRisks.find((r) => r.userNo === cUserNo) ?? null,
    // 拉黑记录可能多条（拉黑—解除—再拉黑），最新在前
    blacklist: userBlacklist.filter((b) => b.userNo === cUserNo)
      .slice().sort((a, b) => (a.blacklistedAt < b.blacklistedAt ? 1 : -1)),
    whitelist: freeWhitelist.find((w) => w.userNo === cUserNo) ?? null,
    creditChanges: listCreditScoreChanges({ cUserNo, size: 50 }).list,
    wallet: wallets.find((w) => w.userNo === cUserNo) ?? null,
    // 走 listWalletTxns 而不是自己切数组：与「钱包流水」抽屉第一页是同一批记录
    walletTxns: listWalletTxns(cUserNo, { page: 1, size: PROFILE_RECENT_TXNS }).list,
    member: members.find((m) => m.userNo === cUserNo) ?? null,
    cards: listMemberCards({ userNo: cUserNo, size: 100 }).list,
  };
}
