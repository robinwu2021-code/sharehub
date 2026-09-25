// 覆盖范围：C 端用户主档与拉黑、会员、钱包、风控与黑名单、消费者分群、
// 免费用户白名单、充值套餐。
// 端点前缀：/api/user/**（拉黑写操作走 /internal/user/**，消费者分群走 /api/ops/reports/**，沿用现状）。
import { client } from "../http-client";
import type { UserApi } from "../contracts/user";
import type { PageQ, PackageQ, WhitelistQ, WalletTxnQ, MemberCardQ } from "../query";

export const userHttp: UserApi = {
  listUsers: (q?: PageQ) => client.get("/api/user/users", q),
  setBlacklist: (no, blacklisted) => client.post("/internal/user/credit/blacklist", { cUserNo: no, blacklisted }),
  // 详情是用户资源的子资源。⚠️ 后端缺口：UserController 目前只有 GET /api/user/users
  getUserProfile: (no) => client.get(`/api/user/users/${no}/profile`),

  // 用户扩展
  listMembers: (q?: PageQ) => client.get("/api/user/members", q),
  // 会员权益与次卡都挂在 user 域（与 /members 同级）。⚠️ 后端缺口：四个端点都还没有
  listMemberBenefits: (q?: PageQ) => client.get("/api/user/member-benefits", q),
  // 等级是主键：只有更新语义，故路径必带 {level}（没有「新增一档」的入口）
  saveMemberBenefit: (x) => client.post(`/api/user/member-benefits/${x.level}`, x),
  listMemberCards: (q?: MemberCardQ) => client.get("/api/user/member-cards", q),
  grantMemberCard: (payload) => client.post("/api/user/member-cards", payload),
  listWallets: (q?: PageQ) => client.get("/api/user/wallets", q),
  // 流水是钱包的子资源，故挂在 /wallets/{userNo}/ 下（后端 UserOpsController 已实现此路径）
  listWalletTxns: (no, q?: WalletTxnQ) => client.get(`/api/user/wallets/${no}/txns`, q),
  saveMember: (x) => client.post(x.userNo ? `/api/user/members/${x.userNo}` : "/api/user/members", x),
  saveWallet: (x) => client.post(x.userNo ? `/api/user/wallets/${x.userNo}` : "/api/user/wallets", x),
  listConsumerSegments: (q?: PageQ) => client.get("/api/ops/reports/consumer-segments", q),

  // 用户风控
  listUserRisks: (q?: PageQ) => client.get("/api/user/risk-users", q),
  listUserBlacklist: (q?: PageQ) => client.get("/api/user/blacklist", q),
  // 调分是「对用户信用分的一次带留痕的变更」，故建在用户资源下；变更流水单列一个只读集合
  adjustCreditScore: (no, payload) => client.post(`/api/user/users/${no}/credit-score`, payload),
  listCreditScoreChanges: (q?: PageQ & { cUserNo?: string }) => client.get("/api/user/credit-score-changes", q),

  // 批次 B4/B5：白名单与充值套餐归 user 域
  listFreeWhitelist: (q?: WhitelistQ) => client.get("/api/user/free-whitelist", q),
  saveFreeWhitelist: (x) => client.post(x.userNo ? `/api/user/free-whitelist/${x.userNo}` : "/api/user/free-whitelist", x),
  revokeFreeWhitelist: (no) => client.post(`/api/user/free-whitelist/${no}/revoke`, {}),
  listRechargePackages: (q?: PackageQ) => client.get("/api/user/recharge-packages", q),
  saveRechargePackage: (x) => client.post(x.packageNo ? `/api/user/recharge-packages/${x.packageNo}` : "/api/user/recharge-packages", x),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  archiveRechargePackage: (no) => client.post(`/api/user/recharge-packages/${no}/archive`, {}),
  unarchiveRechargePackage: (no) => client.post(`/api/user/recharge-packages/${no}/unarchive`, {}),
  listLogoffs: (q) => client.get("/api/user/logoffs", q),
  revokeLogoff: (no) => client.post(`/api/user/logoffs/${no}/revoke`, {}),
  listCUserInvoices: (q) => client.get("/api/user/cuser-invoices", q),
  issueCUserInvoice: (no, fileUrl) => client.post(`/api/user/cuser-invoices/${no}/issue`, { fileUrl }),
  rejectCUserInvoice: (no, reason) => client.post(`/api/user/cuser-invoices/${no}/reject`, { reason }),
};
