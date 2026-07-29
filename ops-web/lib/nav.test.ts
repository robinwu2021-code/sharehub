// nav 三级导航纯函数单测 —— 场景对照 TDD-运营端三级导航.md §4.2 + 附录A.9 矩阵。
import { describe, it, expect } from "vitest";
import {
  NAV,
  visibleDomains, visibleModules, visibleLeaves,
  findActiveModule, activeLeafIndex, isDomainSoon, isSingleModuleDomain,
  moduleDefaultHref, domainDefaultHref, breadcrumb, leafParts, normPath,
  isLeafLocked, isModuleLocked, isLeafDisabled, routeLockedPhase, groupedLeaves,
} from "./nav";
import { isPhaseLocked, CURRENT_PHASE } from "./phase";
import { hasNavLabel } from "./i18n/nav-labels";
import type { Role } from "./auth";

const domain = (key: string) => {
  const d = NAV.find((x) => x.key === key);
  if (!d) throw new Error(`domain ${key} missing`);
  return d;
};
const module_ = (dKey: string, mKey: string) => {
  const m = domain(dKey).modules.find((x) => x.key === mKey);
  if (!m) throw new Error(`module ${mKey} missing`);
  return m;
};
const domainKeys = (role: Role) => visibleDomains(role).map((d) => d.key);

describe("A.9 角色×域可见性矩阵（抽查）", () => {
  it("ADMIN 见全部 7 域", () => {
    expect(domainKeys("ADMIN")).toEqual([
      "overview", "device-ops", "place-bd", "trade-fin", "user-growth", "analytics", "system",
    ]);
  });
  it("VIEWER 不见 用户与服务/系统与权限", () => {
    const keys = domainKeys("VIEWER");
    expect(keys).not.toContain("user-growth");
    expect(keys).not.toContain("system");
    expect(keys).toEqual(expect.arrayContaining(["overview", "device-ops", "place-bd", "trade-fin", "analytics"]));
  });
  it("BD 不见 设备运营；CS 不见 渠道与场地/数据报表", () => {
    expect(domainKeys("BD")).not.toContain("device-ops");
    const cs = domainKeys("CS");
    expect(cs).not.toContain("place-bd");
    expect(cs).not.toContain("analytics");
  });
  it("FINANCE 进 系统与权限 仅见员工与权限(审计)，不见系统设置", () => {
    const mods = visibleModules(domain("system"), "FINANCE").map((m) => m.key);
    expect(mods).toEqual(["org"]);
  });
  it("AGENT 见 概览/设备运营/渠道与场地/交易与资金，不见其余", () => {
    expect(domainKeys("AGENT")).toEqual(["overview", "device-ops", "place-bd", "trade-fin"]);
  });
});

describe("L3 叶子过滤（4.2-2）", () => {
  const finance = module_("trade-fin", "finance");
  it("VIEWER 的财务：无 账务分录/对账/发票，有 规则/明细/统计/结算/提现", () => {
    const labels = visibleLeaves(finance, "VIEWER").map((l) => l.label);
    expect(labels).toEqual(["分润规则", "分润明细", "分润统计", "结算单", "提现审核"]);
  });
  it("FINANCE 的财务：8 项 + 代理分润配置/用户钱包/充值订单 三条跨域深链", () => {
    const labels = visibleLeaves(finance, "FINANCE").map((l) => l.label);
    expect(labels).toHaveLength(11);
    expect(labels).toContain("代理分润配置");
    expect(labels.at(-1)).toBe("充值订单"); // 用户账 分组殿后
  });
  it("VIEWER 无 agent:settlement:read → 财务不出现代理分润配置深链", () => {
    expect(visibleLeaves(finance, "VIEWER").map((l) => l.label)).not.toContain("代理分润配置");
  });
  it("OPS 的站点与点位：无 进场合同/站点坪效", () => {
    const labels = visibleLeaves(module_("place-bd", "location"), "OPS").map((l) => l.label);
    expect(labels).not.toContain("进场合同");
    expect(labels).not.toContain("站点坪效");
    expect(labels).toContain("站点管理");
  });
});

describe("findActiveModule 路径反推（4.2-3，含尾斜杠/最长前缀）", () => {
  it.each([
    ["/", "overview", "dashboard"],
    ["/finance", "trade-fin", "finance"],
    ["/finance/", "trade-fin", "finance"],
    ["/devices/detail", "device-ops", "device"],
    ["/system/vendors/", "system", "system"],
    ["/work-orders", "device-ops", "workorder"],
    ["/agents", "place-bd", "agent"],
  ])("%s → %s / %s", (path, dKey, mKey) => {
    const hit = findActiveModule(path);
    expect(hit?.domain.key).toBe(dKey);
    expect(hit?.module.key).toBe(mKey);
  });
  it("未知路径无归属", () => {
    expect(findActiveModule("/nope")).toBeUndefined();
  });
});

