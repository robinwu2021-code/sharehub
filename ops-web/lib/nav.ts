// 三级导航 SSOT：模块(L1) → 分组(L2) → 子功能(L3)。
// 依据 docs/technical/TDD-运营端三级导航.md 附录A（逐行对照，勿凭记忆增删）。
// - 2026-07-30 层级重构：删除原「域」层（7 个），把原「模块」提为 L1（15 个）。
//   原四层（域→模块→分组→叶子）压成三层；叶子的 (href,label,perm,phase,group) 一律不变。
//   动机：L1 只有 7 项时，「设备+告警+工单」「订单+计费+财务」被硬凑成一个域，上窄下深；
//         竞品简电云是 13 个 L1 的三层结构，比我们浅一层。
// - L1 可见性 = canModule(section.module)；门户 section 见 portalFor。
// - L3 可见性 = leaf.perm ? can(perms, perm) : 跟随 section。
//   ⚠️ 判权入参是**后端下发的 perms**，不是 role（D6a）。role 只剩一个用途：
//   挑门户 section（portalFor）—— 那是**分组**不是判权。
// - soon = 待建：灰显不可点，不产生 404 入口。
// - phase = 产品分期（徽章）：Phase 1=MVP T1-T3 | 2=规模化 T4-T6 | 3=生态 T7-T9。
// - ready = 就绪度（门禁）：叶子灰显 ⇔ !ready && phase > CURRENT_PHASE。
//   2026-07-30 拆分：此前 phase 一个字段兼管「第几期」和「能不能点」，导致 60 个灰叶子
//   的灰色原因（假数据 / 后端缺端点 / 只是排期靠后）无法区分。phase 现在只是徽章。
//   ⚠️ ready 认证的是**前端静态功能**，不是后端贯通 —— 见 NavLeaf.ready 的说明。
// - 深链沿用 ?tab= / ?view=；本文件为纯数据+纯函数（无 React），可单测。
import type { Role } from "./auth";
import { asViewer, can, canModule, type MaybeRole, type ViewerLike } from "./permissions";
import type { Phase } from "./phase";
import { isPhaseLocked } from "./phase";
import { flowReady, pageReady, type OperationPage } from "./backend-ready";
import { useMenuStore } from "./menu-source";

export const NAV_PREFS_STORAGE_KEY = "ops-nav-prefs";

// 布局常量（px）
export const RAIL_WIDTH = 56;
export const RAIL_EXPANDED_WIDTH = 168;
export const PANEL_WIDTH = 176; // 2026-07-30 收窄（208→176）：条目均为短词，给内容区让位

export interface NavLeaf {
  href: string; // 详情/深链（可含 ?tab= / ?view=，可跨 section）
  label: string;
  perm?: string; // 细粒度权限码；无则跟随所属 section 的 canModule
  soon?: boolean; // 待建：灰显不可点
  phase?: Phase; // 产品分期（缺省=P1）；phase > CURRENT_PHASE 时灰显不可点
  /**
   * 就绪度覆盖：本叶**前端静态功能完整且已实机验证**，无视 phase 直接解锁。
   *
   * ⚠️ **口径修正（2026-07-30）**：原文写的是「前后端已贯通并验证」，但本项目当前
   * 阶段是**纯前端 + mock，将来一键切后端**（见 CLAUDE.md 运营端技术约定）。
   * 按「前后端贯通」根本无叶可标 —— 所有验证都是对着 mock 做的，从未跑过真实后端；
   * 后端端点也只覆盖一半（押金/结算/发券/发票有，推送/信用分/对账/干预没有）。
   * 照字面标就是在代码里写假陈述，故把口径改成可诚实核验的那个。
   *
   * **标 ready 的条件**（三条全满足）：
   *   1. 该页写操作在 mock 层**真落库**（重开能读回），非伪实现
   *   2. 状态机/校验在 mock 层强制，非法迁移抛错
   *   3. **浏览器实机验证过**该功能的关键路径，不是只跑了单测
   *
   * **后端贯通状态另行追踪**：docs/technical/实现状态-证据核验清单.md
   * 切真后端前必须逐叶复核，届时 ready 的含义要再收紧一次。
   *
   * 为什么加这一维：原设计里 phase 同时承担两件事——「这是第几期的功能」和「现在能不能点」。
   * 于是 60 个叶子灰着，而灰的真实原因各不相同：有的页面是假数据、有的后端端点压根不存在、
   * 有的其实全通了只是排在 P2。三者混成一种灰色，看不出该修哪个。
   * 现在拆开：phase 只表达「第几期」（徽章），ready 表达「就绪可用」（门禁）。
   * 补完一个叶子的前后端就给它加 ready: true，解锁是逐叶推进的结果，不是一次性改环境变量。
   *
   * 注意：ready 只放宽 phase，不放宽 perm 与 soon —— 无权限仍不可见，待建仍不可点。
   */
  ready?: boolean;
  /**
   * L2 分组标题（按「机构/角色」或「对象」聚类，2026-07-29 结构优化）。
   * 渲染规则：同一 group 的连续叶子共用一个小标题；不设 group 的叶子直接平铺。
   * 约束：同 group 的叶子必须在 children 中相邻（单测 nav.test.ts 保证）。
   * 动机：对标简电云把财务按「平台/运营商/商户/会员」切成四个菜单——
   *      我们不拆 section（一页一 section 的解析约束），改用分组达到同样的「一眼看清是谁的账」。
   */
  group?: string;
  /**
   * 服务端带来的译名（`iam_menu.name_en/name_ar`）。**有就优先于 nav-labels**。
   *
   * 为什么要有：nav-labels 以**中文标签做 key**，在菜单管理里改个名字，
   * 它的翻译就静默失效 —— en/ar 回落成中文，而不报错。
   * 翻译跟着菜单走之后，改名时在同一个抽屉里改三语。
   */
  labelEn?: string;
  labelAr?: string;
}

/**
 * L1 导航项（原 NavDomain + NavModule 合并，2026-07-30）。
 * 一个 section = 一个权限模块 = 一个页面（children 是它的 tab/view 深链）。
 */
/** {@link navTabs} 的入参：字符串 = 菜单叶的 tab key；对象 = 非菜单叶的页内子视图，自带名字 */
export type PageTabSpec = string | { key: string; label: string };

export interface NavSection {
  key: string;
  label: string;
  icon: string;
  module: string; // 权限码模块前缀（canModule 过滤）
  /**
   * 跨模块 section 的全部权限模块前缀（2026-09-22，运营管理新菜单引入）。
   * 设置后 L1 可见性改为：**任一模块可见即显示**（仍由 L3 的 perm 逐叶过滤）。
   * 动机：运营管理横跨 location/pricing/finance/system/marketing 五个模块，
   * 只按单个 module 判断会让只有其中一部分权限的角色（如 CS 只能看问题管理）整个看不到。
   */
  modules?: string[];
  href: string; // section 首页
  /**
   * section 自身的权限码。**只有没有叶子的 section 才需要它**（目前仅「经营看板」）——
   * 有叶子的 section，可见性就是「有没有可见叶子」：那既是 {@link sectionDefaultHref}
   * 的语义（落到第一个可见叶子），也避免「L1 显示、点开一片空」。
   *
   * 菜单真源迁进 iam_menu 后（V67）这个字段随行落库，服务端按同一条规则过滤。
   */
  perm?: string;
  match?: string[]; // 路径归属前缀（默认 = href 的 path 部分）；如 system 归属 /system
  soon?: boolean; // 整 section 待建（页面不存在）
  phase?: Phase; // 产品分期（缺省=P1）；整 section 按期屏蔽
  pinBottom?: boolean; // Rail 固定底部
  /**
   * 专属门户：声明本 section 是某些角色的**唯一**入口。
   * 规则（见 visibleSections）：
   *  - 角色若命中任一 section 的 portalFor → **只看到这些门户 section**，通用运营项一律不出；
   *  - 其它角色**看不到**门户 section。
   * 动机：AGENT 是运营方体内的受限外部伙伴（ADR-012）。此前它靠"无 perm 的叶子跟随
   * 父模块"漏出了 SLA 管理 / 巡检计划 / BD 拓展 CRM / 押金与欠费 等运营方功能——
   * 与 §三·B G5「代理端缺位」是同一问题的两面：既没有自己的门户，又看到了不该看的。
   */
  portalFor?: Role[];
  /** 服务端带来的译名，理由同 {@link NavLeaf.labelEn}。 */
  labelEn?: string;
  labelAr?: string;
  children?: NavLeaf[];
}

