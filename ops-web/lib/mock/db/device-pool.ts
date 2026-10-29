// 出库单与绑定（mock）—— 设计：docs/technical/设备出库与绑定-技术设计.md §二.6、§三.3 ~ 3.4；
// 路径以商户入驻会话落地为准（V161：issue / pool-return / issued-summary）。
//
// 口径（用户 2026-10-28）：绑定商家先出库，出库必须选商家；绑定的设备只从该商家名下（已出库待绑定）选，
// 默认绑到商家的默认站点、默认点位；站点与点位一期由运营代建；绑定在运营端做。
//
// **不变式在这一层强制**（与后端同口径，非法动作抛错，不靠界面拦）：
//   I1 在仓（warehouseNo）与在出库单（issuedAgentNo）互斥
//   I2 绑定的点位所属站点的商家 = 设备名下（已出库待绑定）商家
//   I3 agentNo 只由点位派生（绑定时写、解绑时清）
//   I4 一个事务：批量动作先全部校验，任一不过整批不动
//   I5 平台直营站点（站点无商家）的设备不经出库单：可从仓直接绑定
import type {
  Cabinet, IssueDevicesReq, ClaimDevicesReq, IssueRedirectReq, PoolReturnReq, IssuedSummary, BindReq, BindBatchReq, BindResult, SitePoint, Warehouse } from "../../types";
import { agentCoreComplete } from "../../types";
import { cabinets } from "./device";
import { sites, locations, ensureDefaultPoint } from "./location";
import { agents, syncDefaultSite, saveSiteAgent, listSiteAgents } from "./agent";
import { createWorkOrder } from "./workorder";
import { recordMovement, ref } from "./movement";
import { fail, notFound } from "@/lib/biz-error";
import { sysParams } from "./system";

/**
 * 仓库：与后端 V182 种的四个仓同号同名；增改归档见 inventory-admin（V201）。
 * isDefault 不存在行上：按系统参数 inv.return_warehouse 现算（与后端 WarehouseDirectory 同口径）。
 */
export const WAREHOUSES: Warehouse[] = [
  { warehouseNo: "WH001", name: "迪拜仓", regionId: "AE-DU", address: null, isDefault: false, archivedAt: null },
  { warehouseNo: "WH002", name: "阿布扎比仓", regionId: "AE-AZ", address: null, isDefault: false, archivedAt: null },
  { warehouseNo: "WH003", name: "沙迦仓", regionId: "AE-SH", address: null, isDefault: false, archivedAt: null },
  { warehouseNo: "WH004", name: "阿治曼仓", regionId: "AE-AJ", address: null, isDefault: false, archivedAt: null },
];
/** 演示设备默认所在仓 = 默认回仓仓。 */
export const POOL_WAREHOUSE = "WH001";

/** 默认回仓仓：配置的仓启用中就用它，否则第一个启用的仓。 */
export function defaultWarehouseNo(): string | undefined {
  const cfg = sysParams.find((p) => p.paramKey === "inv.return_warehouse")?.value;
  const live = WAREHOUSES.filter((w) => !w.archivedAt);
  return (live.find((w) => w.warehouseNo === cfg) ?? live[0])?.warehouseNo;
}

export function listWarehouses(): Warehouse[] {
  const def = defaultWarehouseNo();
  return WAREHOUSES.map((w) => ({ ...w, isDefault: w.warehouseNo === def }));
}

const now = () => new Date().toISOString();
const agentOf = (agentNo: string) => agents.find((a) => a.agentNo === agentNo) ?? notFound("商家", "Merchant", agentNo);
const cabOf = (no: string) => cabinets.find((c) => c.cabinetNo === no) ?? notFound("机柜", "Cabinet", no);
const siteOf = (siteNo: string | null | undefined) => sites.find((s) => s.siteNo === siteNo) ?? null;

// ————————————————————————————— 站点的默认点位 —————————————————————————————

/**
 * 商家站点建站的同事务三件事（技术设计 §3.6）：站点的商家已在入参里，这里补
 * `OPERATE` 责任行（分润读它）与默认点位。只写归属不写责任，钱会分给别人。
 */
