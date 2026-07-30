// 营销 S2：优惠券发放 + 推送触达发送 的守卫测试。
//
// 这两个动作的权限码（marketing:coupon:issue / marketing:push:send）早就在 RBAC 里定义，
// 页面却从没用过。补上写操作后，四件事必须由服务端（本 db 层）兜住，不能指望页面自觉：
//  ① 发放真的改库存并留痕（不是只弹个 toast）；② 超发 / 已下线 / 已过期一律拒绝；
//  ③ 推送状态机强制，已发送是终态；④ 发送**必须带幂等键**，同键第二次拒绝。
import { describe, expect, it } from "vitest";
import {
  coupons, couponIssueRecords, issueCoupon, listCouponIssueRecords, CouponIssueError,
  pushMessages, savePushMessage, sendPushMessage, transitionPush, PushError,
  resolveAudience, AudienceError,
} from "./marketing";
import { couponRemaining, couponExpired } from "../../types";
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
