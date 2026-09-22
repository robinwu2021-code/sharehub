// 会员权益增改 + 次卡发放的单测（S4）。
//
// 背景：会员/次卡此前是一张只读名单 —— 「等级」列背后没有任何权益口径，「次卡」列谁都改不了。
// 本文件钉住四件事：
//   ① 权益只有三档、只能改不能增；范围非法一律抛错
//   ② 权益必须**随等级单调变好**（黄金比铂金还便宜的话会员体系当场失去意义），
//      校验不过**整表回滚**，不留半写状态
//   ③ 发放次卡：事由必填 / 黑名单拒发 / 有效期先后 / 次数卡次数为正整数，越界一律抛错
//   ④ 发完卡，**会员名单那一行的「次卡 / 到期」两列跟着变** —— 派生只有一个来源，
//      而且会员表单改不动它（saveMember 会剥掉这两个字段）
import { describe, it, expect } from "vitest";
import { MEMBER_CARD_LABEL, MEMBER_CARD_NONE, MEMBER_LEVEL_ORDER } from "../../types";
import {
  memberBenefits, listMemberBenefits, saveMemberBenefit, MemberBenefitError,
  memberCards, listMemberCards, grantMemberCard, MemberCardError,
  members, saveMember, cUsers,
} from "./user";

const benefitOf = (level: string) => memberBenefits.find((b) => b.level === level)!;
const memberOf = (no: string) => members.find((m) => m.userNo === no);
/** 未来日期（相对当下），避免用固定日期写出「跑到那天就红」的测试。 */
const day = (n: number) => new Date(Date.now() + n * 86400_000).toISOString().slice(0, 10);
/** 找一个没被拉黑的用户（黑名单用户拒发，不能拿来做正例）。 */
const freeUser = (i = 0) => cUsers.filter((u) => !u.blacklisted)[i];

describe("会员权益：只改不增", () => {
  it("三档齐全，按等级由低到高排（页面顺序 = 校验顺序）", () => {
    expect(listMemberBenefits().list.map((b) => b.level)).toEqual(MEMBER_LEVEL_ORDER);
  });

  it("等级不存在 → 抛错（不能凭空多一档没人能落进去的权益）", () => {
    expect(() => saveMemberBenefit({ level: "DIAMOND" as never })).toThrow(MemberBenefitError);
  });

  it("折扣必须落在 (0,1]，负数项一律拒", () => {
    expect(() => saveMemberBenefit({ level: "GOLD", rentDiscount: 0 })).toThrow(/折扣/);
    expect(() => saveMemberBenefit({ level: "GOLD", rentDiscount: 1.2 })).toThrow(/折扣/);
    expect(() => saveMemberBenefit({ level: "GOLD", freeMinutes: -1 })).toThrow(/不得为负/);
  });

  it("改得动：合法值落库并记操作人与时间", () => {
    const before = benefitOf("GOLD");
    const r = saveMemberBenefit({ level: "GOLD", monthlyCoupons: 3, updatedBy: "tester" });
    expect(r.monthlyCoupons).toBe(3);
    expect(r.updatedBy).toBe("tester");
    expect(r.updatedAt >= before.updatedAt).toBe(true);
    expect(benefitOf("GOLD").monthlyCoupons).toBe(3);
    saveMemberBenefit({ level: "GOLD", monthlyCoupons: before.monthlyCoupons, updatedBy: "tester" });
  });

  it("等级名改不了（它同时是页面徽标文案）", () => {
    saveMemberBenefit({ level: "GOLD", name: "土豪" });
    expect(benefitOf("GOLD").name).toBe("黄金");
  });
});

describe("会员权益：随等级单调变好，不过就整表回滚", () => {
  it("黄金折扣低于铂金 → 抛错，且黄金那一行原样不动", () => {
    const before = { ...benefitOf("GOLD") };
    expect(() => saveMemberBenefit({ level: "GOLD", rentDiscount: 0.5 })).toThrow(/不得差于|变好/);
    expect(benefitOf("GOLD")).toEqual(before); // 半写会留下一张自相矛盾的权益表
  });

  it("铂金免押被取消（低档已免押）→ 抛错", () => {
    const before = { ...benefitOf("PLATINUM") };
    expect(() => saveMemberBenefit({ level: "GOLD", depositFree: true })).not.toThrow();
    expect(() => saveMemberBenefit({ level: "PLATINUM", depositFree: false })).toThrow(/免押/);
    expect(benefitOf("PLATINUM")).toEqual(before);
    saveMemberBenefit({ level: "GOLD", depositFree: false });
  });

  it("升级门槛必须严格递增（两档同门槛就分不出档）", () => {
    expect(() => saveMemberBenefit({ level: "GOLD", upgradePoints: benefitOf("PLATINUM").upgradePoints }))
      .toThrow(/升级积分门槛/);
  });
});

