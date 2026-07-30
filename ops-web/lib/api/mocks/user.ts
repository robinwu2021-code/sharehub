// 覆盖范围：C 端用户主档与拉黑、会员、钱包、风控与黑名单、消费者分群、
// 免费用户白名单、充值套餐。
import * as db from "../../mock/db";
import type { UserApi } from "../contracts/user";
import type { PageQ, WhitelistQ, PackageQ } from "../query";
import { wait } from "./_wait";

export const userMock: UserApi = {
  listUsers: (q: PageQ = {}) => wait(db.paginate(db.cUsers, q.page, q.size, (u) => db.kwHit(q.keyword, u.nickname, u.phone, u.cUserNo))),
  setBlacklist: (no, blacklisted) => {
    const u = db.cUsers.find((x) => x.cUserNo === no);
    if (u) u.blacklisted = blacklisted;
    return wait({ ok: true } as const, 350);
  },

  // 用户扩展
  listMembers: (q: PageQ = {}) => wait(db.listMembers(q)),
  listWallets: (q: PageQ = {}) => wait(db.listWallets(q)),
  saveMember: (x) => wait(db.saveMember(x), 350),
  saveWallet: (x) => wait(db.saveWallet(x), 350),
  listConsumerSegments: (q: PageQ = {}) => wait(db.listConsumerSegments(q)),

  // 用户风控
  listUserRisks: (q: PageQ = {}) => wait(db.listUserRisks(q)),
  listUserBlacklist: (q: PageQ = {}) => wait(db.listUserBlacklist(q)),
  // 调分在 db 层真改 cUsers 的分数、联动风控等级/观察名单并落变更留痕（越界抛错）
  adjustCreditScore: (no, payload) => wait(db.adjustCreditScore(no, payload), 400),
  listCreditScoreChanges: (q: PageQ & { cUserNo?: string } = {}) => wait(db.listCreditScoreChanges(q)),

  // 批次 B4/B5：免费白名单 / 充值套餐
  listFreeWhitelist: (q: WhitelistQ = {}) => wait(db.listFreeWhitelist(q)),
  saveFreeWhitelist: (x) => wait(db.saveFreeWhitelist(x), 350),
  revokeFreeWhitelist: (no) => wait(db.revokeFreeWhitelist(no), 400),
  listRechargePackages: (q: PackageQ = {}) => wait(db.listRechargePackages(q)),
  saveRechargePackage: (x) => wait(db.saveRechargePackage(x), 350),

  // G1 软删除：归档 / 恢复（禁止物理删除）
  archiveRechargePackage: async (no) => wait(db.archiveRechargePackage(no), 350),
  unarchiveRechargePackage: async (no) => wait(db.unarchiveRechargePackage(no), 350),
};
