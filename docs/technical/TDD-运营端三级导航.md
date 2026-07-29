# TDD-运营端三级导航（ops-web 3-level nav）

**文档状态**：已实现（2026-07-12）
**关联需求**：[docs/requirements/运营端功能清单.md](../requirements/运营端功能清单.md)（运营端功能 SSOT，14 模块）
**创建日期**：2026-07-12
**最后更新**：2026-07-12
**关联**：[tech-stack-frontend.md](./tech-stack-frontend.md) · [权限管理方案.md](./权限管理方案.md) · 参考实现 `ai-kb/frontend`（`components/layout/sidebar.tsx` + `secondary-nav.tsx`）

---

## 1. 需求摘要

把运营端 Web 的导航从**两级扁平侧栏**升级为 **ai-kb 式三级 + 综合域菜单**：
**① 最左「域」图标栏(L1) → ② 中间「模块=分组标题 / 子功能=条目」面板(L2/L3) → ③ 面包屑 + 详情页**。

本次仅重构**信息架构与导航呈现**，不改任何业务功能、URL、后端契约；14 模块的功能范围与 `运营端功能清单.md` 完全一致，故不新增 PRD。

关键验收标准：
- AC1：14 模块归入 **7 个域**（L1），域→模块(L2)→子功能(L3)→详情页 四层可达。
- AC2：L3 呈现可在 **「面板内分组」** 与 **「三列 Miller 逐列」** 两种模式间切换，偏好持久化（localStorage）。
- AC3：域/模块/子功能三级均沿用现有 RBAC（`canModule` / `can(perm)`）过滤，无权不显示。
- AC4：不改 URL、不改任何详情页；L3 深链沿用已实现的 `?tab=` / `?view=`。
- AC5：单模块域（概览/数据报表）跳过 L2 面板，直接全宽详情。
- AC6：`tsc` / `next build`（静态导出预渲染）全绿；导航纯函数有单测；关键交互实机验证。

---

## 2. 当前架构分析

### 2.1 相关现有模块
| 模块路径 | 职责 | 与本功能的关系 |
|---------|------|--------------|
| `ops-web/lib/nav.ts` | 两级导航数据（`NavItem`+`children`） | **需重构**为三层（域/模块/子功能） |
| `ops-web/components/layout/sidebar.tsx` | 单列两级折叠侧栏 | **被替换**为 L1 Rail + L2/L3 SecondaryNav |
| `ops-web/components/layout/app-shell.tsx` | 登录守卫 + 两栏布局 | **需修改**：加 Rail、SecondaryNav 两列 |
| `ops-web/components/layout/header.tsx` | 顶栏 | **需修改**：加面包屑 + 导航模式切换按钮 |
| `ops-web/lib/permissions.ts` | `can()` / `canModule()` RBAC | **直接复用**（域可见 = 域内任一模块 `canModule`） |
| `ops-web/lib/auth.ts` | Zustand+persist（role/token） | 复用；新增偏好 store 同款模式 |
| 各详情页（`app/*/page.tsx`） | 业务页 + 页内 tab/view | **不改**；`?tab=`/`?view=` 深链已就绪 |

### 2.2 现有数据流
`Sidebar` 读 `NAV` → `canModule` 过滤 → 渲染 → `Link` 跳 `href`（含 `?tab=` 深链）→ 详情页 `useSearchParams` 读初始 tab/view。本次只在**导航层**上方加一层「域」并重排呈现，数据流下游不变。

### 2.3 影响范围评估
- **直接影响**：`lib/nav.ts`、`sidebar.tsx`（弃用/替换）、`app-shell.tsx`、`header.tsx`。
- **间接影响**：无——详情页、API、权限码、URL 全部不变。
- **无影响**：`lib/api/*`、`lib/types.ts`、所有 `app/*/page.tsx` 业务逻辑、后端。

### 2.4 复用机会
- **ai-kb 参考组件**：`Sidebar`（可折叠图标栏 + tooltip + section）、`SecondaryNav`（`sections=[{heading,items}]` 分组面板 + active 逻辑）——照搬心智，按 powerbank 设计 token 落地。
- **RBAC**：`can()`/`canModule()` 原样复用。
- **深链高亮**：现 `sidebar.tsx` 里的 `?tab=`/`?view=` 匹配 + 默认首项逻辑，抽成纯函数复用。
- **持久化**：`auth.ts` 的 Zustand+persist 模式，用于导航偏好 store。

---

## 3. 技术方案

### 3.1 方案选型

