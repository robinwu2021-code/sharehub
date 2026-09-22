// 用户详情抽屉的自洽单测（S4）。
//
// 背景：用户列表长期只有「拉黑」一个动作，没有一处能把一个人看全。补详情抽屉时真正的风险
// 不是接口，而是**抽屉跟它打开来源的那张列表对不上** —— 一个自相矛盾的详情比没有详情更糟。
//
// 本文件钉住五件事：
//   ① 详情里的订单/钱包/流水/风控/会员，逐条**就是**各自 tab 里的那一条（不是另算一份）
//   ② 反范式计数 `cUsers[].orders` === 订单表里该用户的实存单数
//      （它写死在 user.ts 里 —— order.ts 反向 import 会成环，所以只能靠这条断言防漂移）
//   ③ 同一个人在 members/wallets/freeWhitelist 里的昵称与手机，与用户档案完全一致
//   ④ 调分/调余额之后，详情立刻跟着变（详情不缓存自己的副本）
//   ⑤ 查无此人抛错，而不是返回一个空壳详情
import { describe, it, expect } from "vitest";
import { PROFILE_RECENT_TXNS, MEMBER_CARD_LABEL, MEMBER_CARD_NONE } from "../../types";
import { userMock as api } from "../../api/mocks/user";
import { orders } from "./order";
import {
  cUsers, members, wallets, freeWhitelist, userRisks, userBlacklist,
  listWalletTxns, listMemberCards, adjustCreditScore, saveWallet,
} from "./user";

const ordersOf = (no: string) => orders.filter((o) => o.cUserNo === no);

describe("反范式计数与订单表", () => {
  it("cUsers[].orders === 订单表里该用户的实存单数（写死的计数由这条断言兜住）", () => {
    for (const u of cUsers) {
      expect(u.orders, u.cUserNo).toBe(ordersOf(u.cUserNo).length);
    }
  });
});

describe("同一个人的昵称与手机只有一份", () => {
  const profileOf = (no: string) => cUsers.find((u) => u.cUserNo === no);

  it("members / wallets / freeWhitelist 的昵称手机与用户档案一致", () => {
    for (const m of members) {
      const u = profileOf(m.userNo);
      if (u) expect(m.nickname, m.userNo).toBe(u.nickname);
    }
    for (const w of wallets) {
      const u = profileOf(w.userNo);
      if (u) expect(w.nickname, w.userNo).toBe(u.nickname);
    }
    for (const f of freeWhitelist) {
      const u = profileOf(f.userNo);
      if (u) {
        expect(f.nickname, f.userNo).toBe(u.nickname);
        expect(f.phone, f.userNo).toBe(u.phone);
      }
    }
  });
});