/**
 * 运营管理的叶子：[页面, 标签, 权限码, 分组]。soon = 该页后端未就绪（仅真实后端模式下为 true）。
 * `useMock` 参数只为单测注入；运行时取 backend-ready 的环境判定。
 */
export function opLeaves(
  rows: [OperationPage, string, string, string][],
  useMock?: boolean,
): NavLeaf[] {
  return rows.map(([page, label, perm, group]) => {
    const leaf: NavLeaf = { href: `/operation/${page}`, label, perm, group };
    if (!pageReady(page, useMock)) leaf.soon = true;
    return leaf;
  });
}

/**
 * **静态菜单定义**。两个用途：
 * 1. `backend/scripts/gen-menu-seed.py` 从它生成 `iam_menu` 的行（卡口 nav-seed.test.ts）；
 * 2. 服务端菜单没启用 / 没到 / 拉失败时的**兜底**。
 *
 * ⚠️ **界面不要直接读它，读 {@link navTree}()** —— 否则开了服务端菜单也没用。
 */
/**
 * 流程与工单叶子的就绪度（见 backend-ready.ts FLOW_BACKEND_READY）。
 * ⚠️ 必须是一个常量再展开，**不能在叶子字面量里写 `...(x ? {} : {…})`**：
 * 菜单解析器（feature-align.py、MenuPermissionContractTest、gen-menu-seed）按花括号切叶子，
 * 叶子里多一对花括号就整条解析不到 —— 实测这批叶子因此全被判成「孤儿页」，而菜单码检查直接漏掉了它们。
 */
const FLOW_SOON: Pick<NavLeaf, "soon"> = flowReady() ? {} : { soon: true };