| 方案 | 描述 | 优点 | 缺点 | 结论 |
|------|------|------|------|------|
| A（推荐）| **数据驱动**：`nav.ts` 升三层，新增 Rail + SecondaryNav 两组件，由 `pathname` 反推当前域；URL/路由不动 | 改动集中在导航层；详情页零改；静态导出无痛；可切换两种 L3 呈现 | 「当前域」需按路径反推（一次性 helper） | ✅ 采用 |
| B | **Next route groups**：`app/(device)/…` 分域，每域一个 `layout.tsx` 渲染 SecondaryNav（最贴 ai-kb 目录结构） | 目录即 IA；layout 天然分域 | 需搬移所有页面目录、`output: export` 下动态段/布局成本高、改 URL 风险 | ❌ churn 过大、破坏现有 URL |
| C | 保留两级侧栏，仅加"域"分组标题 | 改动最小 | 不满足"三级+综合域菜单+Miller 可切换"诉求 | ❌ 不达标 |

**选择 A 的核心理由**：导航是纯前端呈现，用**数据 + 路径反推**即可表达三级，无需动路由与详情页，最契合静态导出 SPA，且天然支持「面板/Miller」双呈现切换。

### 3.2 信息架构（7 域 · L1→L2→L3）

> L2「模块」沿用 `运营端功能清单.md` 的 14 模块与权限码；L3「子功能」为各模块子项（深链/待建标记见该清单）。

| L1 域 `key` | 图标 | L2 模块（module 码） | L3 子功能（示例） |
|------|:-:|------|------|
| `overview` 概览 | `layout-dashboard` | 经营看板 `dashboard`* | —（叶子域，直达详情） |
| `device-ops` 设备运营 | `cpu` | 设备管理 `device` · 工单管理 `workorder` | 设备台账/实时监控/远程控制/库存⋯ · 工单列表/看板/SLA |
| `place-bd` 渠道与场地 | `map-pin` | 站点与点位 `location` · 代理商管理 `agent` | 站点/点位/场地方/合同 · 档案/划拨/分润/结算 |
| `trade-fin` 交易与资金 | `receipt` | 订单管理 `order` · 计费定价 `pricing` · 财务管理 `finance` | 订单/异常/干预/退款 · 模板/差异化 · 分润/账务/结算/提现/对账 |
| `user-growth` 用户与服务 | `users` | 用户管理 `user` · 营销管理 `marketing` · 客服管理 `cs`† | 用户/风控/黑名单/会员/钱包 · 券/活动/推送/广告 · 报障/会话 |
| `analytics` 数据报表 | `chart-bar` | 数据报表 `report`†* | 设备/坪效/财务/大屏/自定义 |
| `system` 系统与权限 | `settings` | 员工与权限 `org` · 系统设置 `system` | 员工/角色/数据权限/审计 · 供应商/模板/字典/参数/OpenAPI |

> 三级菜单**逐项明细**（含每个 L3 的深链可用性、权限码、依赖、角色×域矩阵、开放决策）见 **附录A**——T1 的实现 SSOT。

\* `overview`/`analytics` 为**单模块域**：点 L1 图标直接进详情，不出 L2 面板（对齐 ai-kb `FULL_WIDTH_PATHS`）。
† `cs`（客服）、`report`（报表）目前无独立页面 → L2 标 `soon`（待建），点击落"待建"占位或先建骨架页（见 3.6 phasing）。

### 3.3 模块设计

**新增**：
- `lib/nav.ts`（重构）—— 三层数据 `NavDomain[] → NavModule[] → NavLeaf[]` + 纯函数 helper（可单测）。
- `lib/stores/nav-prefs.ts` —— Zustand+persist：`navMode: 'panel'|'miller'`、`railCollapsed: boolean` 及切换动作。
- `components/layout/rail.tsx` —— **L1 域图标栏**（w-14 可折叠、tooltip、顶部 brand、`system` 域固定底部；域按 RBAC 过滤；点域→跳该域首个可见模块 `href`）。
- `components/layout/secondary-nav.tsx` —— **L2/L3 面板**，两种 `mode`：
  - `panel`（ai-kb 原味）：单列 ~200px；每个模块 = 分组标题（可点进模块首页），其 `children` = 条目；模块无子项时其自身渲染为单条目。
  - `miller`（三列逐列）：两小列（模块列 ~150px + 子功能列 ~180px）；选中模块→右列出其子功能→点子功能进详情。（L1 Rail 即"域列"，故 Rail+本组件 = 三列 Miller。）
- `components/layout/nav-mode-toggle.tsx` —— 面板/Miller 切换小控件（放 SecondaryNav 头或 Header）。

