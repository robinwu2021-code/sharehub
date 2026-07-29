// 覆盖范围：C 端用户主档与拉黑、会员、钱包、风控与黑名单、消费者分群、
// 免费用户白名单、充值套餐。
import type { PageQ, WhitelistQ, PackageQ } from "../query";
import type {
  PageResult, CUser, Member, Wallet, UserRisk, UserBlacklist,
  ConsumerSegment, FreeUserWhitelist, RechargePackage,
} from "../../types";

export interface UserApi {
  listUsers(q?: PageQ): Promise<PageResult<CUser>>;
  setBlacklist(cUserNo: string, blacklisted: boolean): Promise<{ ok: true }>;

  // === 用户扩展 tab ===
  listMembers(q?: PageQ): Promise<PageResult<Member>>;
  listWallets(q?: PageQ): Promise<PageResult<Wallet>>;
  saveMember(x: Partial<Member> & { userNo?: string }): Promise<Member>;
  saveWallet(x: Partial<Wallet> & { userNo?: string }): Promise<Wallet>;
  listConsumerSegments(q?: PageQ): Promise<PageResult<ConsumerSegment>>;

  // === 用户风控 ===
  listUserRisks(q?: PageQ): Promise<PageResult<UserRisk>>;
  listUserBlacklist(q?: PageQ): Promise<PageResult<UserBlacklist>>;

  // === 批次 B4/B5：免费白名单 / 充值套餐（规格 §7 §8）===
  listFreeWhitelist(q?: WhitelistQ): Promise<PageResult<FreeUserWhitelist>>;
  saveFreeWhitelist(x: Partial<FreeUserWhitelist> & { userNo?: string }): Promise<FreeUserWhitelist>;
  /** 撤销白名单：软撤销置 REVOKED（决策 §八-4，不物理删）。 */
  revokeFreeWhitelist(userNo: string): Promise<FreeUserWhitelist>;
  listRechargePackages(q?: PackageQ): Promise<PageResult<RechargePackage>>;
  saveRechargePackage(x: Partial<RechargePackage> & { packageNo?: string }): Promise<RechargePackage>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  archiveRechargePackage(packageNo: string): Promise<RechargePackage>;
  unarchiveRechargePackage(packageNo: string): Promise<RechargePackage>;
}