describe("activeLeafIndex 深链高亮（4.2-6）", () => {
  const financeLeaves = (role: Role) => visibleLeaves(module_("trade-fin", "finance"), role);
  it("裸 /finance 无 query → 默认首个可点叶子（分润规则）", () => {
    const leaves = financeLeaves("FINANCE");
    expect(leaves[activeLeafIndex(leaves, "/finance", null, null)].label).toBe("分润规则");
  });
  it("?tab=ledger → 账务分录 Phase 2，当前 P1 下不命中(-1)", () => {
    const leaves = financeLeaves("FINANCE");
    expect(activeLeafIndex(leaves, "/finance/", "ledger", null)).toBe(-1);
  });
  it("VIEWER 无 ledger 菜单项 → ?tab=ledger 不命中(-1)", () => {
    expect(activeLeafIndex(financeLeaves("VIEWER"), "/finance", "ledger", null)).toBe(-1);
  });
  it("工单 ?view=board → 工单看板", () => {
    const leaves = visibleLeaves(module_("device-ops", "workorder"), "OPS");
    expect(leaves[activeLeafIndex(leaves, "/work-orders", null, "board")].label).toBe("工单看板");
  });
  it("固件 OTA Phase 2 → 当前 P1 下 ?tab=ota 不命中(-1)", () => {
    const leaves = visibleLeaves(module_("device-ops", "device"), "OPS");
    expect(activeLeafIndex(leaves, "/devices", "ota", null)).toBe(-1);
  });
});

describe("域形态（4.2-5 / D2）", () => {
  it("概览 = 单模块域（无 L2 面板）；analytics 有子功能不算单模块域", () => {
    expect(isSingleModuleDomain(domain("overview"))).toBe(true);
    expect(isSingleModuleDomain(domain("analytics"))).toBe(false);
    expect(isSingleModuleDomain(domain("trade-fin"))).toBe(false);
  });
  it("analytics 报表已建（OPS 视角）→ 非待建；device-ops 非待建", () => {
    expect(isDomainSoon(domain("analytics"), "OPS")).toBe(false);
    expect(isDomainSoon(domain("device-ops"), "OPS")).toBe(false);
  });
});

describe("默认落地与面包屑", () => {
  it("域默认落地 = 首个可点模块的首个可点叶子", () => {
    expect(domainDefaultHref(domain("trade-fin"), "FINANCE")).toBe("/orders");
    expect(domainDefaultHref(domain("device-ops"), "OPS")).toBe("/devices");
    // CS 无 place-bd；FINANCE 的 place-bd 首模块 location → sites
    expect(domainDefaultHref(domain("place-bd"), "FINANCE")).toBe("/locations?tab=venues");
  });
  it("cs 模块：报障受理/客服会话为 Phase 2，P1 下首个可点叶子 = 退款/补偿(/orders)", () => {
    expect(moduleDefaultHref(module_("user-growth", "cs"), "CS")).toBe("/orders");
  });
  it("面包屑：/finance?tab=withdrawals → 提现审核为 Phase 2，P1 只到模块层", () => {
    expect(breadcrumb("/finance/", "withdrawals", null, "FINANCE")).toEqual(["交易与资金", "财务管理"]);
  });
  it("面包屑：概览单模块域只有一级", () => {
    expect(breadcrumb("/", null, null, "ADMIN")).toEqual(["概览"]);
  });
});

describe("L3 分组（按机构/对象聚类，2026-07-29 结构优化）", () => {
  it("同名 group 的叶子必须在数据里相邻（否则渲染会出现重复小标题）", () => {
    const broken: string[] = [];
    for (const d of NAV) {
      for (const m of d.modules) {
        const segs = groupedLeaves(m.children ?? []);
        const named = segs.map((s) => s.group).filter(Boolean) as string[];
        if (named.length !== new Set(named).size) broken.push(`${d.label}/${m.label}`);
      }
    }
    expect(broken).toEqual([]);
  });
  it("模块内要么全部叶子有 group，要么全部没有（不混用）", () => {
    const mixed: string[] = [];
    for (const d of NAV) {
      for (const m of d.modules) {
        const kids = m.children ?? [];
        if (!kids.length) continue;
        const withG = kids.filter((l) => l.group).length;
        if (withG !== 0 && withG !== kids.length) mixed.push(`${d.label}/${m.label}`);
      }
    }
    expect(mixed).toEqual([]);
  });
  it("财务模块按资金主体分四组：分润与结算/平台账/伙伴账/用户账", () => {
    const segs = groupedLeaves(visibleLeaves(module_("trade-fin", "finance"), "ADMIN"));
    expect(segs.map((s) => s.group)).toEqual(["分润与结算", "平台账", "伙伴账", "用户账"]);
  });
  it("站点与点位分两组：场地资产（物理）/ 场地方机构（主体）", () => {
    const segs = groupedLeaves(visibleLeaves(module_("place-bd", "location"), "ADMIN"));
    expect(segs.map((s) => s.group)).toEqual(["场地资产", "场地方机构"]);
  });
  it("RBAC 过滤后不产生空组（VIEWER 看财务）", () => {
    const segs = groupedLeaves(visibleLeaves(module_("trade-fin", "finance"), "VIEWER"));
    expect(segs.every((s) => s.leaves.length > 0)).toBe(true);
  });
  it("groupedLeaves 对无 group 的模块返回单段且 group 为 undefined", () => {
    const segs = groupedLeaves(visibleLeaves(module_("analytics", "report"), "ADMIN"));
    expect(segs).toHaveLength(1);
    expect(segs[0].group).toBeUndefined();
  });
});