export const NAV: NavSection[] = [
  // ── 代理端门户（方案 §六 方案 A：不建新工程/新页，AGENT 登录后只出「我的」三项，
  //    深链复用运营端既有页面，数据由后端按 agent_no 收敛）。
  {
    key: "my-biz", label: "我的经营", icon: "LayoutDashboard", module: "dashboard", href: "/",
    portalFor: ["AGENT"],
    children: [
      { href: "/", label: "我的看板", perm: "dashboard:overview:read", group: "经营概览" },
      { href: "/finance?tab=records", label: "我的收益", perm: "finance:share_record:read", group: "经营概览" },
      { href: "/finance?tab=settlements", label: "我的结算", perm: "agent:settlement:read", group: "经营概览" },
      // 2026-09-23 分级矩阵 AGT-06：代理自助提现是 L0（⑦ 分钱的最后一步），权限码 finance:withdrawal:apply
      // 前后端与 POST /api/trade/withdrawals 三处一致。申请侧已在 /finance 页按 AGENT 角色分流
      // （app/finance/page.tsx 的申请流），不再是 soon。
      { href: "/finance?tab=withdrawals", label: "申请提现", perm: "finance:withdrawal:apply", group: "经营概览" },
    ],
  },
  {
    key: "my-asset", label: "我的资产", icon: "Server", module: "device", href: "/devices",
    portalFor: ["AGENT"],
    children: [
      { href: "/devices", label: "我的设备", perm: "device:cabinet:read", group: "设备与订单" },
      { href: "/orders", label: "我的订单", perm: "order:order:read", group: "设备与订单" },
    ],
  },
  {
    key: "my-service", label: "我的服务", icon: "Wrench", module: "workorder", href: "/work-orders",
    portalFor: ["AGENT"],
    children: [
      { href: "/work-orders?view=list", label: "设备报修", perm: "workorder:wo:create", group: "报修与跟进" },
    ],
  },

  // ── 运营端 16 项 ────────────────────────────────────────────────────
  // 它没有叶子，可见性只能看自己这一个码（别的 section 看「有没有可见叶子」）
  { key: "dashboard", label: "经营看板", icon: "LayoutDashboard", module: "dashboard", href: "/", perm: "dashboard:overview:read" },
  {
    // 2026-09-22 新菜单：对标简电云「运营管理」三个分组（场站管理 / 基础管理 / 公告管理），
    // 不在旧菜单上叠加，将来替换站点/计费/系统设置/营销里的对应项。
    // 需求：docs/requirements/features/运营管理-功能清单.md；方案：TDD-运营管理菜单-前端.md
    // 与其它 section 不同：一个 section 下是**多个独立页面**（/operation/<page>），不是同页 tab。
    // soon 由 backend-ready.ts 决定：真实后端模式下接口未就绪的页面灰显，mock 模式恒可点。
    key: "operation", label: "运营管理", icon: "Store", module: "location",
    modules: ["location", "pricing", "finance", "system", "marketing"],
    // 2026-10-28：站点四项迁到「网点管理」（OM02），计费两项迁到「销售管理」（OM04），本段只剩基础、分成两组，
    // 待系统（OM11）、结算（OM08）两个模块调整时各自迁走，届时本段撤销。
    // /operation/sites、/operation/overview、/operation/fee-* 由各自新 L1 的 match 认领（最长前缀胜出），这里只留 /operation。
    href: "/operation/app-versions", match: ["/operation"],
    // 2026-09-23 菜单收敛（docs/technical/菜单重合梳理与优化方案.md）：
    //
    // 第一步（A 类重合）：站点管理、收费方案、时段倍率、应用版本、银行、问题、公告
    // 此前**每一项都有第二个入口**且调同一组 API。一律并到本 section，旧入口连页面代码一起撤。
    //
    // 第二步：撤销「站点与点位」L1，点位与坪效并进来（URL 仍是 /locations，故 match 带它）。
    //
    // 第三步（续）：「计费定价」L1 整体撤销 —— 它的计费模板/时段价早已并入本 section 的
    // 「收费方案」，最后剩的「差异化定价」在 ADR-028 落地后并入方案的「适用范围」，
    // 取价从此只有一处答案（V49）。那个 L1 已无内容，留着只是一个空壳。
    //
    // 第三步：场地方 / 合同 / BD CRM / 进件 / 生命周期 **移出本 section**，
    // 独立成「场地方与拓展」L1 —— 拓展是签约前的获客，不是经营已有站点；
    // 而分成是分账动作、不是报表，故原「场站报表」组改名「分成」，坪效归回场站管理。
    //
    // ⚠️ 顺序即分组：groupedLeaves 只合并**相邻**的同名 group，插叶子时别打断连续段。
    children: [
      ...opLeaves([
        // 公告并进「基础管理」：原先它自成一组，而组名与唯一那条叶子完全同名 ——
        // 一个只为一条叶子存在的分组标题只是多一行噪音。四项都是运营日常要维护的基础内容。
        ["app-versions", "应用版本", "system:app_version:read", "基础管理"],
        ["banks", "银行管理", "system:bank:read", "基础管理"],
        ["brands", "品牌管理", "system:brand:read", "基础管理"],
        ["problems", "问题管理", "system:problem:read", "基础管理"],
        ["notices", "公告管理", "marketing:notice:read", "基础管理"],
        // 「分成」不是报表：这两页展示的是钱**实际分给了谁、各多少**，是分账口径本身，
        // 不是给人分析用的统计。组名叫报表会让人以为它可改可不看。
        ["site-sharing", "站点分成", "finance:share_rule:read", "分成"],
        ["payee-sharing", "分成方分成", "finance:share_rule:read", "分成"],
      ]),
    ],
  },
  {
    key: "device", label: "设备管理", icon: "Server", module: "device", href: "/devices",
    // 2026-10-28 按运营顺序（OM03 · docs/requirements/运营端菜单清单-按运营顺序.md）：
    // 资产台账 → 库存与流转 → 设备接入 → 在线运行（P4 布放：有账 → 能调 → 能连 → 在跑）。
    // 「采购与入库」组（供应商 / 采购单 / 收货入库 / 期初登记）待后端补实体后再加，见优化清单 OM03。
    // ⚠️ 菜单真源在库里：本段与系统设置段的行由 V150 重编号，改这里要同步新开迁移（见 nav-seed.test.ts）。
    children: [
      { href: "/devices", label: "设备台账", perm: "device:cabinet:read", group: "资产台账" },
      { href: "/devices?tab=powerbanks", label: "充电宝管理", perm: "device:powerbank:read", group: "资产台账" },
      { href: "/devices?tab=codes", label: "设备编码", perm: "device:cabinet:read", phase: 1, group: "资产台账" },
      // 库存余额由柜机 / 充电宝两个既有读端点在页内汇总，不新增后端端点
      { href: "/devices?tab=stock", label: "库存余额", perm: "device:inventory:read", phase: 1, ready: true, group: "库存与流转" },
      { href: "/devices?tab=inventory", label: "库存调拨", perm: "device:inventory:read", phase: 2, group: "库存与流转" },
      // 此前是库存调拨里的子视图（同分期），菜单里找不到；拆出来不改分期
      { href: "/devices?tab=asset-diffs", label: "资产差异", perm: "device:inventory:read", phase: 2, group: "库存与流转" },
      // 从系统设置搬来：新厂商的设备要先接入才能上线，是布放的前置一步，不是系统参数
      { href: "/devices?tab=vendors", label: "供应商接入", perm: "device:vendor:read", group: "设备接入" },
      { href: "/devices?tab=monitor", label: "实时监控", perm: "device:cabinet:read", group: "在线运行" },
      { href: "/devices?tab=commands", label: "远程控制与指令记录", perm: "device:command:send", group: "在线运行" },
      { href: "/devices?tab=logs", label: "设备日志", perm: "device:cabinet:read", phase: 1, group: "在线运行" },
      { href: "/devices?tab=ota", label: "固件 OTA", perm: "device:ota:read", phase: 2, group: "在线运行" },
    ],
  },
  {
    // 2026-10-28 按运营顺序合并为「运维管理」（OM06 · docs/requirements/运营端菜单清单-按运营顺序.md）：
    // 告警管理 + 客服管理 + 工单管理三个 L1 合一。三组是同一条处置链：
    //   告警（机器发现问题）→ 客服（用户报来问题）→ 工单（派人去现场解决）。
    // 前两组是问题的两个入口，工单是两者共同的出口（告警「转工单」、报障「转工单」都落到这里）。
    // 告警组仍对标简电云「告警管理」：码字典 → 通知规则 → 记录 → 触达；我们多一环关联工单号与处置路由。
    // 客服原先的「退款/补偿」「黑名单处理」两个快捷入口撤销：它们指向 /orders、/users，
    // 一个 URL 只属于一个 L1，在这里点进去面包屑与 Rail 高亮跳到别的菜单，运营反而找不到回来的路；
    // 退款直达销售管理 › 售后，黑名单直达用户管理 › 风险。
    // 三个页面 URL 不动（/alarms、/cs、/work-orders），靠 match 认领；落地页是告警记录（href）。
    // ⚠️ 菜单真源在库里：本段的行由 V153 重编号（M_alarm / M_cs 两个 L1 删除），改这里要同步新开迁移（见 nav-seed.test.ts）。
    key: "workorder", label: "运维管理", icon: "Wrench", module: "workorder", href: "/alarms",
    match: ["/alarms", "/cs", "/work-orders"],
    children: [
      { href: "/alarms", label: "告警记录", perm: "workorder:alarm:read", group: "告警" },
      { href: "/alarms?tab=notices", label: "告警通知", perm: "workorder:alarm:read", group: "告警" },
      { href: "/alarms?tab=codes", label: "告警代码", perm: "workorder:alarm:read", group: "告警" },
      { href: "/alarms?tab=rules", label: "通知规则", perm: "workorder:alarm:read", group: "告警" },
      /*
       * 2026-09-28 解锁客服两叶（徽章仍是 L1/L2，ready 只放宽分期）。
       * 在此之前生产跑在 L0，这两页都点不开 —— 而 **C 端的报障全部落在这里**：
       * 用户提交（连同截图）→ 落 cs_ticket / 转人工会话 → 运营端没有任何界面能打开它，
       * 而 C 端还写着「正在排队处理」。L0 可达的「待办中心」只接告警，兜不住这条。
       * 后端就绪：列表 / 详情（含截图）/ 受理 / 转工单 / 转退款 / 会话消息与回复端点齐备。
       */
      { href: "/cs", label: "报障受理", phase: 1, ready: true, perm: "cs:ticket:read", group: "客服" },
      { href: "/cs?tab=sessions", label: "客服会话", phase: 2, ready: true, perm: "cs:session:read", group: "客服" },
      { href: "/work-orders?view=list", label: "工单列表", perm: "workorder:wo:read", group: "工单" },
      { href: "/work-orders?view=board", label: "工单看板", perm: "workorder:wo:read", group: "工单" },
      // 先定巡检、再定时效：巡检计划生成工单，SLA 量的是所有工单
      { href: "/work-orders?view=inspection", label: "巡检计划", phase: 2, perm: "workorder:wo:read", group: "工单" },
      { href: "/work-orders?view=sla", label: "SLA 管理", phase: 2, perm: "workorder:wo:read", group: "工单" },
    ],
  },
  {
    // 2026-09-23 第三步新增：场地方这条线从运营管理拆出。
    // 与「代理商管理」对称 —— 两类合作主体各有自己的档案、协议与生命周期。
    // 为什么不塞在运营管理里：**拓展是签约前的获客**（线索 → 进件 → 签约），
    // 与经营已有站点是两件事、两拨人；混在一个菜单里，运营每天要从一堆线索里找自己的站点。
    // 页面在 /venues（从 /locations 拆出）：一个 URL 只能属于一个 L1，
    // 否则面包屑与 Rail 高亮必错一边（findActiveSection 按路径前缀定归属）。
    //
    // 2026-10-28 按运营顺序合并为「网点管理」（OM02 · docs/requirements/运营端菜单清单-按运营顺序.md）：
    // 场地方这条线与运营管理里的站点四项合成一个 L1，三组跟着 P1 拓场 → P2 签约 → P3 建站走。
    // 站点页的 URL 不动（/operation/sites、/operation/overview、/locations），靠 match 认领 ——
    // findActiveSection 取最长前缀，/operation/sites 胜过运营管理的 /operation。
    // 落地页是站点管理（href），不是第一项 BD CRM：sectionDefaultHref 先认 section.href。
    // ⚠️ 菜单真源在库里：本段与运营管理段的行由 V149 重编号，改这里要同步新开迁移（见 nav-seed.test.ts）。
    key: "venue", label: "网点管理", icon: "Building2", module: "location", href: "/operation/sites",
    match: ["/venues", "/locations", "/operation/sites", "/operation/overview"],
    children: [
      // 必须有显式 perm：无 perm 的叶子跟随 section，客服/运维不该看到 BD 的线索池。
      { href: "/venues?tab=crm", label: "BD 拓展 CRM", perm: "location:lead:read", group: "拓展" },
      { href: "/venues?tab=onboarding", label: "门店 Onboarding", perm: "location:venue:read", phase: 2, group: "拓展" },
      { href: "/venues?tab=venues", label: "场地方", perm: "location:venue:read", group: "场地方与合同" },
      { href: "/venues?tab=contracts", label: "进场合同", perm: "location:contract:read", group: "场地方与合同" },
      // 「合同提醒设置」（OM02-07）暂不建叶：到期提醒的 60 / 30 / 7 天在后端写死（合同汇总与 V104 告警码），
      // 前端做配置页没有任何东西读它。已提给服务端评审，后端可配后再加。
      ...opLeaves([["sites", "站点管理", "location:poi:read", "站点与点位"]]),
      { href: "/locations?tab=points", label: "点位管理", perm: "location:poi:read", group: "站点与点位" },
      ...opLeaves([["overview", "站点概览", "location:overview:read", "站点与点位"]]),
      { href: "/locations?tab=analysis", label: "站点坪效", perm: "location:analysis:read", phase: 3, group: "站点与点位" },
      // 门店生命周期已并入站点状态（运营核心流程 §10.2），它统计的是站点，从「拓展」挪到这里
      { href: "/venues?tab=lifecycle", label: "门店生命周期", perm: "location:venue:read", phase: 3, group: "站点与点位" },
    ],
  },
  {
    // 2026-10-28 按运营顺序重排（docs/requirements/运营端菜单清单-按运营顺序.md · OM01）：
    // 「代理商管理」更名「商家管理」—— 商家是第一阶段的结算单元（ADR-032），代理商 / 城市合伙人
    // 只是它的登记类型。三组顺序跟着商家的一生走：进来 → 定钱与资产 → 经营与退出。
    // 入驻审核排在档案之前：档案是「已经在的」，入驻是「正在进来的」—— 待办优先于台账。
    // 代建录入不单开叶子，它是本页的一个按钮（权限码另发，见 ADR-030 §3.3）。
    // 「代理收益结算」深链撤销：它指向财务的结算单，挂的 agent:settlement:read 没有任何后端端点
    // 挂载（功能权限清单 §缺口 2），BD 从这里点进去对真后端必 403。结算单归「结算」模块。
    // ⚠️ 菜单真源在库里：本段的行由 V148 定点更新，改这里要同步新开迁移（见 nav-seed.test.ts）。
    // 落地页 = 入驻审核（待办优先于台账）。sectionDefaultHref 先认 href，所以 href 必须指向它而不是档案页
    key: "agent", label: "商家管理", icon: "Handshake", module: "agent", href: "/agents?tab=applies",
    children: [
      { href: "/agents?tab=applies", label: "入驻审核", perm: "agent:apply:read", group: "入驻与档案" },
      { href: "/agents", label: "商家档案", perm: "agent:agent:read", group: "入驻与档案" },
      // 码与后端账号端点一致（/api/agent/accounts 全挂 agent:account:manage），此前挂的 agent:agent:update 对不上
      { href: "/agents?tab=accounts", label: "商家账号", perm: "agent:account:manage", group: "入驻与档案" },
      // 先定分润、再划资产：划拨只影响之后的订单归属，规则得先在
      { href: "/agents?tab=commission", label: "商家分润规则", perm: "agent:share:config", group: "分润与划拨" },
      { href: "/agents?tab=assign", label: "设备/点位划拨", perm: "agent:scope:assign", group: "分润与划拨" },
      // 经营视图由三个既有读端点在页内拼出（商家 / 绩效 / 分润汇总），不新增后端端点
      { href: "/agents?tab=business", label: "商家经营视图", perm: "agent:agent:read", phase: 1, ready: true, group: "经营与退出" },
      { href: "/agents?tab=performance", label: "商家绩效", perm: "agent:performance:read", phase: 2, group: "经营与退出" },
      // 清退流程早已在档案详情里跑通（四步门禁），这里补的是「所有在途清退」的列表入口
      { href: "/agents?tab=exits", label: "退出与清算", perm: "agent:agent:update", phase: 2, ready: true, group: "经营与退出" },
    ],
  },
  {
    // 2026-10-28 按运营顺序合并为「销售管理」（OM04 · docs/requirements/运营端菜单清单-按运营顺序.md）：
    // 订单管理 + 营销管理（整段并入）+ 运营管理的计费两项 + 用户管理的会员两项 + 系统设置的支付渠道。
    // 收入线：先有价格，再有订单，订单产生支付，异常走售后；营销、会员、广告是增长手段，排在主线之后。
    // 跨三条路径：/orders（订单、支付渠道、会员 tab）、/marketing、/operation/fee-*（match 最长前缀认领）。
    // 落地页是订单列表（href），不是第一项收费方案。
    // ⚠️ 菜单真源在库里：本段及其它四段的行由 V151 重编号，改这里要同步新开迁移（见 nav-seed.test.ts）。
    key: "order", label: "销售管理", icon: "ReceiptText", module: "order", href: "/orders",
    match: ["/orders", "/marketing", "/operation/fee-plans", "/operation/fee-adjustments"],
    children: [
      ...opLeaves([
        ["fee-plans", "收费方案", "pricing:plan:read", "计费"],
        ["fee-adjustments", "预约调价", "pricing:adjustment:read", "计费"],
      ]),
      { href: "/orders", label: "订单列表", perm: "order:order:read", group: "订单" },
      { href: "/orders?tab=reservations", label: "预约订单", perm: "order:order:read", phase: 2, group: "订单" },
      { href: "/orders?tab=deposit", label: "押金与欠费", perm: "order:order:read", phase: 1, ready: true, group: "订单" },
      { href: "/orders?tab=free", label: "免费订单", perm: "order:order:read", phase: 2, group: "订单" },
      // 支付流水 / 扣款失败与重试（OM04-12 / 04）待后端补实体后加在这一组
      { href: "/orders?tab=payment-channels", label: "支付渠道", perm: "system:payment_channel:read", group: "支付" },
      { href: "/orders?tab=exceptions", label: "异常订单", perm: "order:exception:read", group: "售后" },
      { href: "/orders?tab=complaints", label: "投诉订单", perm: "order:exception:read", group: "售后" },
      { href: "/orders?tab=refunds", label: "退款记录", perm: "order:refund:audit", group: "售后" },
      { href: "/marketing", label: "优惠券", perm: "marketing:coupon:read", phase: 2, ready: true, group: "营销" },
      { href: "/marketing?tab=campaigns", label: "活动", phase: 2, group: "营销", perm: "marketing:campaign:read" },
      { href: "/marketing?tab=push", label: "推送触达", perm: "marketing:push:send", phase: 3, ready: true, group: "营销" },
      { href: "/marketing?tab=referral", label: "邀请裂变", phase: 3, group: "营销", perm: "marketing:campaign:read" },
      { href: "/orders?tab=plans", label: "会员套餐", perm: "user:member:read", phase: 2, group: "会员" },
      { href: "/orders?tab=members", label: "会员/次卡", perm: "user:member:read", phase: 2, group: "会员" },
      { href: "/marketing?tab=ad-slots", label: "广告位管理", phase: 3, group: "广告", perm: "marketing:ad:read" },
      { href: "/marketing?tab=ad-campaigns", label: "广告活动", phase: 3, group: "广告", perm: "marketing:ad:read" },
      { href: "/marketing?tab=ad-delivery", label: "投放与曝光", phase: 3, group: "广告", perm: "marketing:ad:read" },
    ],
  },
  {
    key: "finance", label: "财务管理", icon: "Wallet", module: "finance", href: "/finance",
    children: [
      // 按「资金主体」分组（对标简电云 平台/运营商/商户/会员 四套财务视图）：
      // 分润与结算 = 跨主体的规则与产出；平台账 = 自家的账；伙伴账 = 代理商/场地方的钱；用户账 = C 端的钱。
      { href: "/finance?tab=rules", label: "分润规则", perm: "finance:share_rule:read", group: "分润与结算" },
      { href: "/finance?tab=records", label: "分润明细", perm: "finance:share_record:read", group: "分润与结算" },
      { href: "/finance?tab=summary", label: "分润统计", perm: "finance:share_record:read", phase: 1, group: "分润与结算" },
      { href: "/finance?tab=settlements", label: "结算单", perm: "finance:settlement:read", group: "分润与结算" },
      { href: "/finance?tab=ledger", label: "账务分录", perm: "finance:ledger:read", phase: 2, group: "平台账" },
      { href: "/finance?tab=reconcile", label: "对账", perm: "finance:recon:read", phase: 1, ready: true, group: "平台账" },
      { href: "/finance?tab=invoices", label: "发票", perm: "finance:invoice:read", phase: 2, ready: true, group: "平台账" },
      // 跨 section 深链（导航审查 #4）：FINANCE 岗管场地方分润在本页、代理分润在 /agents，
      // 此处回链避免找不到入口；面包屑按 URL 归属「代理商管理」。
      { href: "/agents?tab=commission", label: "代理分润配置", perm: "agent:share:config", group: "伙伴账" },
      { href: "/finance?tab=withdrawals", label: "提现审核", perm: "finance:withdrawal:read", group: "伙伴账" },
      // 排在提现审核之后：没有收款账户，提现审批根本放行不了（B3）
      { href: "/finance?tab=payout-accounts", label: "收款账户", perm: "finance:payout_account:read", group: "伙伴账" },
      // 「用户账」组（用户钱包深链、充值订单）2026-10-28 撤销：两者归「用户管理 › 资产」（OM05），
      // 钱包本就在用户页，充值订单页签搬过去与套餐合并
    ],
  },
  {
    // 2026-10-28 按运营顺序（OM05 · docs/requirements/运营端菜单清单-按运营顺序.md）：用户 → 风险 → 资产。
    // 会员两项迁到销售管理（OM04）；充值订单自财务管理迁入，与充值套餐合为一项；
    // 开票申请属发票模块（OM09），该模块调整时迁走，暂留资产组末尾。
    // ⚠️ 菜单真源在库里：本段与财务管理段的行由 V152 重编号，改这里要同步新开迁移（见 nav-seed.test.ts）。
    key: "user", label: "用户管理", icon: "UserCircle", module: "user", href: "/users",
    children: [
      { href: "/users", label: "用户列表", perm: "user:cuser:read", phase: 1, group: "用户" },
      { href: "/users?tab=logoffs", label: "注销申请", perm: "user:logoff:read", phase: 2, group: "用户" },
      { href: "/users?tab=risk", label: "风控用户", perm: "user:risk:read", phase: 1, ready: true, group: "风险" },
      { href: "/users?tab=blacklist", label: "黑名单", perm: "user:risk:update", phase: 1, group: "风险" },
      { href: "/users?tab=whitelist", label: "免押白名单", perm: "user:risk:update", phase: 2, group: "风险" },
      { href: "/users?tab=wallets", label: "钱包", perm: "user:wallet:read", phase: 2, group: "资产" },
      { href: "/users?tab=recharge", label: "充值订单与套餐", perm: "user:wallet:read", phase: 2, group: "资产" },
      { href: "/users?tab=invoices", label: "开票申请", perm: "user:invoice:read", phase: 2, group: "资产" },
    ],
  },
  // 「营销管理」L1 2026-10-28 整段并入「销售管理」（OM04）：营销与广告是收入线上的增长手段。
  // /marketing 页面不动，由销售管理的 match 认领。
  // 「客服管理」L1 2026-10-28 并入「运维管理」（OM06）：报障受理 / 客服会话归「客服」组，
  // 两个快捷入口（退款/补偿、黑名单处理）撤销。/cs 页面不动，由运维管理的 match 认领。
  {
    // 分期说明（导航审查 #2）：PDF V4 的「基础运营报表 P1」由「经营看板」承载
    // （KPI/趋势/实时告警/待办中心/排名榜单），本 section 全部为 P2/P3 的专题分析报表，
    // 故 P1 阶段整体灰显属预期。改阶段先改 docs/requirements/运营端功能清单.md §二·B。
    key: "report", label: "数据报表", icon: "ChartColumn", module: "report", href: "/reports",
    children: [
      { href: "/reports?tab=device", label: "设备运营分析", perm: "report:device:read", phase: 2 },
      { href: "/reports?tab=location", label: "点位坪效", perm: "report:location:read", phase: 2 },
      { href: "/reports?tab=finance", label: "财务报表", phase: 2, perm: "report:finance:read" },
      { href: "/reports?tab=screen", label: "实时大屏", phase: 3, perm: "report:screen:read" },
      { href: "/reports?tab=custom", label: "自定义报表", phase: 3, perm: "report:custom:read" },
      { href: "/reports?tab=consumer", label: "消费者分析", phase: 3, perm: "report:consumer:read" },
    ],
  },
  {
    key: "org", label: "员工与权限", icon: "Users", module: "org", href: "/employees",
    children: [
      // 按「人与组织 / 授权 / 留痕」分组
      { href: "/employees?tab=employees", label: "员工", perm: "org:employee:read", group: "人与组织" },
      // 组织架构与绩效展示的都是员工数据，与「员工」同一个码 ——
      // 此前菜单没写 perm 而页面写了，两边不一致时以更严的那边为准
      { href: "/employees?tab=org", label: "组织架构", perm: "org:employee:read", phase: 2, group: "人与组织" },
      { href: "/employees?tab=roles", label: "角色权限", perm: "org:role:read", group: "授权" },
      // 菜单本身也在库里（V67 起），所以它的维护入口也是一条菜单 ——
      // 加这一条要写迁移，这就是「菜单真源迁进库」的代价（方案 §8）
      { href: "/employees?tab=menus", label: "菜单管理", perm: "org:role:read", group: "授权" },
      { href: "/employees?tab=audit", label: "操作审计", perm: "org:audit:read", phase: 1, group: "留痕与考核" },
      { href: "/employees?tab=performance", label: "绩效报表", perm: "org:employee:read", phase: 3, group: "留痕与考核" },
    ],
  },
  {
    // D1：菜单按 system 模块过滤（供应商接入已于 2026-10-28 搬到设备管理，其 device:vendor:* 码随之归设备段）
    // 2026-10-28：供应商接入搬到设备管理（OM03）、支付渠道搬到销售管理（OM04），首页改为通知模板
    key: "system", label: "系统设置", icon: "Settings", module: "system", href: "/system?tab=notify",
    match: ["/system"],
    pinBottom: true,
    children: [
      // 按「接入 / 消息触达 / 业务规则 / 基础字典 / 开放与市场」分组（补齐清单 E）。
      // 注：对方把 提现设置/预约设置/充电设置 拆三个菜单、7 个支付渠道各占一菜单；
      //     我们合并为「业务规则」一页与「支付渠道」一页 —— 少菜单噪音即"信息更清晰"。
      { href: "/system?tab=notify", label: "通知模板", perm: "system:notify_template:read", group: "消息触达" },
      { href: "/system?tab=notify-log", label: "发送记录", perm: "system:notify_log:read", phase: 1, group: "消息触达" },
      { href: "/system?tab=notify-blacklist", label: "触达拉黑", perm: "system:notify_blacklist:read", phase: 1, group: "消息触达" },
      { href: "/system?tab=rules", label: "业务规则", perm: "system:biz_rule:update", phase: 1, group: "业务规则" },
      { href: "/system?tab=login", label: "登录设置", perm: "system:login_setting:update", phase: 1, group: "业务规则" },
      // 「应用版本」「银行管理」「问题管理」2026-09-23 并入 运营管理 › 基础管理
      // （同一组 listAppVersions/saveAppVersion/rollbackAppVersion、listBanks/saveBank、
      // listProblems/saveProblem），本页不再留第二份。
      { href: "/system?tab=dict", label: "参数字典", perm: "system:dict:read", group: "基础字典" },
      { href: "/system?tab=region", label: "地区库", perm: "system:dict:read", group: "基础字典" },
      { href: "/system?tab=params", label: "系统参数", perm: "system:param:read", group: "基础字典" },
      { href: "/system?tab=tax", label: "税率与发票", perm: "system:tax:update", phase: 2, group: "开放与市场" },
      { href: "/system?tab=markets", label: "多国家市场", perm: "system:market:read", phase: 3, group: "开放与市场" },
      { href: "/system?tab=openapi", label: "OpenAPI 应用", perm: "system:openapi:read", phase: 3, group: "开放与市场" },
    ],
  },
  {
    // 2026-09-28 流程与工单（OpsKit 的运营界面，docs/technical/v4/16-工作流与工单/22-前端方案）。
    // 权限码强制方是 OpsKit（功能权限清单 §16，backend/external-perm-modules.txt）。
    //
    // ⚠️ **放在最末**：L1 的 sort 是数组下标，插在中间会让后面每个 L1 的 sort 都要挪，
    //    那就只能整表重灌 iam_menu（会冲掉「菜单管理」的线上改动）。V136 只插新行。
    // ⚠️ soon：只有构建时配了 NEXT_PUBLIC_FLOW_API=http（真连 OpsKit）才放开，否则灰显 ——
    //    不连 OpsKit 时 flow 取的是浏览器内演示数据（backend-ready.ts FLOW_BACKEND_READY）。
    key: "flow", label: "流程与工单", icon: "Workflow", module: "flow", href: "/flow/todo",
    match: ["/flow"],
    children: [
      { href: "/flow/todo", label: "待办中心", perm: "flow:todo:read", group: "我的待办", ...FLOW_SOON },
      { href: "/flow/approvals", label: "审批列表", perm: "flow:approval:read", group: "审批", ...FLOW_SOON },
      { href: "/flow/queue", label: "审核队列", perm: "flow:queue:claim", group: "审批", ...FLOW_SOON },
      { href: "/flow/tickets", label: "工单工作台", perm: "flow:ticket:read", group: "工单", ...FLOW_SOON },
      { href: "/flow/processes", label: "流程配置", perm: "flow:process:manage", group: "配置", ...FLOW_SOON },
      { href: "/flow/settings", label: "运营配置", perm: "flow:config:manage", group: "配置", ...FLOW_SOON },
      { href: "/flow/access", label: "接入管理", perm: "flow:access:manage", group: "配置", ...FLOW_SOON },
      // V139：追加在末尾（不往既有分组中间插）。实例与对账同审批可见口径；统计是管理视角
      { href: "/flow/instances", label: "流程实例", perm: "flow:approval:read", group: "流转与统计", ...FLOW_SOON },
      { href: "/flow/stats", label: "流程统计", perm: "flow:process:manage", group: "流转与统计", ...FLOW_SOON },
    ],
  },
];