**修改**：
- `components/layout/app-shell.tsx` —— 组合 `<Rail/> + <SecondaryNav/>(非单模块域时) + <div>{Header}{main}</div>`；登录守卫、`Suspense`（`useSearchParams`）保留。
- `components/layout/header.tsx` —— 加**面包屑**（域 › 模块 › 子功能，由 active 反推）+ 挂 `nav-mode-toggle`。

**弃用/复用**：
- `components/layout/sidebar.tsx` —— 逻辑迁入 Rail + SecondaryNav 后删除（其 `?tab=`/`?view=` 匹配逻辑抽成 `lib/nav.ts` 纯函数复用）。

### 3.4 核心接口设计

```typescript
// lib/nav.ts —— 三层数据模型
export interface NavLeaf {          // L3 子功能
  href: string;                     // 详情/深链（可含 ?tab= / ?view=）
  label: string;
  icon?: string;
  perm?: string;                    // 细粒度权限码；无则跟随父模块 canModule
  soon?: boolean;                   // 待建（禁用渲染）
}
export interface NavModule {        // L2 模块
  key: string;
  label: string;
  icon: string;
  module: string;                   // 权限码前缀（canModule 过滤）
  href: string;                     // 模块首页（无 children 时即叶子直达）
  soon?: boolean;
  children?: NavLeaf[];             // L3；无则模块本身为叶子
}
export interface NavDomain {        // L1 域
  key: string;
  label: string;
  icon: string;
  pinBottom?: boolean;              // 如 system 固定栏底
  modules: NavModule[];
}
export const NAV: NavDomain[];

// 纯函数 helper（无 React 依赖 → 可单测）
export function visibleDomains(role: Role | undefined): NavDomain[];              // 域可见 = 域内任一模块 canModule
export function visibleModules(domain: NavDomain, role): NavModule[];             // 模块 canModule
export function visibleLeaves(mod: NavModule, role): NavLeaf[];                   // leaf.perm ? can() : true
export function findActiveDomain(pathname: string, role): NavDomain | undefined;  // 由路径反推当前域
export function findActiveModule(pathname: string, role): NavModule | undefined;
export function isLeafActive(leaf: NavLeaf, pathname: string, tab: string|null, view: string|null): boolean; // 复用现深链匹配
export function isSingleModuleDomain(d: NavDomain): boolean;                      // 决定是否出 L2 面板
```

```typescript
// lib/stores/nav-prefs.ts
export type NavMode = 'panel' | 'miller';
interface NavPrefs {
  navMode: NavMode;
  railCollapsed: boolean;
  setNavMode: (m: NavMode) => void;
  toggleRail: () => void;
}
export const useNavPrefs; // Zustand + persist('ops-nav-prefs')
```

### 3.5 配置项
| 配置项 | 类型 | 说明 | 管理位置 |
|--------|------|------|---------|
| `NAV` | 常量 | 三层导航 SSOT（域/模块/子功能） | `lib/nav.ts` |
| `NAV_MODE_DEFAULT` | 常量 | 默认 `'panel'` | `lib/nav.ts` |
| `RAIL_WIDTH` / `PANEL_WIDTH` / `MILLER_*_WIDTH` | 常量 | 布局宽度（禁硬编码散落） | `lib/nav.ts` |
| `persist key` | 常量 | `'ops-nav-prefs'` | `lib/stores/nav-prefs.ts` |

无环境变量、无 DB、无 API 变更。

### 3.6 分期（phasing）
- **P1（本次）**：7 域 IA + Rail + SecondaryNav（panel + miller 可切换）+ 面包屑 + RBAC 过滤；已建模块全部接入。`cs`/`report` 标 `soon`。
- **P2（后续，另开 TDD）**：`客服管理`、`数据报表` 建详情页骨架，去掉 `soon`。

---

## 4. 测试策略

### 4.1 测试层级规划
| 层级 | 覆盖目标 | 工具 |
|------|---------|------|
| 单元测试 | `lib/nav.ts` 纯函数：`visibleDomains/Modules/Leaves`、`findActiveDomain/Module`、`isLeafActive`、`isSingleModuleDomain` | Vitest（若 ops-web 无 runner，本次引入最小 vitest 配置） |
| 构建校验 | `tsc --noEmit` + `next build`（`output: export` 预渲染，验证 `useSearchParams`/`Suspense`） | Next/tsc |
| 交互验证 | Rail 切域、panel↔miller 切换、深链高亮、单模块域全宽、RBAC 三级过滤 | 浏览器预览（mock 数据）实机 |