export function provisionMerchantSite(siteNo: string, agentNo: string) {
  if (!listSiteAgents(siteNo).some((r) => r.agentNo === agentNo && r.role === "OPERATE")) {
    saveSiteAgent(siteNo, { agentNo, role: "OPERATE", remark: "商家站点（运营代建）" });
  }
  return ensureDefaultPoint(siteNo);
}

// ————————————————————————————— 演示数据（首次使用时补） —————————————————————————————

let seeded = false;
/**
 * 惰性补种：在全局种子上直接加柜子会改动别处依赖的计数，所以只在第一次用到出库单时补。
 *  · 资料齐全的前三个商家各有默认站点（与默认站点的同步规则同一个方法）+ 默认点位 + OPERATE 责任行；
 *  · 仓里 6 台待出库（质检放行）；AG002 出库单 3 台未绑定 + 1 台已绑定待安装；
 *  · 存量已绑定的柜子回填 issuedAgentNo = agentNo（技术设计 §2.1 回填口径；直营的保持空）。
 */
export function ensurePoolSeed() {
  if (seeded) return;
  seeded = true;
  for (const c of cabinets) {
    if (c.issuedAgentNo === undefined) c.issuedAgentNo = c.locationNo && c.agentNo ? c.agentNo : null;
    // V195 回填：存量出库单设备视为已签收
    if (c.issueClaimedAt === undefined) c.issueClaimedAt = c.issuedAgentNo ? (c.issuedAt ?? now()) : null;
  }
  for (const a of agents.filter((x) => agentCoreComplete(x) && !x.archivedAt).slice(0, 3)) {
    syncDefaultSite(a, false);
    if (a.defaultSiteNo) provisionMerchantSite(a.defaultSiteNo, a.agentNo);
  }
  const base = {
    vendorCode: cabinets[0]?.vendorCode ?? "VD-A", model: "S8", slotTotal: 8, availableCount: 0,
    onlineStatus: "OFFLINE" as const, status: "IN_STOCK" as const, fwVersion: "1.4.0", lastHeartbeatAt: null,
    agentNo: null, siteNo: null, locationNo: null, locationName: null, archivedAt: null,
  };
  for (let i = 0; i < 6; i++) {
    cabinets.push({ ...base, cabinetNo: `CAB${1100 + i}`, sn: `SN9${1100 + i}`, qcStatus: "PASSED",
      warehouseNo: POOL_WAREHOUSE, issuedAgentNo: null, issuedAt: null } as Cabinet);
  }
  const ag2 = agents.find((a) => a.agentNo === "AG002");
  if (ag2?.defaultSiteNo) {
    for (let i = 0; i < 4; i++) {
      cabinets.push({ ...base, cabinetNo: `CAB${1110 + i}`, sn: `SN9${1110 + i}`, qcStatus: "PASSED",
        warehouseNo: null, issuedAgentNo: "AG002", issuedAt: now(), issueClaimedAt: now() } as Cabinet);
    }
    bindOne(cabOf("CAB1113"), {});
  }
}

// ————————————————————————————— 出库 / 退回 —————————————————————————————