describe("次卡发放", () => {
  it("事由必填 / 用户必须存在 / 黑名单拒发", () => {
    const u = freeUser();
    const base = { cardType: "MONTH" as const, validFrom: day(0), validTo: day(30) };
    expect(() => grantMemberCard({ ...base, userNo: u.cUserNo, note: "  " })).toThrow(/事由/);
    expect(() => grantMemberCard({ ...base, userNo: "U-nope", note: "补偿" })).toThrow(/不存在/);
    const black = cUsers.find((x) => x.blacklisted)!;
    expect(() => grantMemberCard({ ...base, userNo: black.cUserNo, note: "补偿" })).toThrow(/黑名单/);
  });

  it("有效期与次数校验：失效必须晚于生效，次数卡的次数须为正整数", () => {
    const no = freeUser(1).cUserNo;
    expect(() => grantMemberCard({ userNo: no, cardType: "MONTH", validFrom: day(30), validTo: day(1), note: "补偿" }))
      .toThrow(/晚于/);
    expect(() => grantMemberCard({ userNo: no, cardType: "TIMES", totalTimes: 0, validFrom: day(0), validTo: day(30), note: "补偿" }))
      .toThrow(/正整数/);
  });

  it("发放成功：落卡留痕，并把会员行的次卡/到期两列同步成这张卡", () => {
    const u = freeUser(2);
    const before = memberCards.length;
    const r = grantMemberCard({
      userNo: u.cUserNo, cardType: "YEAR", validFrom: day(0), validTo: day(365),
      note: "单测：客诉补偿", operatorName: "tester",
    });
    expect(memberCards.length).toBe(before + 1);
    expect(r.card).toMatchObject({ userNo: u.cUserNo, cardType: "YEAR", source: "GRANT", grantedBy: "tester", totalTimes: 0 });
    expect(r.card.nickname).toBe(u.nickname); // 昵称取用户档案，不另编一份
    // 年卡的有效期最晚，故会员行显示的就是它
    expect(r.member.cardType).toBe(MEMBER_CARD_LABEL.YEAR);
    expect(r.member.expireAt).toBe(r.card.validTo);
    expect(memberOf(u.cUserNo)).toMatchObject({ cardType: MEMBER_CARD_LABEL.YEAR, expireAt: r.card.validTo });
    // 表单给的是 YYYY-MM-DD，落库统一 ISO（同一列不许两种格式）
    expect(r.card.validTo.endsWith("Z")).toBe(true);
  });

  it("给还没开会员的人发卡：补一条最低档会员行，卡不会没人管", () => {
    // 会员名单只铺了 U3000~U3023，取一个更靠后且未被拉黑的用户
    const u = cUsers.find((x) => !x.blacklisted && !memberOf(x.cUserNo))!;
    const r = grantMemberCard({
      userNo: u.cUserNo, cardType: "TIMES", totalTimes: 10, validFrom: day(0), validTo: day(90), note: "单测：新客礼包",
    });
    expect(memberOf(u.cUserNo)).toBeTruthy();
    expect(r.member.level).toBe("SILVER");
    expect(r.member.points).toBe(0);
    expect(r.member.cardType).toBe(MEMBER_CARD_LABEL.TIMES);
    expect(listMemberCards({ userNo: u.cUserNo }).list.map((c) => c.cardNo)).toContain(r.card.cardNo);
  });

  it("会员表单改不动次卡两列（否则名单会说「有月卡」而次卡记录里一张也没有）", () => {
    const u = cUsers.find((x) => !x.blacklisted && !memberOf(x.cUserNo))!;
    const m = saveMember({ userNo: u.cUserNo, nickname: u.nickname, level: "GOLD", points: 10, cardType: "年卡", expireAt: "2099-01-01T00:00:00Z" });
    expect(m.level).toBe("GOLD");
    expect(m.cardType).toBe(MEMBER_CARD_NONE); // 没有卡，所以只能是「无」
    expect(m.expireAt).toBe("");
  });

  it("次卡列表：按用户/类型/状态精确筛，状态按有效期现算", () => {
    const all = listMemberCards({ size: 500 });
    expect(all.total).toBe(memberCards.length);
    const times = listMemberCards({ cardType: "TIMES", size: 500 });
    expect(times.list.every((c) => c.cardType === "TIMES")).toBe(true);
    const active = listMemberCards({ status: "ACTIVE", size: 500 });
    expect(active.list.every((c) => c.validTo > new Date().toISOString())).toBe(true);
  });
});