### 4.2 关键测试场景
1. **RBAC 域过滤**：VIEWER → 只出含可见模块的域；`system` 域对 VIEWER 隐藏（无 `org`/`system` 权限）。
2. **RBAC 叶子过滤**：VIEWER 的财务模块下不出"对账"（`finance:reconcile:read` 缺）。
3. **当前域反推**：`/finance?tab=ledger` → active 域=`trade-fin`、模块=财务、叶子=账务分录高亮。
4. **模式切换持久化**：切到 miller → 刷新后仍 miller（localStorage）。
5. **单模块域**：进 `/`（概览）/报表 → 不渲染 L2 面板，详情全宽。
6. **深链默认项**：进裸 `/finance`（无 query）→ 默认高亮首个可见子功能。
7. **待建项**：`cs`/`report`/对账 等 `soon` 项渲染为禁用不可点、不 404。

---

## 5. 风险与注意事项
- **当前域反推歧义**：模块 `href` 前缀需互不为前缀子串（如 `/system/vendors` vs `/system`）；`findActiveModule` 用**最长前缀匹配** + 归一化尾斜杠（`trailingSlash:true` 坑，沿用现 `norm()`）。
- **静态导出**：任何 `useSearchParams` 必须包 `Suspense`（Rail/SecondaryNav 若读 query 同样处理）；已被 `next build` 预渲染兜住。
- **Miller 竖向高度**：子功能多时右列滚动，避免撑破；`overflow-y:auto`。
- **RTL（ar）**：Rail 在 RTL 应贴右；本次用逻辑属性（`inline-start`）或留 TODO，不阻塞（ar 为后续）。
- **P6 保护**：详情页与 `permissions.ts` 不改，现有实机验证过的登录/工作台/设备/财务等功能零回归；仅删 `sidebar.tsx`（其能力已迁移，非业务逻辑）。

---

## 6. 实现任务
- [x] T1 `lib/nav.ts`：三层数据（7 域）+ 纯函数 helper + 布局常量
- [x] T2 `lib/stores/nav-prefs.ts`：navMode/railExpanded（persist，key=`ops-nav-prefs`）
- [x] T3 `components/layout/rail.tsx`：L1 域图标栏（展开/收起 + 自绘 tooltip + RBAC + pinBottom + 待建灰显）
- [x] T4 `components/layout/secondary-nav.tsx`：L2/L3，`panel` + `miller` 双模式（miller 支持逐列浏览不跳页）
- [x] T5 模式切换控件（并入 secondary-nav.tsx 的 `ModeToggle`，置于面板头部，未单拆文件）
- [x] T6 `app-shell.tsx`：三列布局 + 单模块域全宽 + Suspense
- [x] T7 `header.tsx`：面包屑（域›模块›子功能）
- [x] T8 删除 `sidebar.tsx`（能力已迁移）
- [x] T9 Vitest：`lib/nav.test.ts` 29 用例全过（角色×域矩阵/叶子过滤/路径反推/默认叶子/soon/面包屑）
- [x] T10 `tsc` + `next build`（16 页静态预渲染）全绿；实机验证（ADMIN 深链四联动、panel↔miller 切换+localStorage 持久化、miller 逐列浏览、概览全宽、VIEWER 三级裁剪、数据报表域灰显）
- [x] T11 已回填 `实现状态总表.md` 变更日志 + 本 TDD 状态→已实现

---

## 附录A 三级菜单明细（T1 实现 SSOT）

> 图例——深链：`现有`（页面已支持）/ `加tab`（需页面加 tab 才可直达）/ `待建页` · 菜单项与「页内能力」严格区分：抽屉/行内动作**不是菜单项**，仅挂权限码门控。
>
> ⚠️ **本附录的「状态」列已于 2026-07-29 冻结（停止维护，保留为 T1 设计快照）**：全量补齐后已大面积过时（如 `/cs`、`/reports` 标「待建页」但页面早已建成）。
> **实现度与交付阶段的唯一 SSOT = [运营端功能清单 §三 逐叶矩阵](../requirements/运营端功能清单.md)**（含入口/建设优先级/阶段/前端实现度，与 nav.ts 叶子 1:1）。本附录仅继续承担**菜单结构与权限码**的对照职责；改菜单流程不变：改本附录结构 → 同步 `lib/nav.ts` → `npx vitest run`。

### A.1 域① 概览 `overview`（单模块域，L1 直达全宽，无 L2 面板）

