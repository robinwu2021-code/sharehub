// 营销 S2：优惠券发放 + 推送触达发送 的守卫测试。
//
// 这两个动作的权限码（marketing:coupon:issue / marketing:push:send）早就在 RBAC 里定义，
// 页面却从没用过。补上写操作后，四件事必须由服务端（本 db 层）兜住，不能指望页面自觉：
//  ① 发放真的改库存并留痕（不是只弹个 toast）；② 超发 / 已下线 / 已过期一律拒绝；
//  ③ 推送状态机强制，已发送是终态；④ 发送**必须带幂等键**，同键第二次拒绝。
import { describe, expect, it } from "vitest";
// 状态机 SSOT 在 types 层：按钮派生与本层校验共用同一份（见 AD_CAMPAIGN_TRANSITIONS）
import { adWindowPassed, adActionAllowed, adCampaignActions } from "../../types";
import {
  coupons, couponIssueRecords, issueCoupon, listCouponIssueRecords, CouponIssueError,
  pushMessages, savePushMessage, sendPushMessage, transitionPush, PushError,
  resolveAudience, AudienceError,
  campaigns, listCampaigns, saveCampaign, transitionCampaign, CampaignError,
 adCampaigns, transitionAdCampaign, saveReferralRule,} from "./marketing";
import { couponRemaining, couponExpired, campaignActions, campaignWindowPassed, CAMPAIGN_TRANSITIONS } from "../../types";
import type { Campaign, CampaignAction, CampaignStatus } from "../../types";
import { cUsers, members, consumerSegments } from "./user";

/** 取一张当前可发放的券（ACTIVE / 未过期 / 有剩余），每个用例各取一张避免互相污染。 */
const issuableAt = (n: number) =>
  coupons.filter((c) => c.status === "ACTIVE" && !c.archivedAt && !couponExpired(c) && couponRemaining(c) > 0)[n];

describe("人群解析（发放与推送共用）", () => {
  it("规模全部来自既有主数据，不写死数字", () => {
    expect(resolveAudience({ targetType: "ALL" }).size).toBe(cUsers.length);
    expect(resolveAudience({ targetType: "MEMBER_LEVEL", targetValue: "PLATINUM" }).size)
      .toBe(members.filter((m) => m.level === "PLATINUM").length);
    const seg = consumerSegments[0];
    const r = resolveAudience({ targetType: "SEGMENT", targetValue: seg.segmentNo });
    expect(r.size).toBe(seg.userCount);
    expect(r.targetDesc).toContain(seg.segment);
  });

  it("指定用户号必须是 cUsers 里真实存在的号", () => {
    const ok = resolveAudience({ targetType: "USER_LIST", targetValue: `${cUsers[0].cUserNo},${cUsers[1].cUserNo}` });
    expect(ok.size).toBe(2);
    expect(() => resolveAudience({ targetType: "USER_LIST", targetValue: "U9999" })).toThrow(AudienceError);
    expect(() => resolveAudience({ targetType: "USER_LIST", targetValue: "" })).toThrow(/至少填写一个用户号/);
  });

  it("维度非法直接抛错，不静默兜底成「全体」", () => {
    expect(() => resolveAudience({ targetType: "MEMBER_LEVEL", targetValue: "DIAMOND" })).toThrow(AudienceError);
    expect(() => resolveAudience({ targetType: "SEGMENT", targetValue: "SEG000" })).toThrow(/分层不存在/);
  });
});

