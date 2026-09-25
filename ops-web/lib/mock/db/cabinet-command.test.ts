// 机柜建档 + 单柜指令下发单测（S8）。
//
// 这两件事之前都缺入口，补完后最容易回归的不是「能不能存」，而是三条口径：
//   ① 建档**只接受建档字段**：归属站点/站点名由点位反查，在线态与心跳是设备上报的事实，
//      表单塞了也不采信——否则台账会出现「点位在 A 站、归属写 B 站」或「离线柜显示在线」
//   ② 建档校验守在落库层（不是抽屉里）：换个入口（导入/将来的后端）也得守同一套
//   ③ 下发即留痕：指令记录多一条 SENT，而不是只回一个假 commandId（原先 mock 就是这样，
//      表现为「下发成功了但指令记录里查不到」）
import { describe, it, expect } from "vitest";
import { locations } from "./location";
import {
  cabinets, commandRecords, saveCabinet, recordCommand, cabinetPlacement,
  archiveCabinet, unarchiveCabinet, CabinetError, CommandError,
} from "./device";

let seq = 0;
/** 造一份合法建档入参；SN 每次递增，避免用例之间撞唯一键。 */
const draft = (over: Partial<Parameters<typeof saveCabinet>[0]> = {}) => ({
  sn: `SN7${String(++seq).padStart(4, "0")}`, vendorCode: "cd-tech", model: "X6",
  slotTotal: 8, locationNo: null as string | null, fwVersion: "1.4.0", status: "DEPLOYED" as const,
  ...over,
});

describe("机柜建档：只接受建档字段", () => {
  it("新建自动生成柜机号，且默认在库、离线无心跳、无归属代理", () => {
    const c = saveCabinet(draft());
    expect(c.cabinetNo).toMatch(/^CAB\d+$/);
    // 新建一律在库（同后端）：表单传 DEPLOYED 也不采信 —— 没过上线门禁就在用，会出现在 C 端可借列表里
    expect(c.status).toBe("IN_STOCK");
    expect(cabinets.find((x) => x.cabinetNo === c.cabinetNo)).toBe(c);
    // 新柜没上报过任何东西：在线态/心跳/可借数都不能凭表单变成「像在跑」
    expect(c.onlineStatus).toBe("OFFLINE");
    expect(c.lastHeartbeatAt).toBeNull();
    expect(c.availableCount).toBe(0);
    expect(c.agentNo).toBeNull(); // 归属代理只能经代理域划拨
  });

  it("归属站点与站点名一律由点位反查，表单传什么都不采信", () => {
    const loc = locations[3];
    const c = saveCabinet(draft({
      locationNo: loc.locationNo,
      // 故意塞矛盾值：这三个字段没有任何一个应该按入参落库
      siteNo: "ST999", locationName: "伪造站点", onlineStatus: "ONLINE",
    }));
    expect(c.siteNo).toBe(loc.siteNo);
    expect(c.locationName).toBe(loc.siteName);
    expect(c.onlineStatus).toBe("OFFLINE");
    expect(cabinetPlacement(loc.locationNo)).toEqual({ siteNo: loc.siteNo, locationName: loc.siteName });
  });

  it("点位留空即到货未上架：归属为空，而不是编一个站点号", () => {
    const c = saveCabinet(draft({ locationNo: "" }));
    expect(c.locationNo).toBeNull();
    expect(c.siteNo).toBeNull();
    expect(c.locationName).toBeNull();
  });

  it("编辑不动设备上报出来的事实（可借数 / 心跳 / 归属代理）", () => {
    const live = cabinets.find((c) => c.availableCount > 0 && c.agentNo)!;
    const before = { avail: live.availableCount, agent: live.agentNo, hb: live.lastHeartbeatAt };
    const status = live.status;
    // 状态也不经编辑改（R1，同后端「编辑不再改状态」）：传了 FAULT 也不采信，状态只由动作推进
    const r = saveCabinet({ cabinetNo: live.cabinetNo, sn: live.sn, vendorCode: live.vendorCode, model: "M12", slotTotal: 12, locationNo: live.locationNo, status: status === "FAULT" ? "DEPLOYED" : "FAULT" });
    expect(r.model).toBe("M12");
    expect(r.status).toBe(status);
    expect(r.availableCount).toBe(before.avail);
    expect(r.agentNo).toBe(before.agent);
    expect(r.lastHeartbeatAt).toBe(before.hb);
  });
});