| 层 | 条目 | 入口/深链 | 权限码 | P | 状态 | 依赖/备注 |
|---|------|----------|--------|:-:|:-:|------|
| L2 | 经营看板 `dashboard` | `/` | `dashboard:overview:read` | P0 | ◐ | 唯一模块，菜单无 L3 |
| 页内 | 经营总览 KPI | 页内 | 同上 | P0 | ✅ | 数据源：order/device/workorder 聚合 |
| 页内 | 收入趋势 | 页内 | 同上 | P0 | ✅ | |
| 页内 | 实时告警 | 页内 | 同上 | P0 | ◐ | 跳转依赖：离线→设备、异常→订单、超时→工单 |
| 页内 | 待办中心 | 页内 | `dashboard:todo:read` | P1 | ⬜ | 跨域聚合：待派单(工单)+待审退款(订单)+待审提现(财务) |
| 页内 | 排名榜单 / 快捷入口 | 页内 | `dashboard:overview:read` | P1 | ⬜ | 快捷入口调用 device:command / workorder:create |

### A.2 域② 设备运营 `device-ops`

| 层 | 条目 | 入口/深链 | 权限码 | P | 状态 | 依赖/备注 |
|---|------|----------|--------|:-:|:-:|------|
| **L2** | **设备管理** `device` | `/devices` | `device:*` 前缀 | P0 | ◐ | |
| L3 | 设备台账 | `/devices` `现有` | `device:cabinet:read` | P0 | ◐ | 行内钻取 → `/devices/detail?no=`（详情+仓位 ✅，非菜单） |
| L3 | 充电宝管理 | `?tab=powerbanks` `加tab` | `device:powerbank:*` | P0 | ◐ | 现仅详情页仓位明细内可见，独立清单待建 |
| L3 | 实时监控 | `?tab=monitor` `加tab` | `device:cabinet:read` | P0 | ◐ | 在线态已在台账列；告警→自动开单联动工单；地图 P1 归此 tab |
| L3 | 远程控制·指令记录 | `?tab=commands` `加tab` | `device:command:*` | P0 | ⬜ | 单柜指令已在详情页 ◐；批量指令+下发记录待建 |
| L3 | 库存调拨 | `?tab=inventory` `加tab` | `device:inventory:read` | P1 | ⬜ | soon 灰显 |
| L3 | 固件 OTA | `?tab=ota` `加tab` | `device:ota:read` | P1 | ⬜ | soon 灰显；参考 neargo PF10 三层 OTA |
| 页内 | 新增/编辑/导入导出 | 台账内 | `device:cabinet:*` | P0 | ⬜ | 动作，非菜单 |
| **L2** | **工单管理** `workorder` | `/work-orders` | `workorder:*` | P0 | ◐ | |
| L3 | 工单列表 | `?view=list` `现有` | `workorder:wo:read` | P0 | ◐ | |
| L3 | 工单看板 | `?view=board` `现有` | `workorder:wo:read` | P0 | ◐ | 5 状态列 |
| L3 | SLA 管理 | `?view=sla` `加tab` | `workorder:*` | P1 | ⬜ | soon |
| L3 | 巡检计划 | `?view=inspection` `加tab` | `workorder:*` | P1 | ⬜ | soon；自动开单 |
| 页内 | 开单/派单/处理验收 | 列表+抽屉 | `wo:create`/`wo:dispatch` | P0 | ◐ | 入向依赖：设备告警自动开单、客服报障转单、代理报修 |

### A.3 域③ 渠道与场地 `place-bd`

| 层 | 条目 | 入口/深链 | 权限码 | P | 状态 | 依赖/备注 |
|---|------|----------|--------|:-:|:-:|------|
| **L2** | **站点与点位** `location` | `/locations` | `location:*` | P0 | ◐ | 层级 Venue→Site→Point→机柜（ADR-013） |
| L3 | 站点管理 | `?tab=sites` `现有` | `location:poi:*` | P0 | ◐ | 站点 agent_no 归属 → 分润 + 数据权限 AGENT 的锚点 |
| L3 | 点位管理 | `?tab=points` `现有` | `location:poi:*` | P0 | ◐ | 归属继承站点 |
| L3 | 场地方 | `?tab=venues` `现有` | `location:venue:*` | P0 | ◐ | |
| L3 | 进场合同 | `?tab=contracts` `现有` | `location:contract:*` | P1 | ◐ | 合同分成比例 → 财务分润规则 VENUE 维度的数据源 |
| L3 | BD 拓展 CRM | `?tab=crm` `加tab` | `location:*` | P1 | ⬜ | soon |
| L3 | 站点坪效 | `?tab=analysis` `加tab` | `location:analysis:read` | P1 | ⬜ | soon；与报表域「点位坪效」`report:location:read` 是不同权限码 |
| **L2** | **代理商管理** `agent` | `/agents` | `agent:*` | P0 | ◐ | ADR-012：体内伙伴，非租户 |
| L3 | 代理商档案 | `/agents` `现有` | `agent:agent:*` | P0 | ◐ | 配置抽屉 ✅ |
| L3 | 设备/点位划拨 | `?tab=assign` `加tab` | `agent:scope:assign` | P0 | ⬜ | 跨模块写：改 dev_cabinet/loc_* 的 agent_no |
| L3 | 代理收益结算 | → `/finance?tab=settlements` 跨域深链 | `agent:settlement:read` | P1 | ◐ | 见决策 D3 |
| L3 | 代理绩效 | `?tab=performance` `加tab` | `agent:performance:read` | P1 | ⬜ | soon |
| L3 | 代理账号管理 | `?tab=accounts` `加tab` | `agent:agent:*` | P0 | ⬜ | 联动 org 数据权限：开号即绑 AGENT 范围 |
| 页内 | 代理分润配置 | 档案抽屉内 | `agent:share:config` | P0 | ◐ | 复用 share_rule(dimension=AGENT) |
| — | 〔代理端〕看板/设备/订单/收益/报修 | 非本菜单 | 数据权限收敛 agent_no | P1 | ⬜ | AGENT 登录走同一菜单树被 RBAC 裁剪（见 D4） |

