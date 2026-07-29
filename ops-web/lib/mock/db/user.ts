// 用户域（C 端）：用户档案 cUsers / 风险名单 userRisks / 黑名单 userBlacklist /
// 会员 members / 钱包 wallets / 免费用户白名单 freeWhitelist / 消费者分层 consumerSegments。
// 跨域自洽：wallets 的用户号与 cUsers 一一对应，orderCount 直接取 cUsers 的订单数。
import type {
  CUser, UserRisk, UserBlacklist, Member, Wallet,
  FreeUserWhitelist, ConsumerSegment, PageQuery,
} from "../../types";
import { NICKS, OPERATORS, REASONS, p, iso, phone } from "./internal";
import { paginate, kwHit, upsert, nextNo } from "./helpers";

export const cUsers: CUser[] = Array.from({ length: 60 }, (_, i) => ({
  cUserNo: `U${3000 + i}`, nickname: p(["Ahmed", "Mohammed", "Fatima", "Layla", "Yusuf", "李明"], i),
  phone: `+9715${String(5000000 + i * 173).slice(0, 7)}`, creditScore: 550 + (i * 7) % 300,
  blacklisted: i % 17 === 0, orders: (i * 3) % 40, registeredAt: iso(i * 86400_000),
}));

// 风险名单 / 黑名单都必须挂在**真实存在的 C 端用户**上（台账 M7：原先用 `U-00xx` 带横杠的
// 第三套号，cUsers 里根本没有，点进去查无此人）。昵称/手机号/信用分一律由 cUsers 反查，不再手写。
const userOf = (no: string): CUser => {
  const u = cUsers.find((x) => x.cUserNo === no);
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

export const members: Member[] = Array.from({ length: 24 }, (_, i) => ({
  userNo: `U${3000 + i}`, nickname: p(NICKS, i), level: p(["SILVER", "GOLD", "PLATINUM"] as const, i),
  points: (i * 137) % 5000, cardType: p(["无", "月卡", "季卡", "年卡"], i), expireAt: iso(-(30 + i * 15) * 86400_000),
}));
// 钱包用户号与 cUsers 一一对应（U3000+）；orderCount 直接取该用户在 cUsers 里的订单数，保证两页数据自洽
const RECHARGE_DENOM = [20, 50, 100];
export const wallets: Wallet[] = Array.from({ length: 24 }, (_, i) => {
  const userNo = `U${3000 + i}`;
  const orderCount = cUsers.find((u) => u.cUserNo === userNo)?.orders ?? (i * 3) % 40;
  const rechargeCount = Math.floor(orderCount / 4) + 1; // 约每 4 单充值一次
  const denom = p(RECHARGE_DENOM, i);
  return {
    userNo, nickname: p(NICKS, i), balance: Number(((i * 7) % 200 + (i % 10) / 10).toFixed(2)),
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
  return {
    userNo: `U${3000 + i}`,
    nickname: p(NICKS, i),
    phone: phone(i),
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

export const saveMember = (x: Partial<Member>) => upsert(members, x, "userNo", () => nextNo("U", members));
export const saveWallet = (x: Partial<Wallet>) => upsert(wallets, x, "userNo", () => nextNo("U", wallets));

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
  if (i < 0) throw new Error(`白名单不存在：${userNo}`);
  freeWhitelist[i] = { ...freeWhitelist[i], status: "REVOKED" };
  return freeWhitelist[i];
};
