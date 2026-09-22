# TDD-分期屏蔽功能（Phase Gating）

状态：已实现（2026-07-13）· **门禁语义已修订，见 §1.1（2026-07-30）**
关联需求：[PRD-分期路线图](../requirements/PRD-分期路线图.md) · [运营端功能清单 §二·B](../requirements/运营端功能清单.md)
创建日期：2026-07-13

## 1. 需求摘要
甲方 V4 路线图把功能分三期交付（P1 MVP / P2 规模化 / P3 生态）。要求：**所有功能一次性开发并验证完成，但对外按期解锁**——当前阶段之后的功能在导航中灰显不可点，页面直达时兜底提示，切换阶段仅改环境变量、不改代码。

## 1.1 修订：门禁语义从「分期」改为「就绪度」（2026-07-30）

§1 原始需求成立的前提是「所有功能一次性开发并验证完成」。逐菜单核查后这个前提**不成立**：
98 个叶子里 55 个前端未成型（F0/F1/F2），且约 60 条前端调用的后端端点不存在
（详见 [运营端-未实现功能任务清单](运营端-未实现功能任务清单.md)）。

于是 60 个灰叶子的灰色掩盖了三种完全不同的状态：
① 已建成、只是排期靠后；② 页面是假数据；③ 后端端点压根不存在。
一个字段表达两件事（「第几期」与「能不能点」），谁都看不出该修哪个。

**修订**：拆成两维。
- `phase` —— 只表达产品分期，退化为**徽章**，不再是门禁。
- `NavLeaf.ready?: boolean` —— **门禁**。某叶前后端贯通并验证后标 `ready: true`。

```ts
// 新语义（lib/nav.ts）
isLeafLocked(leaf) = !leaf.ready && isPhaseLocked(leaf.phase)
```

约束：`ready` 只放宽 `phase`，**不放宽** `perm`（无权限仍不可见）与 `soon`（待建仍不可点）——
§3 方案选型里否决方案 B 的理由（「待建 vs 已建未解锁」语义混淆）依然有效，三者是三个独立概念。

后果：解锁不再是「改一个环境变量全亮」，而是**逐叶推进的结果**。`CURRENT_PHASE` 保留，
决定尚未标 ready 的叶子的默认可见性；已就绪但排期靠后的叶子可点且继续显示 P2/P3 徽章
（否则界面上看不出自己在用超前功能）。回归用例见 `lib/nav.test.ts`「ready 覆盖 phase」三条。

## 2. 当前架构分析
- 复用 `soon`（待建灰显）的既有心智：nav.ts 已有 `soon` 灰显 + SecondaryNav `LeafRow` 灰显渲染 + `activeLeafIndex` 跳过 soon。分期屏蔽是同构能力，复用其渲染/跳过路径。
- 菜单 SSOT = `lib/nav.ts`（NavDomain→NavModule→NavLeaf 纯函数）。
- 阶段标注 SSOT = 运营端功能清单 §二·B 表。

## 3. 方案设计
### 方案选型
| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A（推荐）nav.ts 加 `phase` 字段 + 环境变量 `CURRENT_PHASE` 派生 `isPhaseLocked` | 与 soon 同构、零散落 if、切期只改 env | 需在渲染层区分 soon vs phase 徽章 | ✅ 采用 |
| B 用 `soon` 表达未解锁 | 零新增字段 | 语义混淆（待建 vs 已建未解锁）、无法区分 P2/P3 | ❌ |
| C 运行时接口开关 | 可热切 | MVP 无后端配置面、过度设计 | ❌ |