### A.4 域④ 交易与资金 `trade-fin`

| 层 | 条目 | 入口/深链 | 权限码 | P | 状态 | 依赖/备注 |
|---|------|----------|--------|:-:|:-:|------|
| **L2** | **订单管理** `order` | `/orders` | `order:*` | P0 | ✅ | |
| L3 | 订单列表 | `/orders` `现有` | `order:order:read` | P0 | ✅ | 进行中=列表筛选（页内），不设独立 L3 |
| L3 | 异常订单 | `?tab=exceptions` `加tab` | `order:exception:handle` | P0 | ◐ | 未弹/未还/买断/重复扣费 |
| 页内 | 订单详情/时间线 | 抽屉 | `order:order:read` | P0 | ✅ | |
| 页内 | 订单干预 | 抽屉 | `order:intervene:execute` | P0 | ✅ | CS；下行依赖 gateway 指令；审计留痕→org:audit |
| 页内 | 退款申请/审批 | 抽屉 | `order:refund:apply` / `:audit` | P0 | ◐ | 双角色流：CS 申请→FIN 审批→nearpay 执行（ADR-005），幂等 |
| **L2** | **计费定价** `pricing` | `/pricing` | `pricing:*` | P0 | ◐ | |
| L3 | 计费模板 | `/pricing` `现有` | `pricing:rule:*` | P0 | ◐ | 改动仅影响新订单；订单计费引用此模板 |
| L3 | 差异化定价 | `?tab=diff` `加tab` | `pricing:*` | P1 | ⬜ | soon；按点位/场景取价 → 依赖 location |
| L3 | 活动/时段价 | `?tab=schedule` `加tab` | `pricing:*` | P2 | ⬜ | soon |
| **L2** | **财务管理** `finance` | `/finance` | `finance:*` | P0 | ◐ | 支付/打款全委托 nearpay，本模块只管账 |
| L3 | 分润规则 | `?tab=rules` `现有` | `finance:share_rule:*` | P0 | ◐ | 维度 VENUE(←合同)/AGENT(←代理)；双模式（ADR-004） |
| L3 | 分润明细 | `?tab=records` `加tab` | `finance:share_record:read` | P0 | ◐ | 逐单记录，现无独立 tab |
| L3 | 账务分录 | `?tab=ledger` `现有` | `finance:*` | P0 | ◐ | 复式、只增；先记账后执行 |
| L3 | 结算单 | `?tab=settlements` `现有` | `finance:settlement:*` | P1 | ◐ | 对象=场地方/代理 |
| L3 | 提现审核 | `?tab=withdrawals` `现有` | `finance:withdrawal:audit` | P1 | ◐ | 审核→nearpay 打款 |
| L3 | 对账 | `?tab=reconcile` `加tab` | `finance:reconcile:read` | P1 | ⬜ | soon；nearpay↔支付引用↔账务三方 |
| L3 | 发票 | `?tab=invoices` `加tab` | `finance:invoice:*` | P1 | ⬜ | soon；VAT/TRN（UAE） |
| L3 | 代理分润配置 | → `/agents?tab=commission` 跨域深链 | `agent:settlement:read` | P0 | ✅ | 导航审查 #4：FINANCE 岗回链，避免代理/场地分润跨域找不到入口 |

### A.5 域⑤ 用户与服务 `user-growth`

