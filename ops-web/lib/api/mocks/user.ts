// 覆盖范围：C 端用户主档与拉黑、会员、钱包、风控与黑名单、消费者分群、
// 免费用户白名单、充值套餐。
import * as db from "../../mock/db";
import type { UserApi } from "../contracts/user";
import type { PageQ, WhitelistQ, PackageQ, WalletTxnQ, MemberCardQ } from "../query";
import { wait } from "./_wait";

export const userMock: UserApi = {
  listUsers: (q: PageQ = {}) => wait(db.paginate(db.cUsers, q.page, q.size, (u) => db.kwHit(q.keyword, u.nickname, u.phone, u.cUserNo))),
  setBlacklist: (no, blacklisted) => {
    const u = db.cUsers.find((x) => x.cUserNo === no);
    if (u) u.blacklisted = blacklisted;
    return wait({ ok: true } as const, 350);
  },

  // —— 用户详情：**唯一**同时看得见用户域与订单域的一层，故订单在这里补 ——
  // db/user.ts 不能 import order.ts（order.ts 已反向 import 它，会成环）。
  // 订单直接从 db.orders 过滤，不走 listOrders 的模糊关键词 —— 关键词匹配会把 U300 和 U3001 混为一谈。
  // async：查无此人要走 Promise 拒绝（同真实 HTTP 的 404），不能同步抛给调用方
  getUserProfile: async (no) => {
    const base = db.getUserProfileBase(no);
    const orders = db.orders.filter((o) => o.cUserNo === no); // 数组本身即最新在前，与订单列表同序
    return wait({
      ...base,
      orders,
      orderStats: {
        count: orders.length,
        amount: Number(orders.reduce((s, o) => s + o.feeAmount, 0).toFixed(2)),
        // 币种取该用户第一单的；无单时退回钱包币种，再退 AED（不给一个空币种去格式化金额）
        currency: orders[0]?.currency ?? base.wallet?.currency ?? "AED",
        openCount: orders.filter((o) => o.status === "CREATED" || o.status === "DISPENSING" || o.status === "IN_USE").length,
      },
    }, 300);
  },

  // 用户扩展
  listMembers: (q: PageQ = {}) => wait(db.listMembers(q)),
  listMemberBenefits: (q: PageQ = {}) => wait(db.listMemberBenefits(q)),
  // 权益写入在 db 层校验范围与「随等级单调变好」，不过就整表回滚并抛错
  saveMemberBenefit: (x) => wait(db.saveMemberBenefit(x), 350),
  listMemberCards: (q: MemberCardQ = {}) => wait(db.listMemberCards(q)),
  // 发卡在 db 层落卡并同步会员行的次卡/到期两列（黑名单拒发、事由必填）
  grantMemberCard: (payload) => wait(db.grantMemberCard(payload), 400),
  listWallets: (q: PageQ = {}) => wait(db.listWallets(q)),
  listWalletTxns: (no, q: WalletTxnQ = {}) => wait(db.listWalletTxns(no, q)),
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
  listLogoffs: (q) => wait(db.listLogoffs(q)),
  revokeLogoff: (no) => wait(db.revokeLogoff(no), 400),
  listCUserInvoices: (q) => wait(db.listCUserInvoices(q)),
  issueCUserInvoice: (no, fileUrl) => wait(db.issueCUserInvoice(no, fileUrl), 400),
  rejectCUserInvoice: (no, reason) => wait(db.rejectCUserInvoice(no, reason), 400),
};