### 模块设计
- **新增** `lib/phase.ts`：`Phase=1|2|3`、`CURRENT_PHASE`（读 `NEXT_PUBLIC_CURRENT_PHASE`，默认 1）、`PHASE_LABEL`、`isPhaseLocked(phase)`。
- **修改** `lib/nav.ts`：`NavLeaf.phase?` / `NavModule.phase?` 字段；逐叶按 §二·B 标注；新增纯函数 `isLeafLocked(leaf)` / `isModuleLocked(mod,role)`；`activeLeafIndex` 跳过 locked 叶；`moduleDefaultHref` 落到首个未锁叶。
- **修改** `components/layout/secondary-nav.tsx`：`LeafRow` 对 locked 叶灰显 + 阶段徽章（P2/P3）；模块整锁同理。
- **修改** `components/ui/tab-header.tsx` / 各页 tabs：locked tab 灰显（复用 nav 数据）。
- **新增** `components/phase-guard.tsx`：页面级兜底——URL 直达 locked 功能时提示「该功能将于 Pn 阶段开放」。
- **修改** `lib/i18n/messages/*`：`phase.locked` / `phase.p2` / `phase.p3` 文案。

### 核心接口
```ts
// lib/phase.ts
export type Phase = 1 | 2 | 3;
export const CURRENT_PHASE: Phase;
export function isPhaseLocked(phase: Phase | undefined): boolean; // phase > CURRENT_PHASE
// lib/nav.ts
export function isLeafLocked(leaf: NavLeaf): boolean;
export function isModuleLocked(mod: NavModule, role?: Role): boolean;
```

### 配置项
- `NEXT_PUBLIC_CURRENT_PHASE`（1/2/3，默认 1）→ `.env.local.example` 补注释。零硬编码：阶段数值只在 phase.ts 读取。

## 4. 测试策略
- 单测 `lib/nav.test.ts` 扩展：
  - `isPhaseLocked`：CURRENT_PHASE=1 时 phase2/3 锁、phase1/undefined 不锁。
  - `isLeafLocked` / `isModuleLocked`。
  - `activeLeafIndex` 跳过 locked 叶。
  - `moduleDefaultHref` 落到首个未锁叶。
- 关键场景：默认(P1)下 nav 只亮 P1 叶、P2/P3 灰显带徽章；env 切 P2 后 P2 叶解锁。
- 构建：`next build` 全绿；浏览器实测灰显 + 徽章 + env 切换。

## 5. 风险与注意事项
- 阶段标注与 §二·B 表须一致（改一处即改两处）。
- 静态导出：`CURRENT_PHASE` 是**构建期**注入，切期需重新 `next build`（符合「按期交付」节奏，非运行时热切）。
- 权限(perm)与阶段(phase)正交：先按 perm 过滤可见性，再按 phase 决定是否灰锁。

## 6. 实现任务
- [x] 新增 lib/phase.ts
- [x] nav.ts 加 phase 字段 + 逐叶标注 + isLeafLocked/isModuleLocked/isLeafDisabled/routeLockedPhase
- [x] activeLeafIndex/moduleDefaultHref/domainDefaultHref 跳过 locked
- [x] secondary-nav 灰显 + PhaseBadge 徽章（P2/P3）
- [x] tab-header locked tab 隐藏（visibleTabs 过滤）+ 各页 TABS 逐 tab 标 phase
- [x] phase-guard 页面兜底（AppShell 集中式，routeLockedPhase 判定）
- [x] i18n 文案（zh/en/ar phase.*）
- [x] .env 注释（NEXT_PUBLIC_CURRENT_PHASE）
- [x] 单测扩展（48 过）+ next build（19 路由绿）+ 实测

## 7. PDF 对照新增功能（一次性实现、按期解锁）
- 押金与欠费管理（订单域 P2）：`/orders?tab=deposit` + DepositRecord 类型/mock/api
- 多国家市场架构（系统域 P3）：`/system?tab=markets` + MarketCountry
- 消费者分析（报表域 P3）：`/reports?tab=consumer` + ConsumerSegment
均实测 P3 下正常渲染、P1 下 nav 灰显+页内 tab 隐藏。

---
确认记录：2026-07-13 用户指示按此思路（一次性开发、多阶段交付、按期屏蔽）实施。