| 层 | 条目 | 入口/深链 | 权限码 | P | 状态 | 依赖/备注 |
|---|------|----------|--------|:-:|:-:|------|
| **L2** | **用户管理** `user` | `/users` | `user:*` | P0 | ✅ | |
| L3 | 用户列表 | `/users` `现有` | `user:cuser:read` | P0 | ✅ | |
| L3 | 会员/次卡 | `?tab=members` `加tab` | `user:member:read` | P1 | ⬜ | soon |
| L3 | 钱包 | `?tab=wallets` `加tab` | `user:wallet:read` | P1 | ⬜ | soon |
| 页内 | 信用风控/黑名单 | 行内+抽屉 | `user:risk:update` | P0 | ✅ | 信用分→C端免押额度；黑名单→拦下单 |
| **L2** | **营销管理** `marketing` | `/marketing` | `marketing:*` | P1 | ◐ | |
| L3 | 优惠券 | `/marketing` `现有` | `marketing:coupon:*` | P1 | ◐ | |
| L3 | 活动 / 推送触达 / 邀请裂变 | `加tab` ×3 | `marketing:*` · `:push:send` | P1/P2 | ⬜ | soon |
| L3 | 广告位/广告活动/投放曝光 | `加tab` ×3 | `marketing:*` | P2 | ⬜ | soon；广告位挂机柜(device)、广告收入未来入分润(finance) |
| **L2** | **客服管理** `cs` | `/cs` `待建页` | `cs:*` | P0 | ⬜ | L2 整体 soon 灰显（P2 期建页） |
| L3 | 报障受理 | `/cs` `待建页` | `cs:*` | P0 | ⬜ | 受理→转工单(workorder)/转退款(order) |
| L3 | 客服会话 | `/cs?tab=sessions` `待建页` | `cs:*` | P1 | ⬜ | |
| L3 | 退款/补偿 | → `/orders` 跨域深链 | `order:refund:apply` | P0 | ✅ | 复用订单页能力 |
| L3 | 黑名单处理 | → `/users` 跨域深链 | `user:risk:update` | P0 | ✅ | 复用用户页能力 |

### A.6 域⑥ 数据报表 `analytics`（单模块域）

| 层 | 条目 | 入口/深链 | 权限码 | P | 状态 | 依赖/备注 |
|---|------|----------|--------|:-:|:-:|------|
| L2 | 数据报表 `report` | `/reports` | `report:*` | P1 | ✅ | 全部为专题分析报表（阶段 2/3）；PDF 的「基础运营报表 P1」由概览域经营看板承载 → **P1 本域整体灰显属预期**（导航审查 #2 定调）|
| L3 | 设备运营分析 | `?tab=device` | `report:device:read` | P1 | ⬜ | 读 device：在线率/翻台/故障率 |
| L3 | 点位坪效 | `?tab=location` | `report:location:read` | P1 | ⬜ | 读 location+finance |
| L3 | 财务报表 | `?tab=finance` | `report:*` | P1 | ⬜ | 读 finance 汇总 |
| L3 | 实时大屏 / 自定义报表 | `?tab=screen` / `?tab=custom` | `report:*` | P2 | ⬜ | 只读聚合层，无写依赖 |

### A.7 域⑦ 系统与权限 `system`（Rail 固定底部 `pinBottom`）

| 层 | 条目 | 入口/深链 | 权限码 | P | 状态 | 依赖/备注 |
|---|------|----------|--------|:-:|:-:|------|
| **L2** | **员工与权限** `org` | `/employees` | `org:*` | P0 | ◐ | |
| L3 | 员工 | `?tab=employees` `现有` | `org:employee:*` | P0 | ✅ | |
| L3 | 角色权限 | `?tab=roles` `现有` | `org:role:*` | P0 | ◐ | 权限码 SSOT=`功能权限清单.md`，前后端同源 |
| 页内 | 数据权限抽屉 | 角色 tab 内 | `org:role:*` | P0 | ◐ | ALL/REGION/SITE/LOCATION/VENUE/AGENT/SELF，对齐后端 DataScopeInterceptor（全维度首期）；AGENT←ADR-012 |
| L3 | 操作审计 | `?tab=audit` `现有` | `org:audit:read` | P0 | ✅ | FINANCE 也有此码（合规）；干预/退款/提现动作写入 |
| L3 | 组织架构 | `?tab=org` `加tab` | `org:dept:*` | P0 | ⬜ | soon |
| L3 | 绩效报表 | `?tab=performance` `加tab` | `org:*` | P1 | ⬜ | soon；读 workorder/cs 数据 |
| **L2** | **系统设置** `system` | `/system/vendors` | `system:*` | P0 | ◐ | |
| L3 | 供应商接入 | `/system/vendors` `现有` | ⚠️ 按钮=`device:vendor:*` | P0 | ✅ | 权限双源，见决策 D1；driver 状态→gateway |
| L3 | 通知模板 | `/system?tab=notify` `待建页` | `system:notify_template:read` | P0 | ⬜ | ar/en 多语 |
| L3 | 参数字典 | `?tab=dict` `待建页` | `system:dict:read` | P0 | ⬜ | |
| L3 | 地区库 | `?tab=region` `待建页` | `system:region:*` | P0 | ⬜ | region_id 源头 → 数据权限 REGION 维度依赖它 |
| L3 | 系统参数 | `?tab=params` `待建页` | `system:param:read` | P0 | ⬜ | 心跳阈值→设备监控、指令超时→远控、计费默认→pricing |
| L3 | OpenAPI 应用 | `?tab=openapi` `待建页` | `system:openapi:*` | P1 | ⬜ | |

