// nav 三级导航纯函数单测（V2 四级账户体系）—— 对照 运营端功能清单-V2四级体系.md §四 矩阵。
import { describe, it, expect } from "vitest";
import {
  NAV,
  visibleDomains, visibleModules, visibleLeaves,
  findActiveModule, activeLeafIndex, isDomainSoon, isSingleModuleDomain,
  moduleDefaultHref, domainDefaultHref, breadcrumb, leafParts, normPath,
  isLeafLocked, isModuleLocked, isLeafDisabled, routeLockedPhase,
} from "./nav";
import { isPhaseLocked, CURRENT_PHASE } from "./phase";
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

describe("§4.1 角色×域可见性矩阵（14 域派生）", () => {
  it("ADMIN 见全部 14 域", () => {
    expect(domainKeys("ADMIN")).toEqual([
      "overview", "operations", "devices", "alerts", "orders", "topup", "members",
      "partners", "finance", "marketing", "integrations", "messaging", "analytics", "access",
    ]);
  });
  it("OPS 见 9 域（无充值/会员/财务/营销/集成）", () => {
    expect(domainKeys("OPS")).toEqual([
      "overview", "operations", "devices", "alerts", "orders", "partners", "messaging", "analytics", "access",
    ]);
  });
  it("CS 见 8 域（无运营/合作伙伴/财务/集成/报表/系统权限）", () => {
    expect(domainKeys("CS")).toEqual([
      "overview", "devices", "alerts", "orders", "topup", "members", "marketing", "messaging",
    ]);
  });
  it("FINANCE 见 9 域（无设备/告警/营销/集成/消息）", () => {
    expect(domainKeys("FINANCE")).toEqual([
      "overview", "operations", "orders", "topup", "members", "partners", "finance", "analytics", "access",
    ]);
  });
  it("BD 见 6 域（拓展/营销/财务分润/报表）", () => {
    expect(domainKeys("BD")).toEqual([
      "overview", "operations", "partners", "finance", "marketing", "analytics",
    ]);
  });
  it("VIEWER 见 7 域，不见 会员/系统权限/营销/集成/消息/告警/充值", () => {
    const keys = domainKeys("VIEWER");
    expect(keys).toEqual(["overview", "operations", "devices", "orders", "partners", "finance", "analytics"]);
    expect(keys).not.toContain("members");
    expect(keys).not.toContain("access");
  });
  it("AGENT 见 7 域（自己范围：设备/告警工单/订单/合作/财务）", () => {
    expect(domainKeys("AGENT")).toEqual([
      "overview", "operations", "devices", "alerts", "orders", "partners", "finance",
    ]);
  });
  it("FINANCE 进 系统权限 仅见 员工与权限(org)，不见系统设置(system)", () => {
    expect(visibleModules(domain("access"), "FINANCE").map((m) => m.key)).toEqual(["org"]);
  });
});

describe("A7 空模块过滤（canModule 命中但无可见叶 → 不渲染）", () => {
  it("VIEWER 的财务只保留 分润结算 模块（平台/运营商/商户/会员/对账 全空被过滤）", () => {
    expect(visibleModules(domain("finance"), "VIEWER").map((m) => m.key)).toEqual(["fin_share"]);
  });
  it("FINANCE 的财务保留全部 6 个模块", () => {
    expect(visibleModules(domain("finance"), "FINANCE").map((m) => m.key)).toEqual([
      "fin_platform", "fin_operator", "fin_merchant", "fin_member", "fin_share", "fin_recon",
    ]);
  });
});

describe("L3 叶子过滤（§三）", () => {
  it("VIEWER 分润结算：有 规则/明细/结算/提现，无 代理分润配置(跨域)", () => {
    const labels = visibleLeaves(module_("finance", "fin_share"), "VIEWER").map((l) => l.label);
    expect(labels).toEqual(["分润规则", "分润明细", "结算单", "提现审核"]);
  });
  it("FINANCE 分润结算：5 项全见（含跨域「代理分润配置」深链）", () => {
    const labels = visibleLeaves(module_("finance", "fin_share"), "FINANCE").map((l) => l.label);
    expect(labels).toHaveLength(5);
    expect(labels).toContain("代理分润配置");
  });
  it("OPS 的站点管理：无 进场合同，有 站点总览（坪效已迁数据报表）", () => {
    const labels = visibleLeaves(module_("operations", "location"), "OPS").map((l) => l.label);
    expect(labels).not.toContain("进场合同");
    expect(labels).not.toContain("站点坪效");
    expect(labels).toContain("站点总览");
  });
  it("VIEWER 见合作伙伴的「商户」，不见「代理商档案」", () => {
    const labels = visibleLeaves(module_("partners", "partner"), "VIEWER").map((l) => l.label);
    expect(labels).toEqual(["商户"]);
  });
});