describe("优惠券发放", () => {
  it("发放真的改库存并留痕：已发放 +N、剩余 -N，落一条发放流水", () => {
    const c = issuableAt(0);
    const beforeIssued = c.issued;
    const beforeRemaining = couponRemaining(c);
    const beforeRecords = couponIssueRecords.length;

    const { coupon, record } = issueCoupon(c.couponNo, {
      targetType: "SEGMENT", targetValue: consumerSegments[0].segmentNo, quantity: 30,
    });

    expect(coupon.issued).toBe(beforeIssued + 30);
    expect(couponRemaining(coupon)).toBe(beforeRemaining - 30);
    expect(coupon.stock).toBe(c.stock); // 发行总量不变——变的只有「已发放」
    expect(couponIssueRecords.length).toBe(beforeRecords + 1);
    expect(record).toMatchObject({
      couponNo: c.couponNo, couponName: c.name, targetType: "SEGMENT", quantity: 30, operatorName: "admin",
    });
    expect(record.targetDesc).toContain(consumerSegments[0].segment);
    // 流水可按券号查回来
    expect(listCouponIssueRecords({ couponNo: c.couponNo, size: 50 }).list.some((r) => r.issueNo === record.issueNo)).toBe(true);
  });

  it("超发被拒：张数不得超过剩余库存，且库存与流水都不动", () => {
    const c = issuableAt(1);
    const before = { issued: c.issued, records: couponIssueRecords.length };
    const over = couponRemaining(c) + 1;

    expect(() => issueCoupon(c.couponNo, { targetType: "ALL", quantity: over })).toThrow(CouponIssueError);
    expect(() => issueCoupon(c.couponNo, { targetType: "ALL", quantity: over })).toThrow(/超过剩余库存/);
    expect(c.issued).toBe(before.issued);
    expect(couponIssueRecords.length).toBe(before.records);
  });

  it("刚好发完剩余库存是允许的（边界不是 off-by-one）", () => {
    const c = issuableAt(2);
    const all = couponRemaining(c);
    const { coupon } = issueCoupon(c.couponNo, { targetType: "ALL", quantity: all });
    expect(couponRemaining(coupon)).toBe(0);
    // 发完之后再发一张就没库存了
    expect(() => issueCoupon(c.couponNo, { targetType: "ALL", quantity: 1 })).toThrow(/超过剩余库存/);
  });

  it("已过期的券不能发放", () => {
    const expired = coupons.find((c) => couponExpired(c))!;
    expect(expired).toBeDefined();
    const before = expired.issued;
    expect(() => issueCoupon(expired.couponNo, { targetType: "ALL", quantity: 1 })).toThrow(/已于 .* 过期/);
    expect(expired.issued).toBe(before);
  });

  it("已下线（PAUSED）/ 已归档的券不能发放", () => {
    const paused = coupons.find((c) => c.status === "PAUSED")!;
    expect(() => issueCoupon(paused.couponNo, { targetType: "ALL", quantity: 1 })).toThrow(/已下线/);

    const c = issuableAt(3);
    c.archivedAt = new Date().toISOString();
    expect(() => issueCoupon(c.couponNo, { targetType: "ALL", quantity: 1 })).toThrow(/已归档/);
    c.archivedAt = null;
  });

  it("入参校验：券不存在、张数非正整数、人群非法", () => {
    const c = issuableAt(4);
    expect(() => issueCoupon("CP000", { targetType: "ALL", quantity: 1 })).toThrow(/不存在/);
    expect(() => issueCoupon(c.couponNo, { targetType: "ALL", quantity: 0 })).toThrow(/大于 0 的整数/);
    expect(() => issueCoupon(c.couponNo, { targetType: "ALL", quantity: 1.5 })).toThrow(/大于 0 的整数/);
    // 人群非法要在改库存**之前**拦住
    const before = c.issued;
    expect(() => issueCoupon(c.couponNo, { targetType: "SEGMENT", targetValue: "SEG000", quantity: 1 })).toThrow(AudienceError);
    expect(c.issued).toBe(before);
  });
});

