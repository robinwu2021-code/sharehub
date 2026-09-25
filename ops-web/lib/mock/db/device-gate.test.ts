import { describe, it, expect, beforeEach } from "vitest";
import * as dg from "./device-gate";
import { cabinets } from "./device";
import { sites } from "./location";
import type { Cabinet } from "../../types";

/**
 * 设备上线门禁 · 试借还 · 保护。
 *
 * <p>门禁里最硬的一关是**试借还**：只查配置不试一次的话，
 * 第一个真实用户就是试验品，而那时现场已经没人了。
 * 所以这里专门验「配置全齐但没试过 → 仍然不给上线」。
 */

let no: string;
let siteNo: string;

beforeEach(() => {
  const n = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
  siteNo = `ST-D${n}`;
  no = `CAB-T${n}`;
  sites.unshift({
    siteNo, name: "门禁测试站", venueName: "v", address: "x", regionId: "R1",
    lng: 55, lat: 25, sceneType: "商场", pointCount: 0, cabinetCount: 0,
    status: "PREPARING", archivedAt: null,
  } as unknown as never);
  cabinets.unshift({
    cabinetNo: no, siteNo, status: "IN_STOCK", slotTotal: 8, vendorCode: "cd-tech",
  } as unknown as Cabinet);
});

const cur = () => cabinets.find((c) => c.cabinetNo === no)!;

describe("上线门禁", () => {
  it("★ 配置全齐但没做过试借还 → 仍然不给上线（门禁的核心就是这一条）", () => {
    const gate = dg.goLiveGate(no);
    const trial = gate.items.find((i) => i.key === "trial")!;
    expect(trial.passed).toBe(false);
    expect(gate.allPassed).toBe(false);
    expect(() => dg.goLive(no)).toThrowError(/还不能上线|Cannot go live/);
  });

  it("★ 每条未通过都要给去处——只说「缺什么」而不给链接，门禁就成了拦路虎", () => {
    for (const it2 of dg.goLiveGate(no).items) {
      if (it2.passed) continue;
      expect(it2.fixHref, `${it2.key} 未通过却没有 fixHref`).toBeTruthy();
      expect(it2.detail, `${it2.key} 未通过却没说为什么`).toBeTruthy();
    }
  });

  it("试借还通过后门禁全绿，可以上线", () => {
    const t = dg.startTrialRent(no);
    dg.finishTrialRent(t.trialNo, true);
    const gate = dg.goLiveGate(no);
    expect(gate.allPassed, gate.items.filter((i) => !i.passed).map((i) => i.label).join("、")).toBe(true);
    expect(dg.goLive(no).status).toBe("DEPLOYED");
  });

  it("★ 挂着停租保护时不给上线——否则一上线就是个不能借的柜子", () => {
    const t = dg.startTrialRent(no);
    dg.finishTrialRent(t.trialNo, true);
    dg.applyProtection(no, { action: "STOP_RENT", reason: "等待验收" });
    expect(dg.goLiveGate(no).allPassed).toBe(false);
    expect(() => dg.goLive(no)).toThrow();
  });

  it("站点状态不可营业时不给上线", () => {
    sites.find((s) => s.siteNo === siteNo)!.status = "CLOSED";
    const site = dg.goLiveGate(no).items.find((i) => i.key === "site")!;
    expect(site.passed).toBe(false);
    expect(site.detail).toContain("CLOSED");
  });
});

describe("试借还", () => {
  it("★ mock 不直接给 PASSED——直接给的话门禁在离线开发时永远绿，那一关等于不存在", () => {
    expect(dg.startTrialRent(no).status).toBe("WAIT_RETURN");
  });

  it("同一台柜不能同时挂两次未结束的试借还", () => {
    dg.startTrialRent(no);
    expect(() => dg.startTrialRent(no)).toThrowError(/还有一次|in progress/);
  });

  it("失败要记原因", () => {
    const t = dg.startTrialRent(no);
    const done = dg.finishTrialRent(t.trialNo, false, "宝没弹出来");
    expect(done.status).toBe("FAILED");
    expect(done.failReason).toBe("宝没弹出来");
  });
});

