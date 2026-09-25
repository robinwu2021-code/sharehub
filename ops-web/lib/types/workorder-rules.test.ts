import { describe, it, expect } from "vitest";
import { woSlaRemain, woTakeoverBlocked, woDeriveBlocked, woCompleteRules, fmtMinutes } from "./workorder";
import type { WorkOrder } from "./workorder";

/** 页面按钮与 mock 校验共用的工单规则（同 inspectionRunnable 的做法：按钮亮着点了必然报错的情况不许出现）。 */
const wo = (p: Partial<WorkOrder>): WorkOrder => ({
  woNo: "WO1", type: "FAULT", source: "MANUAL", priority: "HIGH", cabinetNo: "C1", status: "PROCESSING",
  assigneeName: "AG001", slaDueAt: null, description: "", createdAt: "", ...p,
} as WorkOrder);
const NOW = Date.parse("2026-09-25T12:00:00");

describe("SLA 剩余", () => {
  it("★ 优先用后端算好的 ops.slaRemainMinutes（服务端时钟）", () => {
    expect(woSlaRemain(wo({ slaDueAt: "2026-09-25 13:00:00", ops: { slaRemainMinutes: -5 } as WorkOrder["ops"] }), NOW)).toBe(-5);
  });
  it("★ 后端 slaDueAt 是空格分隔的本地时间串 —— 补 T 再解析，不然 Safari 下是 NaN", () => {
    expect(woSlaRemain(wo({ slaDueAt: "2026-09-25 13:00:00" }), NOW)).toBe(60);
    expect(woSlaRemain(wo({ slaDueAt: "2026-09-25T10:00:00" }), NOW)).toBe(-120);
  });
  it("已完工 / 无时限 → null（不显示倒计时）", () => {
    expect(woSlaRemain(wo({ status: "DONE", slaDueAt: "2026-09-25 13:00:00" }), NOW)).toBeNull();
    expect(woSlaRemain(wo({ slaDueAt: null }), NOW)).toBeNull();
  });
});

describe("接管 / 派生的可用性", () => {
  const agent = { assigneeType: "AGENT" } as WorkOrder["ops"];
  it("★ 代理 + 已派出 + 超时 才能接管；未超时要说剩多久", () => {
    expect(woTakeoverBlocked(wo({ ops: { ...agent!, slaRemainMinutes: -10 } }), NOW)).toBeNull();
    expect(woTakeoverBlocked(wo({ ops: { ...agent!, slaRemainMinutes: 90 } }), NOW)).toMatch(/剩 1 小时 30 分/);
    expect(woTakeoverBlocked(wo({ ops: { ...agent!, assigneeType: "EMPLOYEE", slaRemainMinutes: -10 } }), NOW)).toMatch(/代理/);
    expect(woTakeoverBlocked(wo({ status: "CREATED", ops: { ...agent!, slaRemainMinutes: -10 } }), NOW)).toMatch(/已派单/);
  });
  it("只有到场的巡检单能派生", () => {
    expect(woDeriveBlocked(wo({ type: "INSPECT", status: "ACCEPTED" }))).toBeNull();
    expect(woDeriveBlocked(wo({ type: "INSPECT", status: "DISPATCHED" }))).toMatch(/到场/);
    expect(woDeriveBlocked(wo({ type: "FAULT" }))).toMatch(/巡检单/);
  });
});

describe("完工必填（与后端 complete 同一套）", () => {
  it("维修：照片 + 故障原因；撤机：照片 + 清点数；装机：照片；补宝 / 巡检：都不强制", () => {
    expect(woCompleteRules("FAULT")).toMatchObject({ photos: true, faultReason: true, countedQty: false });
    expect(woCompleteRules("REMOVE")).toMatchObject({ photos: true, faultReason: false, countedQty: true });
    expect(woCompleteRules("INSTALL")).toMatchObject({ photos: true, locationNo: true });
    expect(woCompleteRules("REFILL")).toMatchObject({ photos: false, faultReason: false, countedQty: false });
    expect(woCompleteRules("INSPECT").photos).toBe(false);
  });
  it("分钟的人读格式", () => {
    expect(fmtMinutes(-125)).toBe("2 小时 5 分");
    expect(fmtMinutes(1500)).toBe("1 天 1 小时");
    expect(fmtMinutes(40)).toBe("40 分钟");
  });
});
