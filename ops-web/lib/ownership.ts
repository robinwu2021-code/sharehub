// 设备归属判定：「谁看得到」（机柜 agentNo，由点位派生）与「谁分得到」（站点责任行）是否一致。
// 分析：docs/technical/设备绑定与商家归属-现状分析.md §四.1。设备详情的归属卡用它标红。
import type { Cabinet, SiteAgent } from "./types";

/** 当天生效的责任行（与分润生成同口径：按日期取生效期内的行）。 */
export function effectiveRoles(rows: SiteAgent[], today = new Date().toISOString().slice(0, 10)) {
  return rows.filter((r) => (!r.effectiveFrom || r.effectiveFrom.slice(0, 10) <= today)
    && (!r.effectiveTo || r.effectiveTo.slice(0, 10) >= today));
}

/**
 * 判定「看得到」与「分得到」是否一致。返回问题描述；一致返回 null。
 * 规则：站点配了逐单分成的责任行（不含牵线）时，机柜的商家必须在其中；没配时分成回落到机柜的商家，天然一致。
 * 另：设备在出库池里时，绑定的站点商家必须等于出库池商家。
 */
export function ownershipIssues(c: Pick<Cabinet, "agentNo" | "issuedAgentNo" | "locationNo">, roles: SiteAgent[]): string[] {
  const out: string[] = [];
  const payees = effectiveRoles(roles).filter((r) => r.role !== "REFER");
  if (c.locationNo && payees.length && !payees.some((r) => r.agentNo === c.agentNo)) {
    out.push(c.agentNo
      ? `机柜归 ${c.agentNo}（商家能看到它的订单），但站点的分成给 ${payees.map((r) => r.agentNo).join("、")}`
      : `机柜是平台直营，但站点的分成给 ${payees.map((r) => r.agentNo).join("、")}`);
  }
  if (c.locationNo && c.issuedAgentNo && c.agentNo !== c.issuedAgentNo) {
    out.push(`机柜出库给了 ${c.issuedAgentNo}，却绑在 ${c.agentNo ?? "平台直营"} 的站点上`);
  }
  return out;
}