describe("三语覆盖（防新增菜单漏配 en/ar）", () => {
  it("NAV 中每个 域/模块/子功能 标签都有 en+ar 译文", () => {
    const missing: string[] = [];
    for (const d of NAV) {
      if (!hasNavLabel(d.label)) missing.push(`域 ${d.label}`);
      for (const m of d.modules) {
        if (!hasNavLabel(m.label)) missing.push(`模块 ${m.label}`);
        for (const l of m.children ?? []) {
          if (!hasNavLabel(l.label)) missing.push(`子功能 ${l.label}`);
          if (l.group && !hasNavLabel(l.group)) missing.push(`分组 ${l.group}`);
        }
      }
    }
    expect(missing).toEqual([]);
  });
});

describe("工具函数", () => {
  it("normPath 归一化尾斜杠", () => {
    expect(normPath("/finance/")).toBe("/finance");
    expect(normPath("/")).toBe("/");
  });
  it("leafParts 拆 tab/view", () => {
    expect(leafParts("/finance?tab=rules")).toEqual({ path: "/finance", tab: "rules", view: null });
    expect(leafParts("/work-orders?view=board").view).toBe("board");
  });
});

describe("分期屏蔽（phase gating，默认 CURRENT_PHASE=1）", () => {
  it("CURRENT_PHASE 测试环境为 1（MVP）", () => {
    expect(CURRENT_PHASE).toBe(1);
  });
  it("isPhaseLocked：P2/P3 锁，P1/undefined 不锁", () => {
    expect(isPhaseLocked(2)).toBe(true);
    expect(isPhaseLocked(3)).toBe(true);
    expect(isPhaseLocked(1)).toBe(false);
    expect(isPhaseLocked(undefined)).toBe(false);
  });
  it("isLeafLocked / isLeafDisabled：固件 OTA(P2) 被锁且不可点", () => {
    const ota = module_("device-ops", "device").children!.find((l) => l.href.includes("ota"))!;
    expect(isLeafLocked(ota)).toBe(true);
    expect(isLeafDisabled(ota)).toBe(true);
  });
  it("isModuleLocked：用户/报表模块 P1 下全叶被锁 → 模块锁", () => {
    expect(isModuleLocked(module_("user-growth", "user"), "ADMIN")).toBe(true);
    expect(isModuleLocked(module_("analytics", "report"), "ADMIN")).toBe(true);
  });
  it("isModuleLocked：营销模块因「公告管理」为 P1 → 不再整体锁定（补齐清单 E1，c-app 首页公告条需要发布口）", () => {
    expect(isModuleLocked(module_("user-growth", "marketing"), "ADMIN")).toBe(false);
    expect(moduleDefaultHref(module_("user-growth", "marketing"), "ADMIN")).toBe("/marketing?tab=notices");
  });
  it("isModuleLocked：设备模块含 P1 叶 → 不锁", () => {
    expect(isModuleLocked(module_("device-ops", "device"), "ADMIN")).toBe(false);
  });
  it("routeLockedPhase：/devices?tab=ota → 被 P2 锁", () => {
    expect(routeLockedPhase("/devices", "ota", null, "ADMIN")).toBe(2);
  });
  it("routeLockedPhase：/devices（台账 P1）→ 不锁", () => {
    expect(routeLockedPhase("/devices", null, null, "ADMIN")).toBeUndefined();
  });
  it("routeLockedPhase：/marketing 首页（首叶 优惠券 P2）→ 被 P2 锁", () => {
    expect(routeLockedPhase("/marketing", null, null, "ADMIN")).toBe(2);
  });
  it("routeLockedPhase：设备详情等非叶路由 → 不锁（透传页面）", () => {
    expect(routeLockedPhase("/devices/detail", null, null, "ADMIN")).toBeUndefined();
  });
  it("moduleDefaultHref：跳过被锁叶，落到首个可点叶（finance→分润规则）", () => {
    expect(moduleDefaultHref(module_("trade-fin", "finance"), "ADMIN")).toBe("/finance?tab=rules");
  });
});
