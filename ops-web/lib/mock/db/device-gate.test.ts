import { describe, it, expect, beforeEach } from "vitest";
import * as dg from "./device-gate";
import { cabinets } from "./device";
import { contracts, locations, sites } from "./location";
import { workOrders } from "./workorder";
import { setQcStatus } from "./device-asset";
import { CABINET_TRANSITIONS, canCabinetAction } from "../../types";
import type { Cabinet, CabinetStatus, Contract, WorkOrder } from "../../types";

/**
 * 设备上线门禁 · 状态动作 · 试借还 · 保护（mock 照后端 CabinetLifecycleServiceImpl 等逐条对齐）。
 *
 * <p>门禁里最硬的一关是**试借还**：只查配置不试一次的话，
 * 第一个真实用户就是试验品，而那时现场已经没人了。
 * 所以这里专门验「其它九项全齐但没试过 → 仍然不给上线」。
 */

let no: string;
let siteNo: string;

/** 造一台「除试借还外门禁全过」的在库柜：真点位、营业中站点、生效合同、完工装机单、在线、装宝 5/8。 */
beforeEach(() => {
  const n = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
  const loc = locations.find((l) => !l.archivedAt)!;
  siteNo = loc.siteNo;
  sites.find((s) => s.siteNo === siteNo)!.status = "ACTIVE";
  if (!contracts.some((k) => k.siteNo === siteNo && k.status === "ACTIVE")) {
    contracts.unshift({ contractNo: `CT-T${n}`, siteNo, status: "ACTIVE" } as unknown as Contract);
  }
  no = `CAB-T${n}`;
  cabinets.unshift({
    cabinetNo: no, sn: `SN-T${n}`, siteNo, locationNo: loc.locationNo, locationName: loc.siteName,
    status: "IN_STOCK", slotTotal: 8, availableCount: 5, vendorCode: "cd-tech", model: "X6",
    onlineStatus: "ONLINE", lastHeartbeatAt: new Date().toISOString(), agentNo: null, fwVersion: "1.4.0", archivedAt: null,
  } as Cabinet);
  workOrders.unshift({ woNo: `WO-T${n}`, type: "INSTALL", cabinetNo: no, status: "DONE" } as unknown as WorkOrder);
});

const cur = () => cabinets.find((c) => c.cabinetNo === no)!;
const item = (key: string) => dg.goLiveGate(no).items.find((i) => i.key === key)!;
const passTrial = () => dg.finishTrialRent(dg.startTrialRent(no).trialNo, true);

