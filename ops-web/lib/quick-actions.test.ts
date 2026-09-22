import { describe, it, expect } from "vitest";
import {
  findCabinet, parseSlotInput, ejectCommandType, ejectPrecheck, ejectConfirmDesc, orderKeywordReady,
} from "@/components/quick-actions-logic";
import type { Cabinet } from "@/lib/types";

const cab = (over: Partial<Cabinet> = {}): Cabinet => ({
  cabinetNo: "CB0001", sn: "SN1", vendorCode: "UJ", model: "M8",
  locationNo: "L1", locationName: "中心广场", siteNo: "S1", agentNo: null,
  slotTotal: 8, availableCount: 3, onlineStatus: "ONLINE", status: "DEPLOYED",
  fwVersion: "1.0.0", lastHeartbeatAt: null, archivedAt: null,
  ...over,
});

describe("快捷动作 · 目标校验", () => {
  it("柜号必须精确命中台账，不做宽容匹配", () => {
    const list = [cab(), cab({ cabinetNo: "CB0002" })];
    expect(findCabinet(list, "CB0002")?.cabinetNo).toBe("CB0002");
    expect(findCabinet(list, "cb0002")).toBeNull();
    expect(findCabinet(list, " CB0002 ")).toBeNull();
    expect(findCabinet(list, "CB9999")).toBeNull();
  });

  it("选不到机柜就不许下发（工作台无行上下文时的默认态）", () => {
    expect(ejectPrecheck(null)).toEqual({ ok: false, reason: "请先从机柜列表中选择目标机柜" });
  });
});

describe("快捷动作 · 仓位输入", () => {
  it("留空 = 任意仓位；非正整数一律判非法", () => {
    expect(parseSlotInput("")).toEqual({ bad: false });
    expect(parseSlotInput("   ")).toEqual({ bad: false });
    expect(parseSlotInput("3")).toEqual({ slotIndex: 3, bad: false });
    expect(parseSlotInput("0").bad).toBe(true);
    expect(parseSlotInput("-1").bad).toBe(true);
    expect(parseSlotInput("2.5").bad).toBe(true);
    expect(parseSlotInput("abc").bad).toBe(true);
  });

  it("指令名随「是否指定仓位」切换", () => {
    expect(ejectCommandType()).toBe("EJECT_ANY");
    expect(ejectCommandType(2)).toBe("EJECT");
  });
});

describe("快捷动作 · 弹出前置校验", () => {
  it("仓位越界按台账格数拦下", () => {
    const r = ejectPrecheck(cab({ slotTotal: 8 }), 9);
    expect(r.ok).toBe(false);
    expect(r.ok === false && r.reason).toContain("共 8 个仓位");
  });

  it("台账没给格数时只校验正整数，不猜上限", () => {
    expect(ejectPrecheck(cab({ slotTotal: 0 }), 99).ok).toBe(true);
  });

  it("非法仓位输入优先于其它拦截项报出", () => {
    const r = ejectPrecheck(cab(), undefined, true);
    expect(r.ok === false && r.reason).toContain("整数");
  });

  it("退役机柜不下发", () => {
    expect(ejectPrecheck(cab({ status: "RETIRED" })).ok).toBe(false);
  });

  it("任意仓位弹出要求仓内有宝，指定仓位则放行（开空仓是合法运维动作）", () => {
    expect(ejectPrecheck(cab({ availableCount: 0 })).ok).toBe(false);
    expect(ejectPrecheck(cab({ availableCount: 0 }), 2).ok).toBe(true);
  });

  it("离线只警告不拦截：指令排队等上线", () => {
    const r = ejectPrecheck(cab({ onlineStatus: "OFFLINE" }));
    expect(r.ok).toBe(true);
    expect(r.ok === true && r.warn).toContain("离线");
  });
});

describe("快捷动作 · 确认文案与订单搜索", () => {
  it("确认文案点明机柜、仓位与离线后果", () => {
    const d = ejectConfirmDesc(cab(), 3, "该机柜当前离线，指令会排队等待上线后执行");
    expect(d).toContain("CB0001");
    expect(d).toContain("第 3 仓");
    expect(d).toContain("排队");
    expect(ejectConfirmDesc(cab(), undefined)).toContain("任意可用仓位");
  });

  it("未上架机柜的点位显示「未上架」而不是 null", () => {
    expect(ejectConfirmDesc(cab({ locationName: null }), 1)).toContain("未上架");
  });

  it("订单关键词不足 2 字符不发请求（避免整表回捞）", () => {
    expect(orderKeywordReady(" a ")).toBe(false);
    expect(orderKeywordReady("")).toBe(false);
    expect(orderKeywordReady("RO")).toBe(true);
  });
});