describe("推送触达发送", () => {
  const draftAt = (n: number) => pushMessages.filter((p) => p.status === "DRAFT")[n];

  it("立即发送：DRAFT → SENT，落 sentAt / targetCount / successCount", () => {
    const p = draftAt(0);
    const expectedTarget = resolveAudience({ targetType: p.audienceType, targetValue: p.audienceValue }).size;

    const r = sendPushMessage(p.pushNo, { idempotencyKey: `K-${p.pushNo}-1` });

    expect(r.status).toBe("SENT");
    expect(r.sentAt).not.toBe("");
    expect(r.targetCount).toBe(expectedTarget);
    expect(r.successCount).toBeGreaterThan(0);
    expect(r.successCount).toBeLessThanOrEqual(r.targetCount);
    expect(r.sentCount).toBe(r.successCount);
    expect(r.idempotencyKey).toBe(`K-${p.pushNo}-1`);
  });

  it("定时发送：DRAFT → SCHEDULED，记排期时间且还没真发（successCount = 0）", () => {
    const p = draftAt(0);
    const at = new Date(Date.now() + 3600_000).toISOString();
    const r = sendPushMessage(p.pushNo, { idempotencyKey: `K-${p.pushNo}-sch`, scheduledAt: at });

    expect(r.status).toBe("SCHEDULED");
    expect(r.scheduledAt).toBe(at);
    expect(r.successCount).toBe(0);
    expect(r.sentAt).toBe("");
    // 排期后仍可真发（SCHEDULED → SENDING → SENT），换一把幂等键
    const sent = sendPushMessage(p.pushNo, { idempotencyKey: `K-${p.pushNo}-go` });
    expect(sent.status).toBe("SENT");
  });

  it("已发送的不能重发（SENT 是终态，状态机拦住）", () => {
    const sent = pushMessages.find((p) => p.status === "SENT")!;
    expect(() => sendPushMessage(sent.pushNo, { idempotencyKey: `K-${sent.pushNo}-again` }))
      .toThrow(/不允许执行「发送」/);
  });

  it("状态机：非法迁移抛错（草稿不能直接「完成发送」）", () => {
    const p = draftAt(0);
    expect(() => transitionPush(p.pushNo, "finish")).toThrow(PushError);
    expect(p.status).toBe("DRAFT");
    expect(() => transitionPush("PM000", "send")).toThrow(/不存在/);
  });

  it("幂等：必须带键，且同一把键第二次直接拒绝", () => {
    const first = draftAt(0);
    expect(() => sendPushMessage(first.pushNo, { idempotencyKey: "  " })).toThrow(/必须携带幂等键/);

    const KEY = "K-DUP-ONCE";
    const sent = sendPushMessage(first.pushNo, { idempotencyKey: KEY });
    expect(sent.status).toBe("SENT");

    // 换一条草稿、同一把键：照样拒绝（键是全局唯一的，防「换个推送号把同一批内容再发一遍」）
    const other = draftAt(0);
    expect(other.pushNo).not.toBe(first.pushNo);
    expect(() => sendPushMessage(other.pushNo, { idempotencyKey: KEY })).toThrow(/拒绝重复发送/);
    expect(other.status).toBe("DRAFT"); // 被拒的那条毫发无伤
    // 换一把新键就能正常发
    expect(sendPushMessage(other.pushNo, { idempotencyKey: "K-DUP-FRESH" }).status).toBe("SENT");
  });

  it("发送失败不烧掉幂等键（状态非法时键仍可复用）", () => {
    const sent = pushMessages.find((p) => p.status === "SENT")!;
    const KEY = "K-NOT-BURNED";
    expect(() => sendPushMessage(sent.pushNo, { idempotencyKey: KEY })).toThrow(PushError);

    const fresh = savePushMessage({ title: "键未被烧掉", content: "正文", audienceType: "ALL" });
    expect(sendPushMessage(fresh.pushNo, { idempotencyKey: KEY }).status).toBe("SENT");
  });

  it("草稿保存不能伪造发送结果（status / sentAt / 计数一律被剥离）", () => {
    const forged = savePushMessage({
      title: "伪造已发送", content: "正文", audienceType: "ALL",
      status: "SENT", sentAt: "2026-07-01T00:00:00.000Z",
      targetCount: 99999, successCount: 99999, idempotencyKey: "forged",
    });
    expect(forged.status).toBe("DRAFT");
    expect(forged.sentAt).toBe("");
    expect(forged.targetCount).toBe(0);
    expect(forged.successCount).toBe(0);
    expect(forged.idempotencyKey).toBeNull();
  });

  it("标题 / 正文为空不给发", () => {
    const empty = savePushMessage({ title: "有标题没正文", content: "", audienceType: "ALL" });
    expect(() => sendPushMessage(empty.pushNo, { idempotencyKey: "K-EMPTY" })).toThrow(/标题与内容不能为空/);
  });
});

// ============================================================================
// 活动启停（F2 补：缺启停动作）
// 要钉的不是「能启动」这种顺风路径，而是四件不许发生的事：
//  ① 已结束（终态）被复活；② 窗口已过还能启动；③ 从没启动过的被暂停；
//  ④ 编辑表单塞 status 绕过上面三道闸门。
// 另外钉住「页面按钮可用性」与「服务端拒绝」同源：campaignActions 说不行的，
// transitionCampaign 必须抛；说行的，必须不抛。两边分叉就是「按钮亮着点了报错」。
// ============================================================================
const ALL_ACTIONS = Object.keys(CAMPAIGN_TRANSITIONS) as CampaignAction[];
/** 种子活动号在**任何夹具创建之前**取快照——否则下面的种子覆盖断言会把夹具算进去。 */
const SEED_NOS = campaigns.map((c) => c.campaignNo);
const days = (d: number) => new Date(Date.now() + d * 86400_000).toISOString();
/** 造一条新活动（一律 DRAFT），endDays < 0 即窗口已过。 */
const fresh = (name: string, endDays = 30) =>
  saveCampaign({ name, kind: "满减", rule: "满10减3", startAt: days(-1), endAt: days(endDays) });
/** 构造指定状态：直接写字段是**测试夹具**手段，只为凑出四种起始态，被测的是 transitionCampaign。 */
const at = (status: CampaignStatus, endDays = 30) => {
  const c = fresh(`夹具-${status}-${endDays}`, endDays);
  c.status = status;
  return c;
};