/** 出库给商家（POST /api/ops/devices/issue）：一个事务，全成或全不成。 */
export function issueDevices(x: IssueDevicesReq): Cabinet[] {
  ensurePoolSeed();
  if (!x.agentNo) fail("请选择商家：出库必须选商家", "Choose a merchant: outbound requires a merchant");
  const agent = agentOf(x.agentNo);
  if (agent.archivedAt || agent.status !== "ENABLED") {
    fail(`商家 ${agent.name} 未启用，不能出库给它`, `Merchant ${agent.name} is not enabled`);
  }
  if (!agentCoreComplete(agent) || !agent.defaultSiteNo) {
    fail(`商家 ${agent.name} 还没补齐位置与行业（没有默认站点），补齐后才能出库`,
      `Merchant ${agent.name} has no location or industry yet (no default site)`);
  }
  const nos = [...new Set(x.cabinetNos ?? [])];
  if (!nos.length) fail("请至少选择一台设备", "Select at least one device");
  const rows = nos.map(cabOf);
  const bad = rows.flatMap((c) => {
    if (c.archivedAt) return [`${c.cabinetNo} 已归档`];
    if (c.status !== "IN_STOCK") return [`${c.cabinetNo} 不在库（${c.status}）`];
    if (c.issuedAgentNo) return [`${c.cabinetNo} 已在 ${c.issuedAgentNo} 名下（已出库待绑定）`];
    if (c.locationNo) return [`${c.cabinetNo} 已绑定点位 ${c.locationNo}`];
    if (c.qcStatus && c.qcStatus !== "PASSED") return [`${c.cabinetNo} 质检未放行`];
    return [];
  });
  if (bad.length) fail(`出库被拒（整批未动）：${bad.join("；")}`, `Outbound rejected (nothing changed): ${bad.join("; ")}`);
  const siteNo = siteOfAgent(agent.agentNo, x.siteNo);
  const receiver = x.receiverNo?.trim() || null;
  const at = now();
  for (const c of rows) {
    recordMovement({ itemNo: c.cabinetNo, event: "ISSUE", fromRef: ref.wh(c.warehouseNo), toRef: ref.pool(agent.agentNo, siteNo),
      agentAfter: agent.agentNo, reason: receiver ? `接收人 ${receiver}` : null });
    Object.assign(c, { warehouseNo: null, issuedAgentNo: agent.agentNo, issuedAt: at, issuedSiteNo: siteNo,
      issueReceiverNo: receiver, issueClaimedAt: receiver ? null : at });
  }
  return rows;
}

/** 目的站点可空；填了须属于该商家（同后端 requireSiteOf）。 */
function siteOfAgent(agentNo: string, siteNo?: string): string | null {
  const no = siteNo?.trim();
  if (!no) return null;
  const s = siteOf(no);
  if (!s) notFound("站点", "Site", no);
  if (s!.agentNo !== agentNo) fail(`站点 ${s!.name} 不属于商家 ${agentNo}`, `Site ${no} does not belong to ${agentNo}`);
  return no;
}

/** 接收人签收（POST /api/ops/devices/claim）：只能签给自己的、未签收的。 */
export function claimDevices(x: ClaimDevicesReq, me: string): Cabinet[] {
  ensurePoolSeed();
  const nos = [...new Set(x.cabinetNos ?? [])];
  if (!nos.length) fail("请至少选择一台设备", "Select at least one device");
  const rows = nos.map(cabOf);
  for (const c of rows) {
    if (!c.issuedAgentNo || c.issueClaimedAt) fail(`设备 ${c.cabinetNo} 不在待签收状态`, `${c.cabinetNo} is not awaiting sign-off`);
    if (c.issueReceiverNo !== me) fail(`设备 ${c.cabinetNo} 不是发给你的，只有接收人本人能签收`, `${c.cabinetNo} was not sent to you`);
  }
  const at = now();
  for (const c of rows) {
    c.issueClaimedAt = at;
    const here = ref.pool(c.issuedAgentNo, c.issuedSiteNo);
    recordMovement({ itemNo: c.cabinetNo, event: "CLAIM", fromRef: here, toRef: here, agentBefore: c.issuedAgentNo, agentAfter: c.issuedAgentNo });
  }
  return rows;
}

/** 出库调整（POST /api/ops/devices/issue-redirect）：只收未绑定的；换接收人须重新签收。 */
export function redirectIssue(x: IssueRedirectReq): Cabinet[] {
  ensurePoolSeed();
  if (!x.reason?.trim()) fail("请写调整原因", "A reason is required");
  if (!x.agentNo) fail("请选择商家", "Choose a merchant");
  const agent = agentOf(x.agentNo);
  if (agent.archivedAt || agent.status !== "ENABLED") fail(`商家 ${agent.name} 未启用`, `Merchant ${agent.name} is not enabled`);
  const siteNo = siteOfAgent(agent.agentNo, x.siteNo);
  const receiver = x.receiverNo?.trim() || null;
  const nos = [...new Set(x.cabinetNos ?? [])];
  if (!nos.length) fail("请至少选择一台设备", "Select at least one device");
  const rows = nos.map(cabOf);
  const bad = rows.filter((c) => !c.issuedAgentNo || c.locationNo || c.status !== "IN_STOCK").map((c) => c.cabinetNo);
  if (bad.length) fail(`这些设备不在出库单或已绑定：${bad.join("、")}`, `Not in pool or already bound: ${bad.join(", ")}`);
  const at = now();
  for (const c of rows) {
    const keep = !!c.issueClaimedAt && receiver === (c.issueReceiverNo ?? null);
    const claimed = receiver == null ? (c.issueClaimedAt ?? at) : keep ? c.issueClaimedAt : null;
    recordMovement({ itemNo: c.cabinetNo, event: "REDIRECT", fromRef: ref.pool(c.issuedAgentNo, c.issuedSiteNo),
      toRef: ref.pool(agent.agentNo, siteNo), agentBefore: c.issuedAgentNo, agentAfter: agent.agentNo, reason: x.reason });
    Object.assign(c, { issuedAgentNo: agent.agentNo, issuedSiteNo: siteNo, issueReceiverNo: receiver, issueClaimedAt: claimed });
  }
  return rows;
}