describe("findActiveModule 路径反推（含尾斜杠/最长前缀/A6 基座）", () => {
  it.each([
    ["/", "overview", "dashboard"],
    ["/finance", "finance", "fin_platform"],
    ["/finance/", "finance", "fin_platform"],
    ["/orders", "orders", "order"],
    ["/marketing", "marketing", "marketing"],
    ["/devices/detail", "devices", "device"],
    ["/system/vendors/", "access", "system"],
    ["/work-orders", "alerts", "workorder"],
    ["/agents", "partners", "agent"],
    ["/partners", "partners", "partner"],
  ])("%s → %s / %s", (path, dKey, mKey) => {
    const hit = findActiveModule(path);
    expect(hit?.domain.key).toBe(dKey);
    expect(hit?.module.key).toBe(mKey);
  });
  it("未知路径无归属", () => {
    expect(findActiveModule("/nope")).toBeUndefined();
  });
});

describe("A6 同 path 多模块按 tab/view 归属消歧", () => {
  it("finance 6 模块共享 /finance：按 tab 归属", () => {
    expect(findActiveModule("/finance", "rules")?.module.key).toBe("fin_share");
    expect(findActiveModule("/finance", "records")?.module.key).toBe("fin_share");
    expect(findActiveModule("/finance", "merchant-flows")?.module.key).toBe("fin_merchant");
    expect(findActiveModule("/finance", "operator-flows")?.module.key).toBe("fin_operator");
    expect(findActiveModule("/finance", "member-flows")?.module.key).toBe("fin_member");
    expect(findActiveModule("/finance", "ledger")?.module.key).toBe("fin_recon");
    expect(findActiveModule("/finance", "withdrawals")?.module.key).toBe("fin_share");
  });
  it("orders 3 模块共享 /orders：按 tab 归属", () => {
    expect(findActiveModule("/orders", "free-users")?.module.key).toBe("freeorder");
    expect(findActiveModule("/orders", "stats-device")?.module.key).toBe("orderstats");
    expect(findActiveModule("/orders", "exceptions")?.module.key).toBe("order");
  });
  it("marketing 活动/广告屏共享 /marketing：按 tab 归属", () => {
    expect(findActiveModule("/marketing", "ad-slots")?.module.key).toBe("adscreen");
    expect(findActiveModule("/marketing", "campaigns")?.module.key).toBe("marketing");
  });
});

describe("activeLeafIndex 深链高亮", () => {
  it("/finance?tab=rules → 分润规则（fin_share 首叶）", () => {
    const leaves = visibleLeaves(module_("finance", "fin_share"), "FINANCE");
    expect(leaves[activeLeafIndex(leaves, "/finance", "rules", null)].label).toBe("分润规则");
  });
  it("?tab=ledger → 账务分录 Phase 2 现可点，正常命中高亮", () => {
    const leaves = visibleLeaves(module_("finance", "fin_recon"), "FINANCE");
    expect(leaves[activeLeafIndex(leaves, "/finance", "ledger", null)].label).toBe("账务分录");
  });
  it("VIEWER 无 对账开票 模块 → 空叶集合不命中(-1)", () => {
    const leaves = visibleLeaves(module_("finance", "fin_recon"), "VIEWER");
    expect(activeLeafIndex(leaves, "/finance", "ledger", null)).toBe(-1);
  });
  it("工单看板 P2 现可点 → ?view=board 正常命中高亮", () => {
    const leaves = visibleLeaves(module_("alerts", "workorder"), "OPS");
    expect(leaves[activeLeafIndex(leaves, "/work-orders", null, "board")].label).toBe("工单看板");
  });
  it("固件 OTA Phase 2 现可点 → ?tab=ota 正常命中高亮", () => {
    const leaves = visibleLeaves(module_("devices", "device"), "OPS");
    expect(leaves[activeLeafIndex(leaves, "/devices", "ota", null)].label).toBe("固件 OTA");
  });
});

describe("域形态", () => {
  it("概览 = 单模块域（无 L2 面板）；analytics/finance 非单模块域", () => {
    expect(isSingleModuleDomain(domain("overview"))).toBe(true);
    expect(isSingleModuleDomain(domain("analytics"))).toBe(false);
    expect(isSingleModuleDomain(domain("finance"))).toBe(false);
  });
  it("无 soon 标记 → isDomainSoon 恒为 false", () => {
    expect(isDomainSoon(domain("analytics"), "OPS")).toBe(false);
    expect(isDomainSoon(domain("devices"), "OPS")).toBe(false);
  });
});

