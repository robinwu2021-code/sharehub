// 覆盖范围：C 端用户主档与拉黑、会员、钱包、风控与黑名单、消费者分群、
// 免费用户白名单、充值套餐。
// 端点前缀：/api/user/**（拉黑写操作走 /internal/user/**，消费者分群走 /api/ops/reports/**，沿用现状）。
import { client } from "../http-client";
import type { UserApi } from "../contracts/user";
import type { PageQ, PackageQ, WhitelistQ } from "../query";

export const userHttp: UserApi = {
  listUsers: (q?: PageQ) => client.get("/api/user/users", q),
  setBlacklist: (no, blacklisted) => client.post("/internal/user/credit/blacklist", { cUserNo: no, blacklisted }),

  // 用户扩展
  listMembers: (q?: PageQ) => client.get("/api/user/members", q),
  listWallets: (q?: PageQ) => client.get("/api/user/wallets", q),
  saveMember: (x) => client.post(x.userNo ? `/api/user/members/${x.userNo}` : "/api/user/members", x),
  saveWallet: (x) => client.post(x.userNo ? `/api/user/wallets/${x.userNo}` : "/api/user/wallets", x),
  listConsumerSegments: (q?: PageQ) => client.get("/api/ops/reports/consumer-segments", q),

  // 用户风控
  listUserRisks: (q?: PageQ) => client.get("/api/user/risk-users", q),
  listUserBlacklist: (q?: PageQ) => client.get("/api/user/blacklist", q),

  // 批次 B4/B5：白名单与充值套餐归 user 域
  listFreeWhitelist: (q?: WhitelistQ) => client.get("/api/user/free-whitelist", q),
  saveFreeWhitelist: (x) => client.post(x.userNo ? `/api/user/free-whitelist/${x.userNo}` : "/api/user/free-whitelist", x),
  revokeFreeWhitelist: (no) => client.post(`/api/user/free-whitelist/${no}/revoke`, {}),
  listRechargePackages: (q?: PackageQ) => client.get("/api/user/recharge-packages", q),
  saveRechargePackage: (x) => client.post(x.packageNo ? `/api/user/recharge-packages/${x.packageNo}` : "/api/user/recharge-packages", x),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  archiveRechargePackage: (no) => client.post(`/api/user/recharge-packages/${no}/archive`, {}),
  unarchiveRechargePackage: (no) => client.post(`/api/user/recharge-packages/${no}/unarchive`, {}),
};