/** 出库退回仓库（POST /api/ops/devices/pool-return）：只收未绑定的；原因必填。 */
export function returnDevicesToWarehouse(x: PoolReturnReq): Cabinet[] {
  ensurePoolSeed();
  if (!x.reason?.trim()) fail("请写退回原因", "A return reason is required");
  const nos = [...new Set(x.cabinetNos ?? [])];
  if (!nos.length) fail("请至少选择一台设备", "Select at least one device");
  const rows = nos.map(cabOf);
  const bad = rows.flatMap((c) => {
    if (!c.issuedAgentNo) return [`${c.cabinetNo} 不在任何商家名下（已出库待绑定）`];
    if (c.locationNo) return [`${c.cabinetNo} 已绑定点位，先解绑再退回`];
    if (c.status !== "IN_STOCK") return [`${c.cabinetNo} 不在库（${c.status}）`];
    return [];
  });
  if (bad.length) fail(`退回被拒（整批未动）：${bad.join("；")}`, `Return rejected (nothing changed): ${bad.join("; ")}`);
  // 与后端同：不选仓 → 默认回仓仓；选了不存在的仓 → notFound（此前不填会落到「不在任何仓」）
  const wh = x.warehouseNo?.trim() || defaultWarehouseNo();
  if (!wh) fail("还没有任何仓库，无法退回仓库", "No warehouse exists yet");
  if (!WAREHOUSES.some((w) => w.warehouseNo === wh && !w.archivedAt)) notFound("仓库", "Warehouse", wh!);
  for (const c of rows) {
    recordMovement({ itemNo: c.cabinetNo, event: "POOL_RETURN", fromRef: ref.pool(c.issuedAgentNo, c.issuedSiteNo), toRef: ref.wh(wh),
      agentBefore: c.issuedAgentNo, reason: x.reason });
    Object.assign(c, { issuedAgentNo: null, issuedAt: null, warehouseNo: wh, issuedSiteNo: null, issueReceiverNo: null, issueClaimedAt: null });
  }
  return rows;
}

/** 出库单按商家汇总（GET /api/ops/inventory/issued-summary）。 */
export function issuedSummary(agentNo?: string): IssuedSummary[] {
  ensurePoolSeed();
  const by = new Map<string, IssuedSummary>();
  for (const c of cabinets) {
    if (!c.issuedAgentNo || c.archivedAt) continue;
    if (agentNo && c.issuedAgentNo !== agentNo) continue;
    const row = by.get(c.issuedAgentNo) ?? {
      agentNo: c.issuedAgentNo, agentName: agents.find((a) => a.agentNo === c.issuedAgentNo)?.name ?? c.issuedAgentNo,
      unbound: 0, bound: 0, live: 0,
    };
    if (!c.locationNo) row.unbound++;
    else if (c.status === "DEPLOYED" || c.status === "FAULT") row.live++;
    else row.bound++;
    by.set(c.issuedAgentNo, row);
  }
  return [...by.values()].sort((a, b) => b.unbound - a.unbound || a.agentNo.localeCompare(b.agentNo));
}