// ── 纯函数 helper（无 React 依赖，可单测） ──────────────────────────────

/** trailingSlash:true 下 pathname 带尾斜杠，比较前归一化。 */
/**
 * 界面该读的那棵树：启用且已拿到服务端菜单时用它，否则用静态的 {@link NAV}。
 *
 * 写成函数而不是常量：数据是运行时到的，常量会被别处提前捕获成旧值。
 * 非 React 代码（面包屑、路由归属判断）也要能调，所以不做成 hook ——
 * 重渲染由 AppShell 订阅 store 触发。
 */
export function navTree(): NavSection[] {
  return useMenuStore.getState().tree ?? NAV;
}

export const normPath = (p: string) => p.replace(/\/+$/, "") || "/";

/** 拆 href 为 path + tab + view。 */
export function leafParts(href: string): { path: string; tab: string | null; view: string | null } {
  const [path, qs] = href.split("?");
  const sp = new URLSearchParams(qs);
  return { path: normPath(path), tab: sp.get("tab"), view: sp.get("view") };
}

/**
 * L1 可见性 = canModule；门户 section 与通用运营 section 互斥（见 NavSection.portalFor）。
 */
/**
 * @param tree 要算的那棵树。缺省是界面正在用的那棵；
 *   **「某个角色会看到什么」的预览必须显式传全量树** ——
 *   当前会话那棵已经按自己的权限剪过枝，拿它去算别人会少算一片。
 */
