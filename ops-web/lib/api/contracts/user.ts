// 覆盖范围：C 端用户主档与拉黑、会员、钱包、风控与黑名单、消费者分群、
// 免费用户白名单、充值套餐。
import type { PageQ, WhitelistQ, PackageQ, WalletTxnQ, MemberCardQ, MemberPlanQ } from "../query";
import type {
  PageResult, CUser, Member, Wallet, WalletTxn, UserRisk, UserBlacklist,
  ConsumerSegment, FreeUserWhitelist, RechargePackage, LogoffItem, CUserInvoiceRow,
  CreditScoreChange, CreditScoreAdjustPayload, CreditScoreAdjustResult,
  MemberBenefit, MemberPlan, MemberCard, MemberCardGrantPayload, MemberCardGrantResult, UserProfile,
} from "../../types";

export interface UserApi {
  listUsers(q?: PageQ): Promise<PageResult<CUser>>;
  setBlacklist(cUserNo: string, blacklisted: boolean): Promise<{ ok: true }>;
  /**
   * 用户详情（`user:cuser:read`）：档案 + 风控 + 钱包 + 会员/次卡 + 订单**一次取全**。
   *
   * 为什么是一个接口而不是前端按用户号并发调五个列表：列表接口的 keyword 是模糊匹配，
   * 前端拼装迟早捞出别人的记录；而抽屉里的每一条都必须就是它所属 tab 里的那一条。
   * ⚠️ 后端缺口：`GET /api/user/users/{cUserNo}/profile` 尚未实现（UserController 只有列表）。
   */
  getUserProfile(cUserNo: string): Promise<UserProfile>;

  // === 用户扩展 tab ===
  listMembers(q?: PageQ): Promise<PageResult<Member>>;
  listWallets(q?: PageQ): Promise<PageResult<Wallet>>;
  /**
   * 钱包流水（权限码 `user:wallet:read`，与钱包列表同码）：`userNo` 走路径，`type` 可筛流水类型。
   * 后端按主键倒序返回（最新在前），前端不再排序，翻页语义才不会跳。
   */
  listWalletTxns(userNo: string, q?: WalletTxnQ): Promise<PageResult<WalletTxn>>;
  /** 会员增改。`cardType`/`expireAt` 由次卡派生，**服务端会剥掉**这两个字段（见 mock/db/user.ts）。 */
  saveMember(x: Partial<Member> & { userNo?: string }): Promise<Member>;
  /**
   * 会员方案（`user:member:read`）—— 「卖什么」，与会员权益（「等级给什么」）正交。
   *
   * ⚠️ 此前 `mbr_plan` **没有任何写入方**：有表、有实体、C 端有页面读它，
   * 但全仓没有一个端点能建或改它，表里 0 行 —— 于是 C 端会员页永远是空列表，
   * 看起来像「本平台暂无会员方案」。
   */
  listMemberPlans(q?: MemberPlanQ): Promise<PageResult<MemberPlan>>;
  /**
   * 增改会员方案（`user:member:update`）。`planNo` 为空则新建（后端取号 `MP`）。
   *
   * **`status` 不在入参里** —— 上下架走下面两个动作端点。混在保存字段里，
   * 「下架一个方案」和「改个名字」就成了同一个动作，而其中一个会影响正在卖的东西。
   */
  saveMemberPlan(x: Partial<MemberPlan> & { planNo?: string }): Promise<MemberPlan>;
  /** 上架：对 C 端可见。**权益为空的方案会被服务端拒绝上架**（否则买了什么也不会发生）。 */
  enableMemberPlan(planNo: string): Promise<MemberPlan>;
  /** 下架。已开通的会员不受影响（权益按 usr_membership 走，不回读方案状态）。 */
  disableMemberPlan(planNo: string): Promise<MemberPlan>;
  /**
   * 会员权益（`user:member:read`）：固定三档，一页三条。
   */
  listMemberBenefits(q?: PageQ): Promise<PageResult<MemberBenefit>>;
  /**
   * 改会员权益（`user:member:update`）：**只改不增**（等级固定三档），
   * 且权益必须随等级单调变好（不满足服务端整表回滚并报错）。
   */
  saveMemberBenefit(x: Partial<MemberBenefit> & { level: MemberBenefit["level"] }): Promise<MemberBenefit>;
  /**
   * 次卡（`user:member:read`）：`userNo` 精确筛该用户的卡（用户详情抽屉用）。
   */
  listMemberCards(q?: MemberCardQ): Promise<PageResult<MemberCard>>;
  /**
   * 发放次卡（`user:member:update`）：事由必填、黑名单用户拒发、失效日期必须晚于生效日期；
   * 落卡后**同步会员行的次卡/到期两列**，故返回值把同步后的会员行一起带回。
   */
  grantMemberCard(payload: MemberCardGrantPayload): Promise<MemberCardGrantResult>;
  saveWallet(x: Partial<Wallet> & { userNo?: string }): Promise<Wallet>;
  listConsumerSegments(q?: PageQ): Promise<PageResult<ConsumerSegment>>;

  // === 用户风控 ===
  listUserRisks(q?: PageQ): Promise<PageResult<UserRisk>>;
  listUserBlacklist(q?: PageQ): Promise<PageResult<UserBlacklist>>;
  /**
   * 调整信用分（user:risk:update）：`delta` 有正负（加分/减分），**原因必填**，
   * 调整后分数须落在 CREDIT_SCORE_MIN~MAX 内（越界后端/mock 一律拒绝，不静默截断）。
   * 返回落库后的用户 + 联动后的风控记录 + 刚写入的变更留痕。
   */
  adjustCreditScore(cUserNo: string, payload: CreditScoreAdjustPayload): Promise<CreditScoreAdjustResult>;
  /** 信用分变更留痕（审计）：用户/风控抽屉的时间线按 `cUserNo` 精确过滤。 */
  listCreditScoreChanges(q?: PageQ & { cUserNo?: string }): Promise<PageResult<CreditScoreChange>>;

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

  // —— 注销申请受理（user:logoff:read / :revoke）——
  //
  // 没有「立即执行」：冷静期到点由清除作业执行，给一个手动提前销毁的按钮，
  // 等于给了一个不可逆的误操作入口。

  listLogoffs(q?: PageQ & { status?: string }): Promise<PageResult<LogoffItem>>;
  /** 代为撤销。冷静期已过后端会拒（400）—— 那时数据可能已在清除，说「撤销成功」是假话。 */
  revokeLogoff(cUserNo: string): Promise<LogoffItem>;

  // —— C 端开票受理（user:invoice:read / :handle）——

  listCUserInvoices(q?: PageQ & { status?: string }): Promise<PageResult<CUserInvoiceRow>>;
  issueCUserInvoice(invoiceNo: string, fileUrl: string): Promise<CUserInvoiceRow>;
  /** 驳回原因必填：只说「已驳回」，用户无从改正后重提，他会做的事是再提一次。 */
  rejectCUserInvoice(invoiceNo: string, reason: string): Promise<CUserInvoiceRow>;
}
