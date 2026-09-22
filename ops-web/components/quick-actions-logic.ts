// 工作台快捷动作的纯逻辑（与 React 解耦，便于单测）：目标校验 + 下发前拦截口径。
//
// 为什么单独一份：工作台没有行上下文，三个动作的目标全靠用户选。选错的代价不对称
// ——「远程弹出」是真下发到硬件的写操作，柜号打错等于对着别人的机器开仓，故目标
// 一律只能从真实机柜列表里选，并在下发前把「离线会排队 / 仓内无宝」讲清楚。

import type { Cabinet } from "@/lib/types";

/** 弹出指令的仓位上限缺省值：机柜台账没给 slotTotal 时不猜格数，只做「正整数」校验。 */
export type EjectTarget = { cabinetNo: string; slotIndex?: number };

export type Precheck =
  /** 拦：说明为什么不能下发（UI 禁用下发按钮并显示 reason）。 */
  | { ok: false; reason: string }
  /** 放行：warn 非空时在二次确认里点明后果（离线排队等），不阻断。 */
  | { ok: true; warn?: string };

/**
 * 目标机柜必须来自真实列表。返回 null = 柜号不在台账里，调用方必须拒绝下发。
 * （不做 trim/大小写宽容：柜号是主键，宽容匹配等于替用户猜目标。）
 */
export function findCabinet(list: readonly Cabinet[], cabinetNo: string): Cabinet | null {
  return list.find((c) => c.cabinetNo === cabinetNo) ?? null;
}

/** 空串/纯空白视为「不指定仓位」，走 EJECT_ANY。 */
export function parseSlotInput(raw: string): { slotIndex?: number; bad: boolean } {
  const s = raw.trim();
  if (s === "") return { bad: false };
  const n = Number(s);
  if (!Number.isInteger(n) || n < 1) return { bad: true };
  return { slotIndex: n, bad: false };
}

/** 指定仓位走 EJECT，不指定走 EJECT_ANY（与设备详情页「弹出充电宝」同一套指令名）。 */
export function ejectCommandType(slotIndex?: number): "EJECT" | "EJECT_ANY" {
  return slotIndex == null ? "EJECT_ANY" : "EJECT";
}

/**
 * 下发前置校验。拦截项只有「目标不存在 / 仓位越界 / 已退役 / 无宝可弹」四类，
 * 离线只警告不拦——网关会排队，等上线执行，这是运维现场常用的做法。
 */
export function ejectPrecheck(cab: Cabinet | null, slotIndex?: number, slotBad = false): Precheck {
  if (!cab) return { ok: false, reason: "请先从机柜列表中选择目标机柜" };
  if (slotBad) return { ok: false, reason: "仓位号必须是不小于 1 的整数，留空表示任意仓位" };
  if (slotIndex != null && cab.slotTotal > 0 && slotIndex > cab.slotTotal) {
    return { ok: false, reason: `该机柜共 ${cab.slotTotal} 个仓位，不存在第 ${slotIndex} 仓` };
  }
  if (cab.status === "RETIRED") return { ok: false, reason: "机柜已退役，不能下发指令" };
  if (slotIndex == null && cab.availableCount <= 0) {
    return { ok: false, reason: "该机柜在仓充电宝为 0，任意仓位弹出没有可弹对象；如需开仓请指定仓位" };
  }
  if (cab.onlineStatus === "OFFLINE") {
    return { ok: true, warn: "该机柜当前离线，指令会排队等待上线后执行" };
  }
  return { ok: true };
}

/** 二次确认文案：把「弹哪台、弹哪仓、后果」一句话说全（对齐设备页批量下发的确认口径）。 */
export function ejectConfirmDesc(cab: Cabinet, slotIndex: number | undefined, warn?: string): string {
  const where = slotIndex == null ? "任意可用仓位" : `第 ${slotIndex} 仓`;
  return `将对机柜 ${cab.cabinetNo}（${cab.locationName ?? "未上架"}）的${where}下发弹出指令，`
    + `现场会立即弹出充电宝，请确认有人在机器旁。`
    + (warn ? `${warn}。` : "");
}

/** 查订单的关键词：太短会把整张订单表捞回来，故 2 个字符以内不发请求。 */
export function orderKeywordReady(keyword: string): boolean {
  return keyword.trim().length >= 2;
}