export function visibleSections(v: ViewerLike, tree?: NavSection[]): NavSection[] {
  const { perms, role } = asViewer(v);
  const all = tree ?? navTree();
  const portals = all.filter((s) => role && s.portalFor?.includes(role as Role));
  const pool = portals.length > 0 ? portals : all.filter((s) => !s.portalFor);
  /*
   * **可见性 = 有没有可见叶子**，不再看 canModule（2026-09-24，P1-3）。
   *
   * 跨模块 section 早就这么判了（「防出现点开是空的 L1」）；这次把单模块的也统一过来，
   * 因为 canModule 在两个方向上都不准：
   *   · 松：有该模块某个码、但本 section 的叶子一个都不可见 → L1 显示、点开是空的；
   *   · 紧：**叶子的码不属于本 section 的模块时，整段看不到**。
   *     实测就有两处：「财务 › 用户钱包 / 充值订单」挂的是 user:wallet:read，
   *     CS 持有这个码、端点也判这个码 —— CS 本来就能用这两个页面，
   *     却因为 canModule('finance') 为假而**在菜单上找不到入口**。
   *     这正是 MenuPermissionContractTest 说的「能用但找不到」。
   *
   * 没有叶子的 section（只有「经营看板」）看它自己的 perm。
   * 服务端 MenuService.visibleFor 用的是同一条规则，两边由
   * MenuVisibilityParityTest 逐项对拍。
   */
  return pool.filter((s) => {
    const leaves = s.children ?? [];
    if (!leaves.length) return s.perm ? can(perms, s.perm) : canModule(perms, s.module);
    return leaves.some((l) => (l.perm ? can(perms, l.perm) : true));
  });
}

