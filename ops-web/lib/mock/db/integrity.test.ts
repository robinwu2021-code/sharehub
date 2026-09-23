// Mock 引用完整性测试（防复发机制 · 台账 §二 B/C 的配套）
//
// 背景：6 个并行 agent 各自追加 mock 时没人引用既有主数据，造出「佣金规则指向不存在的代理商」
// 「充值订单点进去在套餐管理页查无此套餐」这类跨页对不上的数据。规格 §17.1-8 要求
// 「mock 数据必须跨页自洽」，但当时只写进了规格、没有机制保障——本文件就是那个机制。
//
// 做法：把每个「外键」字段声明成一条引用关系，断言它的值都能在对应主数据里找到。
// 失败信息精确到 `数组[下标].字段 = "值" 不存在于 主数据.字段`，不用人肉翻文件。
//
// 新增 mock 时：只要新数组带了指向别处的字段，就往下面的 REFS 里补一条。
// 确实无法满足的（如场地入驻审核里还不是场地方的申请方），走 `allow` 白名单并写明理由。
import { describe, expect, it } from "vitest";
import * as db from "./index";
import { slotsOf } from "./device";

// ————————————————————————————————————————————————————————————————
// 主数据取值集合
// ————————————————————————————————————————————————————————————————
const setOf = <T>(rows: readonly T[], pick: (r: T) => string | null | undefined) =>
  new Set(rows.map(pick).filter((v): v is string => typeof v === "string" && v !== ""));

const agentNos = setOf(db.agents, (a) => a.agentNo);
const agentNames = setOf(db.agents, (a) => a.name);
const siteNos = setOf(db.sites, (s) => s.siteNo);
const siteNames = setOf(db.sites, (s) => s.name);
const venueNos = setOf(db.venues, (v) => v.venueNo);
const venueNames = setOf(db.venues, (v) => v.name);
const locationNos = setOf(db.locations, (l) => l.locationNo);
const cabinetNos = setOf(db.cabinets, (c) => c.cabinetNo);
const powerbankNos = setOf(db.powerbanks, (p) => p.powerbankNo);
const vendorCodes = setOf(db.vendors, (v) => v.vendorCode);
const fwVersions = setOf(db.otaReleases, (r) => r.version);
const rolloutNos = setOf(db.otaRollouts, (r) => r.rolloutNo);
const userNos = setOf(db.cUsers, (u) => u.cUserNo);
const orderNos = setOf(db.orders, (o) => o.orderNo);
const workOrderNos = setOf(db.workOrders, (w) => w.woNo);
const refundNos = setOf(db.refundRecords, (r) => r.refundNo);
const alarmNos = setOf(db.alarmRecords, (a) => a.alarmNo);
const alarmCodeValues = setOf(db.alarmCodes, (a) => a.code);
const packageNos = setOf(db.rechargePackages, (p) => p.packageNo);
const channelCodes = setOf(db.paymentChannels, (c) => c.channelCode);
const templateNos = setOf(db.notifyTemplates, (t) => t.templateNo);
const adNos = setOf(db.adCampaigns, (a) => a.adNo);
const slotNos = setOf(db.adSlots, (s) => s.slotNo);
const tenantNos = setOf(db.tenants, (t) => t.tenantNo);
const employeeNos = setOf(db.employees, (e) => e.employeeNo);
// 分成方（payeeName）可以是场地方或代理商——两套主数据的并集
const payeeNames = new Set([...venueNames, ...agentNames]);

// ————————————————————————————————————————————————————————————————
// 引用关系表
// ————————————————————————————————————————————————————————————————
type Ref = {
  /** 引用方数组名（出现在失败信息里） */
  from: string;
  rows: readonly Record<string, unknown>[];
  /** 引用方字段名 */
  field: string;
  /** 被引用的主数据描述，如 `agents.agentNo` */
  to: string;
  values: ReadonlySet<string>;
  /** 允许为空（null/""）——必须在这里写清楚为什么可以为空 */
  nullableReason?: string;
  /** 显式白名单：值 → 理由。每条都要有理由，否则等于把问题藏起来 */
  allow?: Readonly<Record<string, string>>;
};

const ref = (
  from: string,
  rows: readonly unknown[],
  field: string,
  to: string,
  values: ReadonlySet<string>,
  extra: Omit<Ref, "from" | "rows" | "field" | "to" | "values"> = {},
): Ref => ({ from, rows: rows as readonly Record<string, unknown>[], field, to, values, ...extra });