describe("活动启停", () => {
  it("新建一律落 DRAFT，且表单塞 status 无效（状态归状态机独占）", () => {
    const c = saveCampaign({ name: "伪造进行中", kind: "满减", rule: "x", startAt: days(-1), endAt: days(30), status: "RUNNING" });
    expect(c.status).toBe("DRAFT");
    // 编辑同样剥离：已结束的活动不能靠改字段复活
    const ended = at("ENDED");
    saveCampaign({ campaignNo: ended.campaignNo, name: "改个名", status: "RUNNING" });
    expect(campaigns.find((x) => x.campaignNo === ended.campaignNo)!.status).toBe("ENDED");
  });

  it("启动 / 暂停可来回：DRAFT → RUNNING → PAUSED → RUNNING（PAUSED 不是终态）", () => {
    const c = fresh("正常启停");
    expect(transitionCampaign(c.campaignNo, "start").status).toBe("RUNNING");
    expect(transitionCampaign(c.campaignNo, "pause").status).toBe("PAUSED");
    expect(transitionCampaign(c.campaignNo, "start").status).toBe("RUNNING");
  });

  it("已结束是终态：不给任何动作按钮，三个动作全部拒绝", () => {
    const c = at("ENDED");
    expect(campaignActions(c)).toEqual([]);
    for (const a of ALL_ACTIONS) {
      expect(() => transitionCampaign(c.campaignNo, a)).toThrow(CampaignError);
      expect(() => transitionCampaign(c.campaignNo, a)).toThrow(/不允许执行/);
    }
    expect(c.status).toBe("ENDED"); // 被拒的行毫发无伤
  });

  it("窗口已过不可启动，但仍可暂停 / 结束（否则过期的进行中活动收不了尾）", () => {
    const draft = at("DRAFT", -3);
    expect(campaignWindowPassed(draft)).toBe(true);
    expect(campaignActions(draft)).toEqual([]); // DRAFT 只有 start 一条边，窗口过了就没按钮
    expect(() => transitionCampaign(draft.campaignNo, "start")).toThrow(/已过，不可启动/);
    expect(draft.status).toBe("DRAFT");

    const paused = at("PAUSED", -5);
    expect(() => transitionCampaign(paused.campaignNo, "start")).toThrow(/已过，不可启动/);
    expect(campaignActions(paused)).toEqual(["end"]); // 收尾必须留着
    expect(transitionCampaign(paused.campaignNo, "end").status).toBe("ENDED");

    const running = at("RUNNING", -7);
    expect(campaignActions(running)).toEqual(["pause", "end"]);
    expect(transitionCampaign(running.campaignNo, "pause").status).toBe("PAUSED");
  });

  it("没启动过的（DRAFT）不能暂停，也不能直接结束", () => {
    const c = fresh("草稿不能暂停");
    expect(() => transitionCampaign(c.campaignNo, "pause")).toThrow(/当前状态「DRAFT」不允许执行「暂停」/);
    expect(() => transitionCampaign(c.campaignNo, "end")).toThrow(/不允许执行「结束」/);
    expect(c.status).toBe("DRAFT");
  });

  it("活动不存在直接抛错", () => {
    expect(() => transitionCampaign("CMP000", "start")).toThrow(/不存在/);
  });

  it("页面按钮可用性与服务端拒绝同源（四种状态 × 窗口内/外，逐个对照）", () => {
    const statuses: CampaignStatus[] = ["DRAFT", "RUNNING", "PAUSED", "ENDED"];
    for (const s of statuses) {
      for (const endDays of [30, -3]) {
        for (const a of ALL_ACTIONS) {
          const row = at(s, endDays);
          const allowed = campaignActions(row).includes(a);
          if (allowed) {
            expect(transitionCampaign(row.campaignNo, a).status, `${s}/${endDays}/${a}`)
              .toBe(CAMPAIGN_TRANSITIONS[a].to);
          } else {
            expect(() => transitionCampaign(row.campaignNo, a), `${s}/${endDays}/${a}`).toThrow(CampaignError);
            expect(row.status, `${s}/${endDays}/${a}`).toBe(s);
          }
        }
      }
    }
  });

  it("状态筛选能把暂停的活动单独捞出来", () => {
    const c = fresh("待暂停");
    transitionCampaign(c.campaignNo, "start");
    transitionCampaign(c.campaignNo, "pause");
    const paused = listCampaigns({ status: "PAUSED", size: 200 }).list;
    expect(paused.length).toBeGreaterThan(0);
    expect(paused.every((x: Campaign) => x.status === "PAUSED")).toBe(true);
    expect(paused.some((x: Campaign) => x.campaignNo === c.campaignNo)).toBe(true);
  });

  it("种子数据覆盖到四种状态与「窗口已过」（否则这几条闸门在页面上根本演示不出来）", () => {
    const seeds = campaigns.filter((x) => SEED_NOS.includes(x.campaignNo));
    for (const s of ["DRAFT", "RUNNING", "PAUSED", "ENDED"] as CampaignStatus[]) {
      expect(seeds.some((x) => x.status === s), s).toBe(true);
    }
    expect(seeds.some((x) => campaignWindowPassed(x))).toBe(true);
  });
});