/** L3 可见性 = leaf.perm ? can() : 跟随 section。phase-locked 叶子保留（灰显）。 */
export function visibleLeaves(section: NavSection, v: ViewerLike): NavLeaf[] {
  const { perms } = asViewer(v);
  return (section.children ?? []).filter((l) => (l.perm ? can(perms, l.perm) : true));
}

/**
 * 页内 tab 的**标签与权限**来源：nav.ts。
 *
 * ## 为什么要有它
 *
 * 整理前每个页面自己写一份 `const TABS = [{key:"withdrawals", label:"提现"}, …]`，
 * 于是同一个功能有两个名字（nav 叫「提现审核」、页面叫「提现」），改一处不改另一处
 * 没有任何东西会报错。更要命的是 **tab 不判权**：`TABS` 是写死的数组，
 * 没有 `finance:withdrawal:read` 的角色照样看得到、点得动那个 tab，
 * 只能靠接口 403 兜底 —— 实测 /finance 菜单按角色只剩 3 条，tab 条仍是 9 条。
 *
 * 收进来之后：页面只声明**有哪些 tab、什么顺序**，名字和能不能看由菜单说了算。
 *
 * @param path  页面路径（不带 query）
 * @param specs 顺序即展示顺序。字符串 = 从菜单取名；`{key,label}` = 这个 tab
 *              **不是菜单叶**（页内子视图），必须自带名字，写明理由
 * @returns 已按权限过滤、按 specs 顺序排列的 tab；phase 原样带出（由 TabHeader 决定是否隐藏）
 */