const REFS: Ref[] = [
  // —— 代理商 ——
  ref("agentCommissions", db.agentCommissions, "agentNo", "agents.agentNo", agentNos),
  ref("agentCommissions", db.agentCommissions, "agentName", "agents.name", agentNames),
  ref("agentAssignments", db.agentAssignments, "agentNo", "agents.agentNo", agentNos),
  ref("agentAssignments", db.agentAssignments, "agentName", "agents.name", agentNames),
  ref("agentPerformances", db.agentPerformances, "agentNo", "agents.agentNo", agentNos),
  ref("agentAccounts", db.agentAccounts, "agentNo", "agents.agentNo", agentNos),
  ref("agentAccounts", db.agentAccounts, "agentName", "agents.name", agentNames),
  // S1 划拨流水：代理必须真实；assetNo 是机柜号或站点号的并集（assetType 区分是哪种）
  ref("agentAssignmentRecords", db.agentAssignmentRecords, "agentNo", "agents.agentNo", agentNos),
  ref("agentAssignmentRecords", db.agentAssignmentRecords, "agentName", "agents.name", agentNames),
  ref("agentAssignmentRecords", db.agentAssignmentRecords, "assetNo", "cabinets.cabinetNo ∪ sites.siteNo",
    new Set([...cabinetNos, ...siteNos])),

  // —— 场所 ——
  ref("sites", db.sites, "agentNo", "agents.agentNo", agentNos, {
    nullableReason: "直营站点没有代理商（Site.agentNo 类型即为 string | null）",
  }),
  ref("sites", db.sites, "venueName", "venues.name", venueNames),
  // 台账 M11：站点的区域曾经存的是"Dubai North"这类字典里不存在的名字，现改为存 regions 字典 ID
  ref("sites", db.sites, "regionId", "regions.regionId", new Set(db.regions.map((r) => r.regionId))),
  ref("sites", db.sites, "regionName", "regions.name", new Set(db.regions.map((r) => r.name))),
  ref("locations", db.locations, "siteNo", "sites.siteNo", siteNos),
  ref("locations", db.locations, "siteName", "sites.name", siteNames),
  ref("contracts", db.contracts, "siteName", "sites.name", siteNames),
  ref("contracts", db.contracts, "venueName", "venues.name", venueNames),
  ref("siteAnalyses", db.siteAnalyses, "siteNo", "sites.siteNo", siteNos),
  ref("siteAnalyses", db.siteAnalyses, "siteName", "sites.name", siteNames),
  ref("siteLifecycles", db.siteLifecycles, "siteNo", "sites.siteNo", siteNos),
  ref("siteLifecycles", db.siteLifecycles, "siteName", "sites.name", siteNames),
  ref("venueOnboardings", db.venueOnboardings, "venueName", "venues.name", venueNames, {
    allow: {
      "Al Barsha Mall": "入驻审核里的申请方按定义尚未成为场地方，审核通过后才会进 venues",
      "Dragon Mart 2": "同上（APPROVED 但 mock 未演示落库，保持审核队列独立）",
      "Dune Hotel": "同上（REJECTED，永远不会进 venues）",
      "City Walk Shops": "同上（PENDING）",
    },
  }),
  ref("leads", db.leads, "venueName", "venues.name", venueNames, {
    allow: {
      "Dubai Marina Mall": "BD 线索是尚未签约的潜在场地方，签约后才会成为 venue",
      "The Dubai Fountain": "同上",
      "Global Village": "同上",
    },
  }),

  // —— 设备 ——
  ref("cabinets", db.cabinets, "locationNo", "locations.locationNo", locationNos),
  ref("cabinets", db.cabinets, "locationName", "sites.name", siteNames),
  ref("cabinets", db.cabinets, "vendorCode", "vendors.vendorCode", vendorCodes),
  // 偏差 A1 补齐（S5）：机柜的归属站点。后端实体有、DDL 还没有 site_no 列（见清单 §一 A1），
  // 但既然前端展示了，就必须指向真实站点——否则「归属站点」点过去查无此站
  ref("cabinets", db.cabinets, "siteNo", "sites.siteNo", siteNos, {
    nullableReason: "到货未上架的机柜没有点位、也就没有归属站点（Cabinet.siteNo 类型即为 string | null）",
  }),
  // 偏差 A1 补齐：机柜的归属代理（划拨的落点）
  ref("cabinets", db.cabinets, "agentNo", "agents.agentNo", agentNos, {
    nullableReason: "平台直营机柜没有代理商（Cabinet.agentNo 类型即为 string | null，回收后也会置 null）",
  }),
  ref("powerbanks", db.powerbanks, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("cabinetMonitors", db.cabinetMonitors, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("cabinetMonitors", db.cabinetMonitors, "locationName", "sites.name", siteNames),
  ref("commandRecords", db.commandRecords, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("deviceLogs", db.deviceLogs, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("deviceLogs", db.deviceLogs, "vendorCode", "vendors.vendorCode", vendorCodes),
  ref("deviceCodeBatches", db.deviceCodeBatches, "vendorCode", "vendors.vendorCode", vendorCodes),
  ref("otaRollouts", db.otaRollouts, "vendorCode", "vendors.vendorCode", vendorCodes),
  // 固件 OTA 三层：投放投的必须是版本库里真有的版本，任务必须挂在真投放与真机柜上
  ref("otaRollouts", db.otaRollouts, "fwVersion", "otaReleases.version", fwVersions),
  ref("otaReleases", db.otaReleases, "vendorCode", "vendors.vendorCode", vendorCodes, {
    nullableReason: "通用固件不限供应商（OtaRelease.vendorCode 类型即为 string | null，各厂商投放都能引用）",
  }),
  ref("otaTasks", db.otaTasks, "rolloutNo", "otaRollouts.rolloutNo", rolloutNos),
  ref("otaTasks", db.otaTasks, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("otaTasks", db.otaTasks, "previousVersion", "otaReleases.version", fwVersions),
  ref("inventoryTransfers", db.inventoryTransfers, "fromLocation", "sites.name", siteNames),
  ref("inventoryTransfers", db.inventoryTransfers, "toLocation", "sites.name", siteNames),

  // —— 告警 / 工单 ——
  ref("alarmRecords", db.alarmRecords, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("alarmRecords", db.alarmRecords, "siteName", "sites.name", siteNames),
  ref("alarmRecords", db.alarmRecords, "vendorCode", "vendors.vendorCode", vendorCodes),
  ref("alarmRecords", db.alarmRecords, "alarmCode", "alarmCodes.code", alarmCodeValues),
  ref("alarmRecords", db.alarmRecords, "workOrderNo", "workOrders.woNo", workOrderNos, {
    nullableReason: "未转工单的告警（status=OPEN）没有关联工单号",
  }),
  ref("alarmNotices", db.alarmNotices, "alarmNo", "alarmRecords.alarmNo", alarmNos),
  ref("alarmRules", db.alarmRules, "alarmCode", "alarmCodes.code", alarmCodeValues),
  ref("workOrders", db.workOrders, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("workOrders", db.workOrders, "locationName", "sites.name", siteNames),

  // —— 订单 ——
  ref("orders", db.orders, "cUserNo", "cUsers.cUserNo", userNos),
  ref("orders", db.orders, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("orders", db.orders, "returnCabinetNo", "cabinets.cabinetNo", cabinetNos, {
    nullableReason: "未归还的订单（IN_USE/CREATED/EXCEPTION）没有归还柜机",
  }),
  ref("orders", db.orders, "powerbankNo", "powerbanks.powerbankNo", powerbankNos),
  ref("orders", db.orders, "locationName", "sites.name", siteNames),
  ref("orderExceptions", db.orderExceptions, "orderNo", "orders.orderNo", orderNos),
  ref("orderExceptions", db.orderExceptions, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("orderExceptions", db.orderExceptions, "userNo", "cUsers.cUserNo", userNos),
  // S2 异常单处置：转工单落的是**真实工单**（不是编出来的号），退款号同理挂在退款队列上
  ref("orderExceptions", db.orderExceptions, "workOrderNo", "workOrders.woNo", workOrderNos, {
    nullableReason: "未转工单的异常单没有工单号（待处置 / 直接关闭 / 只发起了退款）",
  }),
  ref("orderExceptions", db.orderExceptions, "refundNo", "refundRecords.refundNo", refundNos, {
    nullableReason: "未发起退款的异常单没有退款号",
  }),
  ref("depositRecords", db.depositRecords, "orderNo", "orders.orderNo", orderNos),
  ref("depositRecords", db.depositRecords, "userNo", "cUsers.cUserNo", userNos),
  ref("reservations", db.reservations, "userNo", "cUsers.cUserNo", userNos),
  ref("reservations", db.reservations, "siteNo", "sites.siteNo", siteNos),
  ref("reservations", db.reservations, "siteName", "sites.name", siteNames),
  ref("reservations", db.reservations, "cabinetNo", "cabinets.cabinetNo", cabinetNos, {
    nullableReason: "站点级预约不指定机柜（到店任选一台）",
  }),
  ref("reservations", db.reservations, "orderNo", "orders.orderNo", orderNos, {
    nullableReason: "只有已履约（FULFILLED）的预约才会生成订单",
  }),
  ref("freeOrders", db.freeOrders, "orderNo", "orders.orderNo", orderNos),
  ref("freeOrders", db.freeOrders, "userNo", "cUsers.cUserNo", userNos),
  ref("freeOrders", db.freeOrders, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("freeOrders", db.freeOrders, "siteName", "sites.name", siteNames),

  // —— 客服 / 售后 ——
  ref("csTickets", db.csTickets, "userNo", "cUsers.cUserNo", userNos),
  ref("csTickets", db.csTickets, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("csSessions", db.csSessions, "userNo", "cUsers.cUserNo", userNos),
  ref("orderComplaints", db.orderComplaints, "orderNo", "orders.orderNo", orderNos),
  ref("orderComplaints", db.orderComplaints, "userNo", "cUsers.cUserNo", userNos),
  ref("orderComplaints", db.orderComplaints, "workOrderNo", "workOrders.woNo", workOrderNos, {
    nullableReason: "非设备类投诉不转工单",
  }),
  ref("refundRecords", db.refundRecords, "orderNo", "orders.orderNo", orderNos),
  ref("refundRecords", db.refundRecords, "userNo", "cUsers.cUserNo", userNos),

  // —— 用户 ——
  ref("members", db.members, "userNo", "cUsers.cUserNo", userNos),
  ref("wallets", db.wallets, "userNo", "cUsers.cUserNo", userNos),
  ref("freeWhitelist", db.freeWhitelist, "userNo", "cUsers.cUserNo", userNos),
  ref("userRisks", db.userRisks, "userNo", "cUsers.cUserNo", userNos),
  ref("userBlacklist", db.userBlacklist, "userNo", "cUsers.cUserNo", userNos),
  // S2 信用分变更留痕：调的必须是真实存在的 C 端用户
  ref("creditScoreChanges", db.creditScoreChanges, "cUserNo", "cUsers.cUserNo", userNos),

  // —— 财务 ——
  ref("ledger", db.ledger, "orderNo", "orders.orderNo", orderNos),
  ref("shareRecords", db.shareRecords, "orderNo", "orders.orderNo", orderNos),
  ref("shareRecords", db.shareRecords, "payeeName", "venues.name ∪ agents.name", payeeNames),
  ref("shareRecords", db.shareRecords, "payeeNo", "venues.venueNo ∪ agents.agentNo",
    new Set([...venueNos, ...agentNos])),
  ref("shareRules", db.shareRules, "payeeName", "venues.name ∪ agents.name", payeeNames),
  ref("settlements", db.settlements, "payeeName", "venues.name ∪ agents.name", payeeNames),
  // 结算单的对象号：结算单与分润明细靠 (payeeType, payeeNo, period) 对齐，号对不上就汇总不出金额
  ref("settlements", db.settlements, "payeeNo", "venues.venueNo ∪ agents.agentNo",
    new Set([...venueNos, ...agentNos])),
  ref("withdrawals", db.withdrawals, "payeeName", "venues.name ∪ agents.name", payeeNames),
  ref("invoices", db.invoices, "payeeName", "venues.name ∪ agents.name", payeeNames),
  // S2：发票金额不自造，一律挂在一张结算单上——来源单号必须是真实结算单
  ref("invoices", db.invoices, "sourceNo", "settlements.settleNo",
    new Set(db.settlements.map((s) => s.settleNo))),
  ref("shareSummaries", db.shareSummaries, "payeeNo", "venues.venueNo ∪ agents.agentNo",
    new Set([...venueNos, ...agentNos])),
  ref("shareSummaries", db.shareSummaries, "payeeName", "venues.name ∪ agents.name", payeeNames),
  ref("rechargeOrders", db.rechargeOrders, "userNo", "cUsers.cUserNo", userNos),
  ref("rechargeOrders", db.rechargeOrders, "packageNo", "rechargePackages.packageNo", packageNos, {
    nullableReason: "自定义金额充值不走套餐（RechargeOrder.packageNo 类型即为 string | null）",
  }),
  ref("rechargeOrders", db.rechargeOrders, "channelCode", "paymentChannels.channelCode", channelCodes),

  // —— 营销 ——
  ref("adSlots", db.adSlots, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("adDeliveries", db.adDeliveries, "adNo", "adCampaigns.adNo", adNos),
  ref("adDeliveries", db.adDeliveries, "slotNo", "adSlots.slotNo", slotNos),
  // S2 优惠券发放流水：券号/券名必须指向真实的券，否则「发放记录」点回去查无此券
  ref("couponIssueRecords", db.couponIssueRecords, "couponNo", "coupons.couponNo",
    setOf(db.coupons, (c) => c.couponNo)),
  ref("couponIssueRecords", db.couponIssueRecords, "couponName", "coupons.name",
    setOf(db.coupons, (c) => c.name)),

  // —— 系统 / 组织 ——
  ref("notifyLogs", db.notifyLogs, "templateNo", "notifyTemplates.templateNo", templateNos),
  ref("tenantConfigs", db.tenantConfigs, "tenantNo", "tenants.tenantNo", tenantNos),
  ref("staffPerformances", db.staffPerformances, "employeeNo", "employees.employeeNo", employeeNos),

  // —— 报表 / 工作台 ——
  ref("reportDevices", db.reportDevices, "locationName", "sites.name", siteNames),
  ref("reportLocations", db.reportLocations, "siteName", "sites.name", siteNames),
  ref("reportCustoms", db.reportCustoms, "dim", "sites.name", siteNames),
  ref("dashboard.rankings", db.dashboard.rankings, "siteName", "sites.name", siteNames),
  ref("dashboard.alerts", db.dashboard.alerts, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  // 适用范围（ADR-028）：定位键是 scopeType + scopeRef，按层指向各自的表。
  // 悬空的范围行取价永远命中不到，而且不报错 —— 与旧的「按名字挂规则」是同一类风险。
  ref("planScopes(SITE)", db.planScopes.filter((x) => x.scopeType === "SITE"),
    "scopeRef", "sites.siteNo", siteNos),
  ref("planScopes(LOCATION)", db.planScopes.filter((x) => x.scopeType === "LOCATION"),
    "scopeRef", "locations.locationNo", locationNos),
  ref("planScopes(SCENE)", db.planScopes.filter((x) => x.scopeType === "SCENE"),
    "scopeRef", "sites.sceneType", setOf(db.sites, (s) => s.sceneType)),
  ref("planScopes.planNo", db.planScopes, "planNo", "pricePlans.planNo",
    setOf(db.pricePlans, (p) => p.planNo)),
];

/** 逐条比对，返回可读的违规清单（空数组 = 通过）。 */
function violationsOf(r: Ref): string[] {
  const bad: string[] = [];
  r.rows.forEach((row, i) => {
    const v = row[r.field];
    if (v === null || v === undefined || v === "") {
      if (r.nullableReason) return;
      bad.push(`${r.from}[${i}].${r.field} = ${JSON.stringify(v)}（该字段不允许为空，若确实可空请在 REFS 里写明 nullableReason）`);
      return;
    }
    if (typeof v !== "string") {
      bad.push(`${r.from}[${i}].${r.field} 不是字符串：${JSON.stringify(v)}`);
      return;
    }
    if (r.values.has(v)) return;
    if (r.allow && v in r.allow) return;
    bad.push(`${r.from}[${i}].${r.field} = "${v}" 不存在于 ${r.to}`);
  });
  return bad;
}

describe("mock 引用完整性", () => {
  it.each(REFS.map((r) => [`${r.from}.${r.field} → ${r.to}`, r] as const))(
    "%s",
    (_name, r) => {
      const bad = violationsOf(r);
      expect(bad, `\n${bad.join("\n")}\n`).toEqual([]);
    },
  );

  it("引用关系表本身有覆盖度（防止有人删条目让测试变哑巴）", () => {
    expect(REFS.length).toBeGreaterThanOrEqual(80);
    // 白名单每条必须带理由（非空字符串）
    for (const r of REFS) {
      for (const [value, reason] of Object.entries(r.allow ?? {})) {
        expect(reason, `${r.from}.${r.field} 的白名单项 "${value}" 缺少理由`).toBeTruthy();
      }
    }
  });

  it("slotsOf() 返回的仓位充电宝号都存在于 powerbanks", () => {
    const bad: string[] = [];
    for (const cab of db.cabinets) {
      slotsOf(cab.cabinetNo).forEach((s, i) => {
        if (s.powerbankNo && !powerbankNos.has(s.powerbankNo)) {
          bad.push(`slotsOf("${cab.cabinetNo}")[${i}].powerbankNo = "${s.powerbankNo}" 不存在于 powerbanks.powerbankNo`);
        }
      });
    }
    expect(bad, `\n${bad.join("\n")}\n`).toEqual([]);
  });

  // 投放行上的百分比若与抽屉里的逐台进度对不上，运营会以为页面坏了——故把「均值」这条约束钉住。
  it("otaRollouts.progress 等于其逐设备任务进度的均值，且每次投放都有任务", () => {
    const bad: string[] = [];
    for (const r of db.otaRollouts) {
      const mine = db.otaTasks.filter((t) => t.rolloutNo === r.rolloutNo);
      if (mine.length === 0) { bad.push(`${r.rolloutNo} 没有任何逐设备任务（抽屉点开会是空的）`); continue; }
      const avg = Math.round(mine.reduce((n, t) => n + t.progress, 0) / mine.length);
      if (avg !== r.progress) bad.push(`${r.rolloutNo}.progress = ${r.progress}，但 ${mine.length} 个任务均值是 ${avg}`);
    }
    expect(bad, `\n${bad.join("\n")}\n`).toEqual([]);
  });

  // A1：机柜的归属站点不是独立维护的字段，而是「点位所属站点」的投影。
  // 一旦有人手改其中一个，台账上就会出现「点位在 A 站、归属写 B 站」这种自相矛盾的行。
  it("cabinets.siteNo 与其 locationNo 所属站点一致，且站点名与 locationName 对得上", () => {
    const bad: string[] = [];
    db.cabinets.forEach((c, i) => {
      const loc = db.locations.find((l) => l.locationNo === c.locationNo) ?? null;
      const expected = loc?.siteNo ?? null;
      if ((c.siteNo ?? null) !== expected) {
        bad.push(`cabinets[${i}](${c.cabinetNo}).siteNo = ${JSON.stringify(c.siteNo)}，但点位 ${c.locationNo} 属于 ${JSON.stringify(expected)}`);
      }
      if (loc && c.locationName && loc.siteName !== c.locationName) {
        bad.push(`cabinets[${i}](${c.cabinetNo}).locationName = "${c.locationName}"，但点位 ${c.locationNo} 所属站点名是 "${loc.siteName}"`);
      }
    });
    expect(bad, `\n${bad.join("\n")}\n`).toEqual([]);
  });

  it("deviceLogs.payload 里内嵌的业务号都真实存在", () => {
    const bad: string[] = [];
    db.deviceLogs.forEach((log, i) => {
      const pl = JSON.parse(log.payload) as Record<string, unknown>;
      const check = (field: string, values: ReadonlySet<string>, to: string) => {
        const v = pl[field];
        if (typeof v === "string" && !values.has(v)) {
          bad.push(`deviceLogs[${i}].payload.${field} = "${v}" 不存在于 ${to}`);
        }
      };
      check("cabinetNo", cabinetNos, "cabinets.cabinetNo");
      check("orderNo", orderNos, "orders.orderNo");
      check("powerbankNo", powerbankNos, "powerbanks.powerbankNo");
    });
    expect(bad, `\n${bad.join("\n")}\n`).toEqual([]);
  });
});

describe("编号前缀不撞车（台账 M9/M10）", () => {
  // 同一前缀只能有一个含义，否则「按号搜索」会跨页搜出无关记录。
  const prefixOf = (no: string) => /^([A-Za-z_]+)/.exec(no)?.[1] ?? no;
  const owners: [string, string[]][] = [
    ["PB（充电宝）", db.powerbanks.map((x) => x.powerbankNo)],
    ["ISS（问题管理）", db.problems.map((x) => x.problemNo)],
    ["BL（用户黑名单）", db.userBlacklist.map((x) => x.blacklistNo)],
    ["NBL（触达拉黑）", db.notifyBlacklist.map((x) => x.blockNo)],
  ];
  it.each(owners)("%s 前缀唯一", (_label, nos) => {
    expect(new Set(nos.map(prefixOf)).size).toBe(1);
  });
  it("PB / ISS / BL / NBL 四组编号互不重叠", () => {
    const all = owners.flatMap(([, nos]) => nos);
    expect(new Set(all).size).toBe(all.length);
    const prefixes = owners.map(([, nos]) => prefixOf(nos[0]));
    expect(new Set(prefixes).size).toBe(prefixes.length);
  });
});