### A.8 层间依赖规则（实现判定逻辑）

1. **L1 可见性是派生的，无独立权限码**：域可见 = 域内任一 L2 `canModule` 命中；点 L1 落到该域首个可见 L2 的 href。
2. **L2 可见性 = `canModule`**；L2 `soon`（客服/报表）不影响域可见性但灰显不可点。
3. **L3 可见性 = `leaf.perm ? can() : 跟随父 L2`**；`soon` 灰显。
4. **面包屑/当前域反推**（最长前缀匹配 + 尾斜杠归一）：`/`→概览 · `/devices*`,`/work-orders`→设备运营 · `/locations`,`/agents`→渠道与场地 · `/orders`,`/pricing`,`/finance`→交易与资金 · `/users`,`/marketing`,`/cs`→用户与服务 · `/reports`→数据报表 · `/employees`,`/system*`→系统与权限。无歧义前缀。

### A.9 角色 × 域 可见性矩阵（由 permissions.ts 推导，单测锚点）

| 域 | ADMIN | OPS | CS | FINANCE | BD | VIEWER | AGENT |
|------|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| 概览 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 设备运营 | ✓ | ✓ | ✓ | ✗ | ✗ | ✓(仅设备) | ✓ |
| 渠道与场地 | ✓ | ✓ | ✗ | ✓ | ✓ | ✓(仅站点) | ✓ |
| 交易与资金 | ✓ | ✓(仅订单) | ✓(仅订单) | ✓ | ✓(计费+分润读) | ✓(只读) | ✓ |
| 用户与服务 | ✓ | ✗ | ✓ | ✓(仅用户读) | ✓(仅营销) | ✗ | ✗ |
| 数据报表 | ✓ | ✓ | ✗ | ✓ | ✓ | ✓ | ✗ |
| 系统与权限 | ✓ | ✓(仅系统读) | ✗ | ✓(仅审计) | ✗ | ✗ | ✗ |

### A.10 深链缺口清单（L3 直达需页面配合）

| 页面 | 已支持 | 需新增 tab/view key |
|------|--------|---------------------|
| `/locations` `/finance` `/employees` `/work-orders` | `?tab=`/`?view=` ✅ | finance: `records` `reconcile` `invoices`；work-orders: `sla` `inspection`；employees: `org` `performance` |
| `/devices` | 无 tab | `powerbanks` `monitor` `commands` `inventory` `ota` |
| `/orders` `/pricing` `/users` `/marketing` `/agents` | 无 tab | orders: `exceptions`；pricing: `diff` `schedule`；users: `members` `wallets`；marketing: 6 个；agents: `assign` `performance` `accounts` |
| `/cs` `/reports` `/system`(除 vendors) | — | 整页待建（P2） |

> P1 期只渲染「现有」深链为可点 L3，其余一律 `soon` 灰显——不产生任何 404 入口。

### A.11 开放决策

- **D1 供应商接入权限双源**：模块门 `system`、按钮码 `device:vendor:*`。建议保持现状（菜单按 system 过滤、按钮按 device:vendor 门控），在 `功能权限清单.md` 加注释，不改码。
- **D2 数据报表域 P1 呈现**：整域待建。建议 Rail 图标对有 report 权限角色显示但灰显 tooltip「待建」，不隐藏。
- **D3 跨域深链面包屑归属**：「代理收益结算→/finance」「客服退款→/orders」点击后面包屑按 URL 反推显示目标域。接受此行为（同 ai-kb activeFor 心智），不做双高亮。
- **D4 代理端**：AGENT 不做独立 IA，同一菜单树被 RBAC+数据权限裁剪（A.9 矩阵末列即其可见范围）。

---

确认记录：2026-07-12 用户确认（含附录A 三级明细、L1↔L2/L2↔L3 对照、开放决策 D1–D4 默认值）。