/** 列表筛选：出库单商家 + 是否已绑定（技术设计 §3.3）。 */
export function poolHit(c: Cabinet, issuedAgentNo?: string, pool?: string, me?: string): boolean {
  if (issuedAgentNo && c.issuedAgentNo !== issuedAgentNo) return false;
  if (pool === "UNCLAIMED") return !!c.issuedAgentNo && !c.issueClaimedAt;
  if (pool === "MINE") return !!c.issuedAgentNo && !c.issueClaimedAt && !!me && c.issueReceiverNo === me;
  // 待绑定 = 绑定候选：已签收、未绑定（未签收的在「待签收」里，不能绑）
  if (pool === "UNBOUND") return !!c.issuedAgentNo && !c.locationNo && c.issueClaimedAt !== null;
  if (pool === "BOUND") return !!c.issuedAgentNo && !!c.locationNo;
  if (pool === "ALL") return !!c.issuedAgentNo;
  return true;
}

// ————————————————————————————— 绑定 / 解绑 —————————————————————————————

/** 校验一台设备能否绑到目标点位，返回目标点位；不改任何数据（批量绑定先全部校验）。 */
function checkBind(c: Cabinet, req: BindReq): SitePoint {
  if (c.archivedAt) fail(`${c.cabinetNo} 已归档`, `${c.cabinetNo} is archived`);
  if (c.status !== "IN_STOCK") fail(`${c.cabinetNo} 不在库（${c.status}），已上线的设备要先撤机`, `${c.cabinetNo} is not in stock`);
  if (c.locationNo) fail(`${c.cabinetNo} 已绑定点位 ${c.locationNo}，先解绑`, `${c.cabinetNo} is already bound`);
  let loc: SitePoint | undefined;
  if (req.locationNo) {
    loc = locations.find((l) => l.locationNo === req.locationNo && !l.archivedAt);
    if (!loc) notFound("点位", "Point", req.locationNo);
  } else {
    if (!c.issuedAgentNo) fail(`${c.cabinetNo} 不在任何商家名下（已出库待绑定）：先出库，或指定平台直营站点的点位`,
      `${c.cabinetNo} is not in any merchant's pool`);
    const agent = agentOf(c.issuedAgentNo!);
    const target = c.issuedSiteNo ?? agent.defaultSiteNo;
    if (!target) fail(`商家 ${agent.name} 没有默认站点`, `Merchant ${agent.name} has no default site`);
    loc = ensureDefaultPoint(target!);
  }
  const site = siteOf(loc!.siteNo);
  if (!site) notFound("站点", "Site", loc!.siteNo);
  if (loc!.status !== "ACTIVE") fail(`点位 ${loc!.name} 已暂停，不能绑定`, `Point ${loc!.name} is paused`);
  const siteAgent = site!.agentNo ?? null;
  if (c.issuedAgentNo) {
    // PB-D4 / D2（V195）：未签收不能绑；出库到站点的只能绑该站点
    if (!c.issueClaimedAt) fail(`设备 ${c.cabinetNo} 已发货但接收人还没签收，签收后才能绑定`, `${c.cabinetNo} is not signed for yet`);
    if (c.issuedSiteNo && c.issuedSiteNo !== loc!.siteNo) {
      fail(`设备 ${c.cabinetNo} 出库到站点 ${c.issuedSiteNo}，只能绑该站点的点位；要换站点请先在出库处调整目的地`,
        `${c.cabinetNo} was issued to site ${c.issuedSiteNo}`);
    }
    // I2：出库给 A 的柜子不能绑到 B 的站点（也不能绑到直营站点）
    if (siteAgent !== c.issuedAgentNo) {
      fail(`设备 ${c.cabinetNo} 出库给了 ${c.issuedAgentNo}，不能绑到 ${siteAgent ?? "平台直营"} 的站点 ${site!.name}`,
        `${c.cabinetNo} was issued to ${c.issuedAgentNo} and cannot be bound to a site of ${siteAgent ?? "the platform"}`);
    }
  } else if (siteAgent) {
    // I5 的反面：商家站点只收出库单里的设备
    fail(`商家站点 ${site!.name} 只能绑定出库给 ${siteAgent} 的设备：先出库`,
      `Merchant site ${site!.name} only accepts devices issued to ${siteAgent}`);
  } else if (c.status === "IN_STOCK" && !c.warehouseNo) {
    fail(`${c.cabinetNo} 不在仓里，不能直接绑定直营站点`, `${c.cabinetNo} is not in a warehouse`);
  }
  return loc!;
}