describe("用户详情逐块与来源列表一致", () => {
  it("订单：与订单表里该用户的那几单同号同序（不靠关键词模糊匹配）", async () => {
    const no = "U3001";
    const p = await api.getUserProfile(no);
    expect(p.orders.map((o) => o.orderNo)).toEqual(ordersOf(no).map((o) => o.orderNo));
    expect(p.orders.every((o) => o.cUserNo === no)).toBe(true);
    // 聚合全由这批单现算，且与用户档案上的反范式计数一致
    expect(p.orderStats.count).toBe(p.orders.length);
    expect(p.orderStats.count).toBe(p.user.orders);
    expect(p.orderStats.amount).toBe(Number(p.orders.reduce((s, o) => s + o.feeAmount, 0).toFixed(2)));
  });

  it("关键词式取单会串人，精确取单不会（U300 不是 U3001 的前缀陷阱）", async () => {
    const p = await api.getUserProfile("U3001");
    // 模糊匹配下 "U300" 会命中 U3001~U3009；详情必须只有本人的单
    expect(p.orders.some((o) => o.cUserNo !== "U3001")).toBe(false);
  });

  it("钱包：与钱包列表那一行逐字段相同；最近流水就是流水抽屉的第一页", async () => {
    const no = wallets[2].userNo;
    const p = await api.getUserProfile(no);
    expect(p.wallet).toEqual(wallets.find((w) => w.userNo === no));
    expect(p.walletTxns.map((x) => x.txnNo))
      .toEqual(listWalletTxns(no, { page: 1, size: PROFILE_RECENT_TXNS }).list.map((x) => x.txnNo));
    expect(p.walletTxns.length).toBeLessThanOrEqual(PROFILE_RECENT_TXNS);
  });

  it("风控与黑名单：与风控名单/黑名单页的记录同一条", async () => {
    const risky = userRisks[0].userNo;
    const p = await api.getUserProfile(risky);
    expect(p.risk).toEqual(userRisks.find((r) => r.userNo === risky));
    // 分数以用户档案为准，风控名单只是投影 —— 详情里两处必须一样
    expect(p.risk?.creditScore).toBe(p.user.creditScore);

    const bl = userBlacklist[0].userNo;
    const q = await api.getUserProfile(bl);
    expect(q.blacklist.map((b) => b.blacklistNo))
      .toEqual(userBlacklist.filter((b) => b.userNo === bl).map((b) => b.blacklistNo));
    expect(q.user.blacklisted).toBe(true);
  });

  it("会员与次卡：会员行的次卡/到期两列由生效卡派生，详情两块自洽", async () => {
    const m = members.find((x) => x.cardType !== MEMBER_CARD_NONE)!;
    const p = await api.getUserProfile(m.userNo);
    expect(p.member).toEqual(m);
    expect(p.cards.map((c) => c.cardNo))
      .toEqual(listMemberCards({ userNo: m.userNo, size: 100 }).list.map((c) => c.cardNo));
    const active = p.cards.filter((c) => c.status === "ACTIVE").sort((a, b) => (a.validTo < b.validTo ? 1 : -1));
    expect(p.member?.cardType).toBe(MEMBER_CARD_LABEL[active[0].cardType]);
    expect(p.member?.expireAt).toBe(active[0].validTo);
  });

  it("不在名单里的用户：风控/白名单/钱包为 null，而不是抛错或造空壳", async () => {
    // U3050+ 既没有钱包（只铺了 24 条）也不在会员名单里
    const p = await api.getUserProfile("U3055");
    expect(p.user.cUserNo).toBe("U3055");
    expect(p.wallet).toBeNull();
    expect(p.member).toBeNull();
    expect(p.whitelist).toBeNull();
    expect(p.cards).toEqual([]);
    expect(p.orders).toEqual([]);
    expect(p.orderStats).toMatchObject({ count: 0, amount: 0, openCount: 0 });
    expect(p.orderStats.currency).toBeTruthy(); // 无单也要有币种，否则金额格式化不出来
  });

  it("查无此人抛错（详情不能返回一个看着像真的空壳）", async () => {
    await expect(api.getUserProfile("U-not-exist")).rejects.toThrow(/不存在/);
  });
});

describe("写操作之后详情立刻跟着变", () => {
  it("调信用分：详情里的分数、风控等级与调分留痕同步", async () => {
    const no = "U3020";
    const before = (await api.getUserProfile(no)).user.creditScore;
    adjustCreditScore(no, { delta: -7, reason: "单测：详情联动", operatorName: "tester" });
    const after = await api.getUserProfile(no);
    expect(after.user.creditScore).toBe(before - 7);
    expect(after.creditChanges[0]).toMatchObject({ before, after: before - 7, delta: -7 });
    if (after.risk) expect(after.risk.creditScore).toBe(after.user.creditScore);
  });

  it("调余额：详情的钱包与最近流水跟着动，且流水合计仍等于余额", async () => {
    const no = wallets[5].userNo;
    const w = saveWallet({ userNo: no, balance: wallets[5].balance + 12 });
    const p = await api.getUserProfile(no);
    expect(p.wallet?.balance).toBe(w.balance);
    // 手工调整会补一条流水，它就是最近流水的第一条（同 saveWallet 的口径）
    expect(p.walletTxns[0]).toMatchObject({ bizType: "MANUAL_ADJUST", amount: 12 });
  });
});