describe("机柜建档：校验守在落库层", () => {
  it("SN 少于 4 位 / 与他柜重复都拒绝", () => {
    expect(() => saveCabinet(draft({ sn: "SN1" }))).toThrow(CabinetError);
    expect(() => saveCabinet(draft({ sn: cabinets[0].sn }))).toThrow(/已被 CAB/);
  });

  it("手填柜机号必须形如 CAB+数字（与导入模板同一条规则）", () => {
    expect(() => saveCabinet(draft({ cabinetNo: "机柜一号" }))).toThrow(/CAB/);
  });

  it("未接入的供应商 / 不存在的点位一律拒绝，不留悬空引用", () => {
    expect(() => saveCabinet(draft({ vendorCode: "no-such-vendor" }))).toThrow(/未接入的供应商/);
    expect(() => saveCabinet(draft({ locationNo: "LOC99999" }))).toThrow(/点位不存在/);
  });

  it("仓位数越界拒绝，且不得缩到在仓充电宝数以下", () => {
    expect(() => saveCabinet(draft({ slotTotal: 0 }))).toThrow(CabinetError);
    expect(() => saveCabinet(draft({ slotTotal: 49 }))).toThrow(CabinetError);
    expect(() => saveCabinet(draft({ slotTotal: 8.5 }))).toThrow(CabinetError);

    const live = cabinets.find((c) => c.availableCount > 1)!;
    expect(() => saveCabinet({
      cabinetNo: live.cabinetNo, sn: live.sn, vendorCode: live.vendorCode,
      model: live.model, slotTotal: live.availableCount - 1, locationNo: live.locationNo,
    })).toThrow(/不得小于当前在仓充电宝数/);
  });

  it("被拒绝的建档不落库（不允许半截数据）", () => {
    const before = cabinets.length;
    expect(() => saveCabinet(draft({ locationNo: "LOC99999" }))).toThrow();
    expect(cabinets.length).toBe(before);
  });
});

describe("单柜指令下发：下发即留痕", () => {
  it("落一条 SENT 记录，而不是只回一个假 commandId", () => {
    const cab = cabinets[0];
    const before = commandRecords.length;
    const rec = recordCommand(cab.cabinetNo, "REBOOT");
    expect(commandRecords.length).toBe(before + 1);
    expect(commandRecords[0]).toBe(rec); // 倒序：最新一条在最前，列表首屏就能看到
    expect(rec.cabinetNo).toBe(cab.cabinetNo);
    expect(rec.type).toBe("REBOOT");
    expect(rec.slotIndex).toBeNull();
    // 刚下发只能是「已下发」：直接写 ACKED 等于伪造设备回执
    expect(rec.status).toBe("SENT");
    expect(rec.operator).toBe("admin");
    expect(commandRecords.filter((r) => r.commandId === rec.commandId)).toHaveLength(1);
  });

  it("弹仓不带仓位 = 任意仓，带仓位 = 指定仓（详情页两个按钮同一个指令名）", () => {
    const cab = cabinets[0];
    expect(recordCommand(cab.cabinetNo, "EJECT").slotIndex).toBeNull();
    expect(recordCommand(cab.cabinetNo, "EJECT", { slotIndex: 2 }).slotIndex).toBe(2);
  });

  it("锁仓必须指定仓位，仓位号不得超出该柜仓位数", () => {
    const cab = cabinets[0];
    expect(() => recordCommand(cab.cabinetNo, "LOCK")).toThrow(CommandError);
    expect(() => recordCommand(cab.cabinetNo, "LOCK", { slotIndex: cab.slotTotal + 1 })).toThrow(/仓位号应在/);
    expect(() => recordCommand(cab.cabinetNo, "EJECT", { slotIndex: 0 })).toThrow(/仓位号应在/);
  });

  it("未知指令名拒绝——页面能选的必须落得进指令记录", () => {
    // 收敛前详情页发的就是 EJECT_ANY/EJECT_SLOT，落到记录里是显示不出来的类型
    expect(() => recordCommand(cabinets[0].cabinetNo, "EJECT_ANY")).toThrow(/未知指令类型/);
    expect(() => recordCommand(cabinets[0].cabinetNo, "FW_SYNC")).not.toThrow(); // 批量那条在词表里
  });

  it("机柜不存在 / 已归档都拒绝，且不落记录", () => {
    const cab = cabinets[1];
    const before = commandRecords.length;
    expect(() => recordCommand("CAB000000", "REBOOT")).toThrow(/机柜不存在/);
    archiveCabinet(cab.cabinetNo);
    expect(() => recordCommand(cab.cabinetNo, "REBOOT")).toThrow(/已归档/);
    expect(commandRecords.length).toBe(before);
    unarchiveCabinet(cab.cabinetNo);
    expect(() => recordCommand(cab.cabinetNo, "REBOOT")).not.toThrow();
  });
});