// —— 广告投放动作（S9）——
// 动作挂在**广告活动**上而不是「投放与曝光」：后者是按天回传的曝光事实行，没有生命周期。
// 与营销活动的状态机刻意分开（广告要对广告主结算，暂停即停止计费）。
describe("广告投放动作", () => {
  const anyDraft = () => adCampaigns.find((a) => a.status === "DRAFT" && !adWindowPassed(a))!;

  it("上线：DRAFT → RUNNING", () => {
    const a = anyDraft();
    expect(transitionAdCampaign(a.adNo, "launch").status).toBe("RUNNING");
  });

  it("非法迁移被拒：ENDED 是终态，三个动作都不行", () => {
    const a = adCampaigns.find((x) => x.status === "DRAFT")!;
    transitionAdCampaign(a.adNo, "stop");
    for (const k of ["launch", "pause", "stop"] as const) {
      expect(() => transitionAdCampaign(a.adNo, k)).toThrow();
    }
    expect(adCampaignActions(a)).toEqual([]);   // 终态不出按钮
  });

  it("窗口已过只拦「上线」，不拦「下线」—— 否则过期的 RUNNING 广告永远收不了尾", () => {
    const past = adCampaigns.find((a) => adWindowPassed(a));
    if (!past) return;
    expect(adActionAllowed(past, "launch")).toBe(false);
    if (past.status === "RUNNING") expect(adActionAllowed(past, "stop")).toBe(true);
  });

  it("按钮集合与 mock 校验同源：adCampaignActions 允许的动作必然不抛", () => {
    for (const a of adCampaigns) {
      for (const k of adCampaignActions(a)) {
        const before = a.status;
        expect(() => transitionAdCampaign(a.adNo, k)).not.toThrow();
        a.status = before;   // 还原，避免污染后续用例
      }
    }
  });
});

// —— 邀请奖励规则（S9）——
// 这几条错了都是真金白银，故校验必须在 mock 层（表单能被绕过）。
describe("邀请奖励规则", () => {
  const base = () => ({
    name: "测试规则", rewardTo: "BOTH" as const, rewardAmount: 5, currency: "AED",
    trigger: "FIRST_ORDER" as const, maxPerInviter: 3,
    startAt: "2027-01-01T00:00:00Z", endAt: "2027-02-01T00:00:00Z", status: "DISABLED" as const,
  });

  it("奖励金额必须 > 0（0 元规则等于挂着一个永不发奖的活动）", () => {
    expect(() => saveReferralRule({ ...base(), rewardAmount: 0 })).toThrow(/大于 0/);
    expect(() => saveReferralRule({ ...base(), rewardAmount: -1 })).toThrow();
  });

  it("结束必须晚于开始（窗口为空的规则永不生效，但状态会显示生效）", () => {
    expect(() => saveReferralRule({ ...base(), startAt: "2027-03-01T00:00:00Z", endAt: "2027-02-01T00:00:00Z" }))
      .toThrow(/晚于/);
  });

  it("每人上限不能为负（0 是「不限」的约定值，不是非法值）", () => {
    expect(() => saveReferralRule({ ...base(), maxPerInviter: -1 })).toThrow();
    expect(() => saveReferralRule({ ...base(), maxPerInviter: 0 })).not.toThrow();
  });

  it("同一时间窗只允许一条 ACTIVE —— 重叠时一次邀请该发几笔无法回答", () => {
    // RR001 已是 ACTIVE 且覆盖 2026 下半年
    expect(() => saveReferralRule({
      ...base(), status: "ACTIVE", startAt: "2026-08-01T00:00:00Z", endAt: "2026-09-01T00:00:00Z",
    })).toThrow(/重叠/);
  });

  it("窗口不重叠的 ACTIVE 规则可以存", () => {
    const r = saveReferralRule({ ...base(), status: "ACTIVE", startAt: "2028-01-01T00:00:00Z", endAt: "2028-02-01T00:00:00Z" });
    expect(r.ruleNo).toMatch(/^RR/);
    expect(r.status).toBe("ACTIVE");
  });
});