export function navTabs(
  path: string, specs: readonly PageTabSpec[], v: ViewerLike, defaultKey?: string,
): { key: string; label: string; phase?: Phase }[] {
  return resolveTabs(path, specs, v, false, defaultKey).tabs;
}

/**
 * 哪些 tab 在菜单里找不到。
 *
 * 单独给一个纯函数，是因为「逐个 key 去问」会得到错误答案：
 * key 列表的**第一个**被当作页面默认 tab，会去匹配不带 `?tab=` 的裸路径叶，
 * 于是单独问任何一个 key 都能匹配上。必须整组一起问。
 */
export function missingTabs(
  path: string, keys: readonly string[], v: ViewerLike, defaultKey?: string,
): string[] {
  return resolveTabs(path, keys, v, true, defaultKey).missing;
}

function resolveTabs(
  path: string, specs: readonly PageTabSpec[], v: ViewerLike, quiet = false, defaultKey?: string,
): { tabs: { key: string; label: string; phase?: Phase }[]; missing: string[] } {
  const { perms } = asViewer(v);
  const target = normPath(path);
  const keys = specs.map((x) => (typeof x === "string" ? x : x.key));
  /*
   * **先认这一页主属的那个 section**。
   *
   * 同一个 href 可能在多个 section 登记：`/finance?tab=settlements` 在
   * 「代理商管理 › 代理收益结算」和「财务管理 › 结算单」各有一条。按 NAV 顺序取第一个
   * 会拿到「代理收益结算」—— 在财务页上是错的名字（实测 2026-09-23 就是这样）。
   * 判据：**命中本页 tab 最多的那个 section 就是这一页的归属**，先从它取名，
   * 剩下的再去别处找（门户角色的 section 优先参与评分）。
   */
  /*
   * **页面的默认 tab 在菜单里是不带 `?tab=` 的那条裸路径。**
   * 例：`/orders` 这一条叫「订单列表」，对应页面的默认 tab `list`。
   * 不认这条的话，十二个页面的默认 tab 会全部报「未在 nav.ts 登记」。
   *
   * 默认 tab 不一定是列表里的第一个：`/marketing` 的默认 tab 随分期变
   * （阶段 1 是公告、阶段 2 起是优惠券），而 tab 顺序是固定的。
   * 所以这种页面要显式把 `defaultKey` 传进来；不传才退回"第一个"。
   */
  const dft = defaultKey ?? keys[0];
  // 页内切换参数有两种写法：多数页用 `?tab=`，工单页用 `?view=`。
  // 一条叶最多带其中一个，所以这里两个都认，不必让调用方声明用的是哪一个。
  const tabOf = (href: string) => {
    const parts = leafParts(href);
    if (parts.path !== target) return undefined;
    return parts.tab ?? parts.view ?? dft;
  };
  const score = (sec: NavSection) => (sec.children ?? []).filter((l) => {
    const k = tabOf(l.href);
    return k !== undefined && keys.includes(k);
  }).length;
  const visible = visibleSections(v);
  const ranked = [...visible, ...navTree().filter((x) => !visible.includes(x))]
    .map((sec, i) => ({ sec, s: score(sec), i }))
    .filter((x) => x.s > 0)
    .sort((a, b) => b.s - a.s || a.i - b.i)
    .map((x) => x.sec);
  const pool = [...ranked, ...visible, ...navTree()];
  const out: { key: string; label: string; phase?: Phase }[] = [];
  const missing: string[] = [];
  for (const spec of specs) {
    const key = typeof spec === "string" ? spec : spec.key;
    /*
     * 同一个 tab key 可能在**多个 section 登记，且权限码不同**：
     * `/finance?tab=withdrawals` 在「财务管理 › 提现审核」判 `finance:withdrawal:read`，
     * 在「我的经营 › 申请提现」判 `finance:withdrawal:apply`。
     *
     * 只取排名最高的那一条会出错：财务管理的 /finance 叶子多、评分高，于是代理商
     * 拿到的是「提现审核」那条 —— 他没有 `:read`，整个 tab 就被丢掉了，
     * **尽管他持有 `:apply`、菜单里那条叶子也确实是给他的**。
     * 表现是「菜单点得进来，页面上却没有对应的 tab」，且不报任何错。
     *
     * 所以按 pool 顺序找**第一条该访问者进得去的**；都进不去时回落到第一条，
     * 由下面的判权把 tab 丢掉（回落这一步是为了让 missingTabs 仍能报出未登记，
     * 而不是把「无权限」误报成「没登记」）。
     */
    let leaf: NavLeaf | undefined;
    let firstMatch: NavLeaf | undefined;
    outer: for (const section of pool) {
      for (const l of section.children ?? []) {
        if (tabOf(l.href) !== key) continue;
        firstMatch ??= l;
        if (!l.perm || can(perms, l.perm)) { leaf = l; break outer; }
      }
    }
    leaf ??= firstMatch;
    if (!leaf) {
      if (typeof spec === "string") {
        // 菜单里没登记、页面也没自带名字 —— 这不是显示问题，是**这个功能在菜单里进不去**。
        // 开发期直接抛；生产回落成 key 本身（难看，但不白屏，且一眼看得出漏了什么）。
        missing.push(key);
        if (!quiet && process.env.NODE_ENV !== "production") {
          throw new Error(`[navTabs] ${target} 的 tab「${key}」未在 nav.ts 登记；` +
            `它若是菜单叶请补登记，若只是页内子视图请传 { key, label }`);
        }
        out.push({ key, label: key });
        continue;
      }
      out.push({ key, label: spec.label });
      continue;
    }
    if (leaf.perm && !can(perms, leaf.perm)) continue; // 无权限：tab 不渲染，与菜单同一口径
    // 分期也与菜单同一口径（isLeafLocked）：ready 的叶子不受分期屏蔽，所以不带 phase 出去。
    // 此前原样带出 phase，TabHeader 按它隐藏 —— 于是 ready 叶「菜单能点进来、页签条上却没有它」，
    // 生产跑在 L0 时押金与欠费、对账、发票、风控用户、优惠券、推送触达、报障受理、客服会话都是这样。
    out.push({ key, label: leaf.label, phase: leaf.ready ? undefined : leaf.phase });
  }
  return { tabs: out, missing };
}

/**
 * 门户标题覆盖（拍板 #5，2026-07-30：代理端按角色改文案，**只改标题不改列**）。
 * 当前路由命中该角色的**门户叶**时返回叶 label（AGENT 在 /devices 默认 tab → 「我的设备」），
 * 其余情况一律 undefined（非门户角色、非门户页、门户页的子 tab）。
 *
 * 匹配规则：path 相等，且——叶 href 带 tab/view 的须与 currentKey 相等
 * （/finance?tab=records → 我的收益）；不带的要求当前处于页面**默认 tab**（isDefault）——
 * 门户叶没细到 tab 的，不抢子 tab 的标题（AGENT 看 /devices?tab=monitor 时仍叫「实时监控」）。
 */
