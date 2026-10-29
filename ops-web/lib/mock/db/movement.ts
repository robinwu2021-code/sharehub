// 设备履历（V196）mock：只追加；与后端 MovementRecorder 同口径（from / to 带前缀 WH: / AGENT: / SITE: / LOC:）。
import type { DeviceMovement, MovementEvent } from "../../types";

const rows: DeviceMovement[] = [];
let seq = 0;

export const ref = {
  wh: (no?: string | null) => (no ? `WH:${no}` : null),
  agent: (no?: string | null) => (no ? `AGENT:${no}` : null),
  site: (no?: string | null) => (no ? `SITE:${no}` : null),
  loc: (no?: string | null) => (no ? `LOC:${no}` : null),
  pool: (agentNo?: string | null, siteNo?: string | null) => (siteNo ? `SITE:${siteNo}` : agentNo ? `AGENT:${agentNo}` : null),
};

export function recordMovement(m: {
  itemType?: DeviceMovement["itemType"]; itemNo: string; event: MovementEvent; fromRef?: string | null; toRef?: string | null;
  agentBefore?: string | null; agentAfter?: string | null; reason?: string | null; sourceRef?: string | null;
}): void {
  rows.push({
    id: ++seq, itemType: m.itemType ?? "CABINET", itemNo: m.itemNo, event: m.event, fromRef: m.fromRef ?? null, toRef: m.toRef ?? null,
    agentBefore: m.agentBefore ?? null, agentAfter: m.agentAfter ?? null, reason: m.reason?.trim() || null,
    sourceRef: m.sourceRef ?? null, operatorNo: "mock", occurredAt: new Date().toISOString(),
  });
}

/** 某台设备的履历，新的在前。 */
export const listDeviceMovements = (itemNo: string): DeviceMovement[] =>
  rows.filter((r) => r.itemNo === itemNo).sort((a, b) => b.id - a.id).map((r) => ({ ...r }));