describe("默认落地与面包屑", () => {
  it("域默认落地 = 首个非待建模块的首叶（分期 P2/P3 也可落地）", () => {
    // 平台流水 P1（PRD 财务数据 P1）→ finance 域首落地 = /finance
    expect(domainDefaultHref(domain("finance"), "FINANCE")).toBe("/finance");
    expect(domainDefaultHref(domain("devices"), "OPS")).toBe("/devices");
    expect(domainDefaultHref(domain("orders"), "OPS")).toBe("/orders");
    // FINANCE 无 poi 权限但有 venue 权限 → operations 域首个可见叶 = 门店 Onboarding
    expect(domainDefaultHref(domain("operations"), "FINANCE")).toBe("/locations?tab=onboarding");
  });
  it("moduleDefaultHref：落到首个非待建叶（fin_share→分润规则）", () => {
    expect(moduleDefaultHref(module_("finance", "fin_share"), "ADMIN")).toBe("/finance?tab=rules");
  });
  it("充值域全 P2 现可点 → 默认落地首叶(/topup)", () => {
    expect(domainDefaultHref(domain("topup"), "CS")).toBe("/topup");
  });
  it("面包屑：/finance?tab=rules → 域›模块›子功能", () => {
    expect(breadcrumb("/finance/", "rules", null, "FINANCE")).toEqual(["财务管理", "分润结算", "分润规则"]);
  });
  it("面包屑：/finance?tab=withdrawals 提现审核 P2 现可点 → 到子功能层", () => {
    expect(breadcrumb("/finance/", "withdrawals", null, "FINANCE")).toEqual(["财务管理", "分润结算", "提现审核"]);
  });
  it("面包屑：概览单模块域只有一级", () => {
    expect(breadcrumb("/", null, null, "ADMIN")).toEqual(["概览"]);
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
  it("isLeafLocked 仍标识分期(P2)，但 isLeafDisabled 不再因分期而不可点", () => {
    const ota = module_("devices", "device").children!.find((l) => l.href.includes("ota"))!;
    expect(isLeafLocked(ota)).toBe(true); // 分期查询：OTA 属 P2（> 当前 P1）
    expect(isLeafDisabled(ota)).toBe(false); // 但分期不再屏蔽点击（仅 soon 才不可点）
  });
  it("isModuleLocked：营销活动/报表模块 P1 下全叶被锁 → 模块锁", () => {
    expect(isModuleLocked(module_("marketing", "marketing"), "ADMIN")).toBe(true);
    expect(isModuleLocked(module_("analytics", "report"), "ADMIN")).toBe(true);
  });
  it("isModuleLocked：会员模块含 P1 叶（用户列表）→ 不锁", () => {
    // 甲特图真值：用户管理(C端) 属 P1，故会员/user 模块存在可点叶
    expect(isModuleLocked(module_("members", "user"), "ADMIN")).toBe(false);
  });
  it("isModuleLocked：设备模块含 P1 叶 → 不锁", () => {
    expect(isModuleLocked(module_("devices", "device"), "ADMIN")).toBe(false);
  });
  it("routeLockedPhase：/devices?tab=ota → 被 P2 锁", () => {
    expect(routeLockedPhase("/devices", "ota", null, "ADMIN")).toBe(2);
  });
  it("routeLockedPhase：/devices（台账 P1）→ 不锁", () => {
    expect(routeLockedPhase("/devices", null, null, "ADMIN")).toBeUndefined();
  });
  it("routeLockedPhase：/marketing 首页（首叶 优惠券 P3，PRD 会员营销工具 P3）→ 被 P3 锁", () => {
    expect(routeLockedPhase("/marketing", null, null, "ADMIN")).toBe(3);
  });
  it("routeLockedPhase：/work-orders 首页（工单 P2，PRD 客服工单系统 P2）→ 被 P2 锁", () => {
    expect(routeLockedPhase("/work-orders", null, "list", "ADMIN")).toBe(2);
  });
  it("routeLockedPhase：/finance 首页（平台流水 P1，PRD 财务数据 P1）→ 不锁", () => {
    expect(routeLockedPhase("/finance", null, null, "ADMIN")).toBeUndefined();
  });
  it("routeLockedPhase：/reports?tab=device（设备效率分析 PRD P3）→ 被 P3 锁", () => {
    expect(routeLockedPhase("/reports", "device", null, "ADMIN")).toBe(3);
  });
  it("routeLockedPhase：/operations 首页（基础运营全 P2）→ 被 P2 锁", () => {
    expect(routeLockedPhase("/operations", null, null, "ADMIN")).toBe(2);
  });
  it("routeLockedPhase：设备详情等非叶路由 → 不锁（透传页面）", () => {
    expect(routeLockedPhase("/devices/detail", null, null, "ADMIN")).toBeUndefined();
  });
  // 甲特图真值补正：以下三项经 PDF 原件核对为 P1（还原文档曾误标）
  it("routeLockedPhase：/users 首页（用户管理 C端 PDF P1）→ 不锁", () => {
    expect(routeLockedPhase("/users", null, null, "ADMIN")).toBeUndefined();
  });
  it("routeLockedPhase：/marketing?tab=ad-slots（充电站点广告/设备屏 PDF P1）→ 不锁", () => {
    expect(routeLockedPhase("/marketing", "ad-slots", null, "ADMIN")).toBeUndefined();
  });
  it("routeLockedPhase：/integrations 首页（Neargo 服务打通 PDF P1）→ 不锁", () => {
    expect(routeLockedPhase("/integrations", null, null, "ADMIN")).toBeUndefined();
  });
});