export function portalTitleOverride(
  v: ViewerLike, pathname: string, currentKey: string | null, isDefault: boolean,
): string | undefined {
  const { role } = asViewer(v);
  const p = normPath(pathname);
  for (const section of navTree()) {
    if (!role || !section.portalFor?.includes(role)) continue;
    for (const leaf of section.children ?? []) {
      const parts = leafParts(leaf.href);
      if (parts.path !== p) continue;
      const key = parts.tab ?? parts.view;
      if (key ? key === currentKey : isDefault) return leaf.label;
    }
  }
  return undefined;
}

/**
 * 把可见叶子按 group 聚成连续段（L2 分组）。
 * - 无 group 的叶子聚成 `{ group: undefined }` 段（渲染时不出标题）。
 * - 只合并**相邻**同名 group（不跨段合并）——保证渲染顺序 = 数据顺序，不隐式重排。
 * - 一段内若所有叶子都被 phase 锁定，该段整体灰显（调用方可据此收起）。
 */
export function groupedLeaves(leaves: NavLeaf[]): { group?: string; leaves: NavLeaf[] }[] {
  const out: { group?: string; leaves: NavLeaf[] }[] = [];
  for (const leaf of leaves) {
    const last = out[out.length - 1];
    if (last && last.group === leaf.group) last.leaves.push(leaf);
    else out.push({ group: leaf.group, leaves: [leaf] });
  }
  return out;
}

/**
 * 叶子是否被屏蔽 = 未就绪 且 超出当前分期。
 * `ready: true` 是逐叶解锁的覆盖开关（见 NavLeaf.ready）。
 */
export function isLeafLocked(leaf: NavLeaf): boolean {
  if (leaf.ready) return false;
  return isPhaseLocked(leaf.phase);
}

/** section 是否被产品分期屏蔽（整 section phase 或所有叶子均被锁）。 */
export function isSectionLocked(section: NavSection, v: ViewerLike): boolean {
  if (isPhaseLocked(section.phase)) return true;
  const leaves = visibleLeaves(section, v);
  return leaves.length > 0 && leaves.every((l) => isLeafLocked(l));
}

/** section 的路径归属前缀（含子路径如 /devices/detail）。 */
function sectionMatchPrefixes(section: NavSection): string[] {
  return section.match ?? [leafParts(section.href).path];
}

/**
 * 由 pathname 反推当前 section：最长前缀匹配；"/" 仅精确匹配。
 * 不做 RBAC 过滤——URL 已到达即需正确归属（页面自身有权限兜底）。
 */
export function findActiveSection(pathname: string, v?: ViewerLike): NavSection | undefined {
  const p = normPath(pathname);
  // ⚠️ 必须按角色限定搜索范围：门户 section（如代理端「我的经营」）与运营 section**共用同一批路径**
  // （/、/devices、/orders…）。不限定的话，排在前面的门户项会对所有角色命中，
  // 运营人员的面包屑会变成「我的经营 › …」。传 role 时只在该角色可见的 section 里找；
  // 不传时排除门户 section（对运营端是安全默认值）。
  const pool = v !== undefined && asViewer(v).role ? visibleSections(v) : navTree().filter((s) => !s.portalFor);
  let best: { section: NavSection; len: number } | undefined;
  for (const section of pool) {
    for (const prefix of sectionMatchPrefixes(section)) {
      const hit = prefix === "/" ? p === "/" : p === prefix || p.startsWith(prefix + "/");
      if (hit && (!best || prefix.length > best.len)) best = { section, len: prefix.length };
    }
  }
  return best?.section;
}

/**
 * 当前 section 的可见叶子中，命中项下标：
 * 先按 path+query 精确匹配；section 首页（无 tab/view）默认高亮首个可点叶子。
 * 未命中返回 -1。
 */
export function activeLeafIndex(
  leaves: NavLeaf[], pathname: string, tab: string | null, view: string | null,
): number {
  const p = normPath(pathname);
  const exact = leaves.findIndex((l) => {
    if (l.soon || isLeafLocked(l)) return false;
    const parts = leafParts(l.href);
    if (parts.path !== p) return false;
    if (parts.tab) return parts.tab === tab;
    if (parts.view) return parts.view === view;
    return !tab && !view;
  });
  if (exact >= 0) return exact;
  if (!tab && !view) {
    return leaves.findIndex((l) => !l.soon && !isLeafLocked(l) && leafParts(l.href).path === p);
  }
  return -1;
}

/** section 的默认落地地址：首个可点叶子（排除 soon 和 phase-locked），无则 section 首页。 */
export function sectionDefaultHref(section: NavSection, v: ViewerLike): string {
  const open = visibleLeaves(section, v).filter((l) => !l.soon && !isLeafLocked(l));
  /*
   * 落地页先认 section.href（2026-10-28，运营端菜单清单 §一）：菜单按运营顺序排之后，
   * 第一项不一定是高频页（网点管理的第一项是 BD CRM，日常却是站点管理）。
   * section.href 指向的叶子对当前角色可见且未锁时落到它，否则退回第一个可点叶 ——
   * 没这个码的角色不会落到一个看不见的页面上。
   */
  const own = open.find((l) => normPath(l.href) === normPath(section.href) || l.href === section.href);
  return (own ?? open[0])?.href ?? section.href;
}

/** 叶子是否不可点：待建 或 分期锁定（渲染层统一判定）。 */
export function isLeafDisabled(leaf: NavLeaf): boolean {
  return !!leaf.soon || isLeafLocked(leaf);
}

/**
 * 按 URL 判断当前路由是否被产品分期锁定（页面级兜底用）。
 * 返回锁定它的阶段 Phase（>CURRENT_PHASE），未锁定返回 undefined。
 * 与 activeLeafIndex 不同：此处「无视锁定」匹配目标叶，才能识别到被锁叶。
 */
export function routeLockedPhase(
  pathname: string, tab: string | null, view: string | null, v: ViewerLike,
): Phase | undefined {
  const section = findActiveSection(pathname, v);
  if (!section) return undefined;
  if (isPhaseLocked(section.phase)) return section.phase;
  const p = normPath(pathname);
  const leaves = visibleLeaves(section, v);
  let leaf = leaves.find((l) => {
    const parts = leafParts(l.href);
    if (parts.path !== p) return false;
    if (parts.tab) return parts.tab === tab;
    if (parts.view) return parts.view === view;
    return !tab && !view;
  });
  // section 首页（无 tab/view）：落到该 path 的首叶，兜底首叶
  if (!leaf && !tab && !view) {
    leaf = leaves.find((l) => leafParts(l.href).path === p) ?? leaves[0];
  }
  return leaf && isLeafLocked(leaf) ? leaf.phase : undefined;
}

/**
 * 面包屑：L1 › 分组 › 子功能。
 * 分组是视觉聚类不是可导航节点，仅作不可点的中间项；叶子无 group 时退化为两级。
 */
export function breadcrumb(
  pathname: string, tab: string | null, view: string | null, v: ViewerLike,
): string[] {
  const section = findActiveSection(pathname, v);
  if (!section) return [];
  const crumbs = [section.label];
  const leaves = visibleLeaves(section, v);
  const idx = activeLeafIndex(leaves, pathname, tab, view);
  if (idx >= 0) {
    const leaf = leaves[idx];
    if (leaf.group) crumbs.push(leaf.group);
    if (leaf.label !== section.label) crumbs.push(leaf.label);
  }
  return crumbs;
}