describe("保护", () => {
  it("保护原因必填——没有原因的保护没人敢解", () => {
    expect(() => dg.applyProtection(no, { action: "STOP_RENT", reason: "  " })).toThrowError(/原因|Reason/i);
  });

  it("★ 只能解人工挂的；信号/告警挂的手工解不掉", () => {
    dg.markDeviceFault(no, "主板故障");   // 内部挂一条 MANUAL/holderRef=fault 的停租
    const sys = dg.listProtections(no).find((p) => p.holderRef === "fault")!;
    // 把它改成告警持有，模拟系统挂的那种
    (sys as { holderType: string }).holderType = "ALARM";
    expect(() => dg.releaseProtection(sys.protectionNo))
      .toThrowError(/告警|设备信号|MANUAL/);
  });

  it("人工挂的能解，解完不再生效", () => {
    const p = dg.applyProtection(no, { action: "SLOT_DISABLE", slotIndex: 3, reason: "卡槽异响" });
    expect(dg.listProtections(no).some((x) => x.protectionNo === p.protectionNo)).toBe(true);
    dg.releaseProtection(p.protectionNo, "已处理");
    expect(dg.listProtections(no).some((x) => x.protectionNo === p.protectionNo)).toBe(false);
    expect(dg.listProtections(no, false).find((x) => x.protectionNo === p.protectionNo)?.releaseReason).toBe("已处理");
  });

  it("已解除的不能再解一次", () => {
    const p = dg.applyProtection(no, { action: "DERATE", reason: "降额观察" });
    dg.releaseProtection(p.protectionNo);
    expect(() => dg.releaseProtection(p.protectionNo)).toThrowError(/已解除|Already released/);
  });

  it("★ 标故障会顺带挂停租——不挂的话，故障柜仍然接客", () => {
    dg.markDeviceFault(no, "主板故障");
    expect(dg.listProtections(no).some((p) => p.action === "STOP_RENT")).toBe(true);
    // 修复后自动撤掉
    dg.repairDevice(no);
    expect(dg.listProtections(no).some((p) => p.holderRef === "fault")).toBe(false);
  });
});

describe("生命周期", () => {
  it("在用设备不能直接报废——先撤机", () => {
    const t = dg.startTrialRent(no);
    dg.finishTrialRent(t.trialNo, true);
    dg.goLive(no);
    expect(() => dg.retireDevice(no)).toThrowError(/先撤机|Undeploy/);
    dg.undeployDevice(no, "撤场");
    expect(cur().status).toBe("IN_STOCK");
    expect(dg.retireDevice(no).status).toBe("RETIRED");
  });

  it("撤机会解掉该柜所有保护——否则回库后还挂着停租，下次上线门禁会莫名不过", () => {
    // 先真上线：undeploy 只接受 DEPLOYED/FAULT，直接对在库设备调是非法的
    const t = dg.startTrialRent(no);
    dg.finishTrialRent(t.trialNo, true);
    dg.goLive(no);
    dg.applyProtection(no, { action: "STOP_RENT", reason: "x" });
    dg.undeployDevice(no);
    expect(dg.listProtections(no)).toEqual([]);
  });
});

describe("信号码字典", () => {
  it("★ 信号不是告警——每条信号要么自带保护动作，要么是成对的恢复信号", () => {
    const codes = dg.listSignalCodes();
    expect(codes.length).toBeGreaterThan(0);
    for (const c of codes) {
      const isRecovery = !c.protectiveAction;
      // 触发型必须指明「谁能清掉它」，否则挂上的保护永远撤不掉
      if (!isRecovery) expect(c.clearsCode, `${c.code} 没有恢复信号`).toBeTruthy();
    }
    // 恢复信号确实存在，不是写了个不存在的码
    const known = new Set(codes.map((c) => c.code));
    for (const c of codes) if (c.clearsCode) expect(known.has(c.clearsCode), `${c.clearsCode} 不存在`).toBe(true);
  });
});