describe("上线门禁", () => {
  it("门禁项与后端同 key、同顺序——前端按 key 翻译与定位，两套 key 就是两套门禁", () => {
    expect(dg.goLiveGate(no).items.map((i) => i.key)).toEqual(
      ["QC", "SURVEY", "INSTALL_WO", "LOAD", "LOCATION", "SITE_OPEN", "CONTRACT", "ONLINE", "TRIAL", "PRICE"]);
  });

  it("★ 其余全齐但没做过试借还 → 仍然不给上线（门禁的核心就是这一条）", () => {
    const gate = dg.goLiveGate(no);
    expect(gate.items.filter((i) => !i.passed).map((i) => i.key)).toEqual(["TRIAL"]);
    expect(gate.allPassed).toBe(false);
    expect(() => dg.goLive(no)).toThrowError(/还不能上线|Cannot go live/);
    expect(cur().status).toBe("IN_STOCK");
  });

  it("★ 每条未通过都要给去处，且是运营端真实存在的路由（不是后端那种 /devices/{no}）", () => {
    cur().onlineStatus = "OFFLINE";
    cur().availableCount = 0;
    for (const it2 of dg.goLiveGate(no).items) {
      expect(it2.fixHref, `${it2.key} 没有 fixHref`).toBeTruthy();
      expect(it2.fixHref!, `${it2.key}`).not.toMatch(/^\/devices\/(?!detail\?)/);
      expect(it2.fixHref!, `${it2.key}`).not.toMatch(/^\/sites\//);
      if (!it2.passed) expect(it2.detail, `${it2.key} 未通过却没说为什么`).toBeTruthy();
    }
  });

  it("试借还通过后门禁全绿，可以上线", () => {
    passTrial();
    const gate = dg.goLiveGate(no);
    expect(gate.allPassed, gate.items.filter((i) => !i.passed).map((i) => i.label).join("、")).toBe(true);
    expect(dg.goLive(no).status).toBe("DEPLOYED");
  });

  it("质检未过 / 装宝比例越界 / 离线 / 站点关闭，各自卡住且说清原因", () => {
    setQcStatus(no, "FAILED");
    expect(item("QC")).toMatchObject({ passed: false, detail: "质检不通过" });
    cur().availableCount = 8;
    expect(item("LOAD").passed).toBe(false);
    expect(item("LOAD").detail).toContain("50%–80%");
    cur().lastHeartbeatAt = new Date(Date.now() - 10 * 60_000).toISOString();
    expect(item("ONLINE").passed).toBe(false);
    sites.find((s) => s.siteNo === siteNo)!.status = "CLOSED";
    expect(item("SITE_OPEN").detail).toContain("CLOSED");
    sites.find((s) => s.siteNo === siteNo)!.status = "ACTIVE";
  });

  it("存量设备（没有质检状态）免检放行", () => {
    expect(item("QC")).toMatchObject({ passed: true, detail: "存量设备免检" });
  });

  it("非法迁移优先于门禁：在用的柜子点上线报的是「不能上线」而不是缺哪几项", () => {
    passTrial();
    dg.goLive(no);
    expect(() => dg.goLive(no)).toThrowError(/当前是「DEPLOYED」|Illegal transition/);
  });
});

describe("机柜状态机（与后端 CabinetStateMachine 逐边一致）", () => {
  it("迁移表就是后端那七条边", () => {
    const edges = Object.values(CABINET_TRANSITIONS).flatMap((t) => t.from.map((f) => `${f}->${t.to}`)).sort();
    expect(edges).toEqual([
      "DEPLOYED->FAULT", "DEPLOYED->IN_STOCK", "FAULT->DEPLOYED", "FAULT->IN_STOCK",
      "FAULT->RETIRED", "IN_STOCK->DEPLOYED", "IN_STOCK->RETIRED",
    ]);
  });

  it("运输中的柜子一个人工动作都没有——它由调拨单的签收拉回在库", () => {
    const s: CabinetStatus = "IN_TRANSIT";
    expect((Object.keys(CABINET_TRANSITIONS) as (keyof typeof CABINET_TRANSITIONS)[]).filter((a) => canCabinetAction(s, a))).toEqual([]);
  });

  it("标故障 / 撤机 / 报废原因必填", () => {
    passTrial();
    dg.goLive(no);
    expect(() => dg.markDeviceFault(no, " ")).toThrowError(/原因必填|Reason/);
    expect(cur().status).toBe("DEPLOYED");
    expect(() => dg.undeployDevice(no)).toThrowError(/原因必填|Reason/);
  });

  it("标故障 → 修复 → 撤机 → 报废，一路合法", () => {
    passTrial();
    dg.goLive(no);
    expect(dg.markDeviceFault(no, "主板故障").status).toBe("FAULT");
    expect(dg.repairDevice(no).status).toBe("DEPLOYED");
    expect(dg.undeployDevice(no, "撤场").status).toBe("IN_STOCK");
    expect(dg.retireDevice(no, "进水报废").status).toBe("RETIRED");
  });

  it("★ 在用设备不能直接报废——先撤机", () => {
    passTrial();
    dg.goLive(no);
    expect(() => dg.retireDevice(no, "x")).toThrowError(/不能报废|Illegal/);
    expect(cur().status).toBe("DEPLOYED");
  });

  it("在库设备不能标故障（后端只有 DEPLOYED→FAULT 这条边）", () => {
    expect(() => dg.markDeviceFault(no, "x")).toThrowError(/不能标记故障|Illegal/);
  });

  it("撤机解绑点位与站点，并让之前的试借还失效——换了点位要重做", () => {
    passTrial();
    dg.goLive(no);
    dg.undeployDevice(no, "换点");
    expect(cur()).toMatchObject({ locationNo: null, siteNo: null, status: "IN_STOCK" });
    const gate = dg.goLiveGate(no);
    expect(gate.items.find((i) => i.key === "TRIAL")!.passed).toBe(false);
    expect(gate.items.find((i) => i.key === "LOCATION")!.passed).toBe(false);
  });

  it("首台上线把筹备中的站点转营业", () => {
    const site = sites.find((s) => s.siteNo === siteNo)!;
    site.status = "PREPARING";
    (site as { surveyPassed?: boolean }).surveyPassed = true;
    passTrial();
    dg.goLive(no);
    expect(site.status).toBe("ACTIVE");
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

  it("离线不能试借还；被禁用 / 锁定的仓位里的宝不会被挑去弹", () => {
    cur().onlineStatus = "OFFLINE";
    expect(() => dg.startTrialRent(no)).toThrowError(/离线|offline/i);
    cur().onlineStatus = "ONLINE";
    for (let s = 1; s <= 5; s++) dg.applyProtection(no, { action: "SLOT_LOCK", slotIndex: s, reason: "电池异常" });
    expect(() => dg.startTrialRent(no)).toThrowError(/没有可弹出/);
  });

  it("失败要记原因，失败不算通过", () => {
    const t = dg.startTrialRent(no);
    const done = dg.finishTrialRent(t.trialNo, false, "宝没弹出来");
    expect(done).toMatchObject({ status: "FAILED", failReason: "宝没弹出来" });
    expect(item("TRIAL").passed).toBe(false);
  });
});

describe("保护", () => {
  it("保护原因必填——没有原因的保护没人敢解", () => {
    expect(() => dg.applyProtection(no, { action: "STOP_RENT", reason: "  " })).toThrowError(/原因|Reason/i);
  });

  it("仓位级动作必须给仓位号且在范围内；整柜级动作忽略仓位号", () => {
    expect(() => dg.applyProtection(no, { action: "SLOT_DISABLE", reason: "x" })).toThrowError(/仓位号/);
    expect(() => dg.applyProtection(no, { action: "SLOT_LOCK", slotIndex: 9, reason: "x" })).toThrowError(/1~8/);
    expect(dg.applyProtection(no, { action: "STOP_RENT", slotIndex: 3, reason: "x" }).slotIndex).toBeNull();
  });

  it("同一持有方重复挂同一条是幂等的（后端唯一键）", () => {
    const a = dg.applyProtection(no, { action: "DERATE", reason: "过热" });
    const b = dg.applyProtection(no, { action: "DERATE", reason: "过热" });
    expect(b.protectionNo).toBe(a.protectionNo);
  });

  it("★ 只能解人工挂的；信号/告警挂的手工解不掉", () => {
    const sys = dg.applySystemProtection(no, { action: "SLOT_LOCK", slotIndex: 2, reason: "电池异常" }, "SIGNAL", "BATTERY_ABNORMAL:x:2");
    expect(() => dg.releaseProtection(sys.protectionNo, "我觉得好了")).toThrowError(/设备信号|MANUAL/);
    expect(sys.active).toBe(true);
  });

  it("人工挂的能解（原因必填），解完不再生效、历史里留痕", () => {
    const p = dg.applyProtection(no, { action: "SLOT_DISABLE", slotIndex: 3, reason: "卡槽异响" });
    expect(() => dg.releaseProtection(p.protectionNo, "")).toThrowError(/原因必填|Reason/);
    dg.releaseProtection(p.protectionNo, "已处理");
    expect(dg.listProtections(no, true).some((x) => x.protectionNo === p.protectionNo)).toBe(false);
    expect(dg.listProtections(no).find((x) => x.protectionNo === p.protectionNo)?.releaseReason).toBe("已处理");
  });

  it("已解除的不能再解一次", () => {
    const p = dg.applyProtection(no, { action: "DERATE", reason: "降额观察" });
    dg.releaseProtection(p.protectionNo, "ok");
    expect(() => dg.releaseProtection(p.protectionNo, "ok")).toThrowError(/已解除|Already released/);
  });
});

describe("信号码字典", () => {
  it("恢复信号清的是真实存在的码；「不保护」写 NONE（后端字典原样）", () => {
    const codes = dg.listSignalCodes();
    const known = new Set(codes.map((c) => c.code));
    for (const c of codes) if (c.clearsCode) expect(known.has(c.clearsCode), `${c.clearsCode} 不存在`).toBe(true);
    expect(codes.some((c) => c.protectiveAction === "NONE")).toBe(true);
  });
});
