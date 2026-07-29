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
const userNos = setOf(db.cUsers, (u) => u.cUserNo);
const orderNos = setOf(db.orders, (o) => o.orderNo);
const workOrderNos = setOf(db.workOrders, (w) => w.woNo);
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

  // —— 场所 ——
  ref("sites", db.sites, "agentNo", "agents.agentNo", agentNos, {
    nullableReason: "直营站点没有代理商（Site.agentNo 类型即为 string | null）",
  }),
  ref("sites", db.sites, "venueName", "venues.name", venueNames),
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
  ref("powerbanks", db.powerbanks, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("cabinetMonitors", db.cabinetMonitors, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("cabinetMonitors", db.cabinetMonitors, "locationName", "sites.name", siteNames),
  ref("commandRecords", db.commandRecords, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("deviceLogs", db.deviceLogs, "cabinetNo", "cabinets.cabinetNo", cabinetNos),
  ref("deviceLogs", db.deviceLogs, "vendorCode", "vendors.vendorCode", vendorCodes),
  ref("deviceCodeBatches", db.deviceCodeBatches, "vendorCode", "vendors.vendorCode", vendorCodes),
  ref("otaRollouts", db.otaRollouts, "vendorCode", "vendors.vendorCode", vendorCodes),
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

  // —— 财务 ——
  ref("ledger", db.ledger, "orderNo", "orders.orderNo", orderNos),
  ref("shareRecords", db.shareRecords, "orderNo", "orders.orderNo", orderNos),
  ref("shareRecords", db.shareRecords, "payeeName", "venues.name ∪ agents.name", payeeNames),
  ref("shareRules", db.shareRules, "payeeName", "venues.name ∪ agents.name", payeeNames),
  ref("settlements", db.settlements, "payeeName", "venues.name ∪ agents.name", payeeNames),
  ref("withdrawals", db.withdrawals, "payeeName", "venues.name ∪ agents.name", payeeNames),
  ref("invoices", db.invoices, "payeeName", "venues.name ∪ agents.name", payeeNames),
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
  ref("pricingDiffs", db.pricingDiffs, "locationName", "sites.name", siteNames),
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
