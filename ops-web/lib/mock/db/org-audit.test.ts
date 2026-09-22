// 操作审计详情（改动前后对比）的 mock 测试。
//
// 关注点只有一个：详情不许「为了有内容而编内容」。审计是用来复盘的，
// 给一条纯指令动作（远程弹出）硬编两行假 diff，比不给更糟——复盘时会照着假 diff 找原因。
import { describe, expect, it } from "vitest";
import { audits, getAuditDetail } from "./org";

describe("getAuditDetail", () => {
  it("每条列表记录都能取到详情（列表点进去不许 404）", () => {
    for (const a of audits) expect(getAuditDetail(a.id).id).toBe(a.id);
  });

  it("详情包含列表的全部字段（抽屉里不用再回头看列表）", () => {
    const a = audits[0];
    expect(getAuditDetail(a.id)).toMatchObject({
      id: a.id, actor: a.actor, action: a.action, target: a.target, detail: a.detail, ip: a.ip, createdAt: a.createdAt,
    });
  });

  it("id 不存在 → 抛错，不返回空壳详情", () => {
    expect(() => getAuditDetail("A0")).toThrow(/不存在/);
  });

  it("requestId 与 userAgent 非空（链路追踪要靠它串后端日志）", () => {
    const d = getAuditDetail(audits[0].id);
    expect(d.requestId).toBeTruthy();
    expect(d.userAgent).toBeTruthy();
  });

  it("纯动作（设备远程弹出）没有字段级改动 —— 空数组而非编造 diff", () => {
    const a = audits.find((x) => x.action === "设备远程弹出")!;
    expect(getAuditDetail(a.id).changes).toEqual([]);
  });

  it("写操作有 diff，且 before/after 一定不同（相同就不该留痕成「改动」）", () => {
    const a = audits.find((x) => x.action === "订单退款")!;
    const { changes } = getAuditDetail(a.id);
    expect(changes.length).toBeGreaterThan(0);
    for (const c of changes) {
      expect(c.field).toBeTruthy();
      expect(c.before).not.toBe(c.after);
    }
  });

  it("新增类记录的 before 用「—」而非空串（空串是「被清空」的真实语义）", () => {
    const a = audits.find((x) => x.action === "员工新增")!;
    expect(getAuditDetail(a.id).changes.every((c) => c.before === "—")).toBe(true);
  });

  it("返回的是副本，改它改不到 mock 数据源", () => {
    const a = audits.find((x) => x.action === "工单派单")!;
    getAuditDetail(a.id).changes[0].after = "篡改";
    expect(getAuditDetail(a.id).changes[0].after).not.toBe("篡改");
  });
});