function bindOne(c: Cabinet, req: BindReq): BindResult {
  const loc = checkBind(c, req);
  const site = siteOf(loc.siteNo)!;
  recordMovement({ itemNo: c.cabinetNo, event: "BIND",
    fromRef: c.locationNo ? ref.loc(c.locationNo) : c.issuedAgentNo ? ref.pool(c.issuedAgentNo, c.issuedSiteNo) : ref.wh(c.warehouseNo),
    toRef: ref.loc(loc.locationNo), agentBefore: c.agentNo, agentAfter: site.agentNo ?? null });
  // I3：站点、站点名、商家都由点位派生；直营站点从仓里直接出（I5）
  Object.assign(c, {
    locationNo: loc.locationNo, siteNo: site.siteNo, locationName: site.name,
    agentNo: site.agentNo ?? null, warehouseNo: null,
  });
  let woNo: string | null = null;
  if (req.openInstallWo) {
    woNo = createWorkOrder({
      type: "INSTALL", source: "MANUAL", cabinetNo: c.cabinetNo, locationName: loc.name,
      description: `装机：${c.cabinetNo} → ${site.name} · ${loc.name}（出库单绑定生成）`,
    } as Parameters<typeof createWorkOrder>[0]).woNo;
  }
  return { cabinet: c, woNo };
}

/** 绑定（POST /api/ops/devices/{deviceNo}/bind）。 */
export function bindDevice(deviceNo: string, req: BindReq = {}): BindResult {
  ensurePoolSeed();
  return bindOne(cabOf(deviceNo), req);
}

/** 批量绑定（POST /api/ops/devices/bind-batch）：先全部校验，任一不过整批不动。 */
export function bindDevices(x: BindBatchReq): BindResult[] {
  ensurePoolSeed();
  const nos = [...new Set(x.deviceNos ?? [])];
  if (!nos.length) fail("请至少选择一台设备", "Select at least one device");
  const rows = nos.map(cabOf);
  const wrong = rows.filter((c) => c.issuedAgentNo !== x.agentNo).map((c) => c.cabinetNo);
  if (wrong.length) fail(`这些设备不在 ${x.agentNo} 名下（已出库待绑定）：${wrong.join("、")}`, `Not in ${x.agentNo}'s pool: ${wrong.join(", ")}`);
  const errors: string[] = [];
  for (const c of rows) {
    try { checkBind(c, x); } catch (e) { errors.push((e as Error).message); }
  }
  if (errors.length) fail(`批量绑定被拒（整批未动）：${errors.join("；")}`, `Batch bind rejected: ${errors.join("; ")}`);
  return rows.map((c) => bindOne(c, x));
}

/** 解绑（POST /api/ops/devices/{deviceNo}/unbind）：只限已绑定、未上线；回到同一商家名下（已出库待绑定）。 */
export function unbindDevice(deviceNo: string, reason: string): Cabinet {
  ensurePoolSeed();
  const c = cabOf(deviceNo);
  if (!reason?.trim()) fail("请写解绑原因", "An unbind reason is required");
  if (!c.locationNo) fail(`${c.cabinetNo} 没有绑定点位`, `${c.cabinetNo} is not bound`);
  if (c.status !== "IN_STOCK") fail(`${c.cabinetNo} 已上线（${c.status}），要走撤机工单`, `${c.cabinetNo} is live; use a removal work order`);
  const toWarehouse = !c.issuedAgentNo;   // 直营站点绑定的，解绑回仓
  recordMovement({ itemNo: c.cabinetNo, event: "UNBIND", fromRef: ref.loc(c.locationNo),
    toRef: ref.pool(c.issuedAgentNo, c.issuedSiteNo), agentBefore: c.agentNo, reason });
  Object.assign(c, {
    locationNo: null, siteNo: null, locationName: null, agentNo: null,
    warehouseNo: toWarehouse ? POOL_WAREHOUSE : null,
  });
  return c;
}
