# TDD-运营端前端静态收尾（mock 层缺口三项）

状态：已确认（见文末确认记录）
关联需求：[运营端功能清单 §三](../requirements/运营端功能清单.md) · [前端静态实现-逐菜单待办清单](前端静态实现-逐菜单待办清单.md)（遗留标注即需求）
创建日期：2026-08-04

## 1. 需求摘要

功能矩阵 98 叶已 100% 有页面（F3 95/98）。本 TDD 收掉其中**前端可独立推进、无需拍板**的
真实缺口，全部按既有约定「纯前端 + mock 真落库、契约对齐后端、将来一键切后端」实现：

| # | 项 | 需求出处 |
|---|---|---|
| A | 订单页读 `?keyword=` 深链（经营看板「查订单」跳转带单号） | 逐菜单清单 §二 经营总览行「🔸 遗留」 |
| B | 差异化定价开 SCENE / LOCATION 两维（现只有 SITE） | 逐菜单清单 §二 差异化定价行「⚠️ 遗留」；后端 `PriceRule.dimension` 已有三维 |
| C | 代理端按 AGENT 视角改页面标题（只改标题不改列） | 拍板 #5（2026-07-30 已确认「做」，一直未实施） |

**核实后剔除的第 4 项**：绩效报表「数据非工单派生」——已由并行会话完成
（`lib/mock/db/workorder.ts` 种子补 `handlerName/acceptedAt/completedAt` 且用员工真名、
`lib/mock/db/org.ts#buildStaffPerformances` 真从工单聚合），本轮只做验证与文档回填，不重复实现。

## 2. 当前架构分析

- **A**：`app/orders/page.tsx` 已读 `?tab=`，未读 `?keyword=`；`components/quick-actions.tsx`
  的「去订单页做干预」链接已带 `keyword` 参数并注释说明等待此项。纯页面态初始化，无 mock 改动。
- **B**：`PricingDiff`（`lib/types/pricing.ts`）只有 `siteNo` 定位键；后端
  `PriceRule`（sharehub-svc-core）为 `dimension(SITE/LOCATION/SCENE) + matchRef` + 冗余列。
  mock 校验在 `lib/mock/db/pricing.ts#savePricingDiff`（站点必须存在、冗余列服务端反查覆盖），
  引用完整性由 `pricing.test.ts` + `integrity.test.ts` 钉住——三维扩展必须保持同等强度。
- **C**：页面标题由 `components/ui/tab-header.tsx` 的 h1（= 当前 tab label）与
  `components/ui/misc.tsx#PageTitle`（工作台）渲染；AGENT 门户叶子（label 即「我的设备」等）
  在 `lib/nav.ts` 的 `portalFor` section 里，是现成的标题 SSOT。面包屑已按门户显示，缺的只是 h1。

## 3. 方案设计

### A 订单 keyword 深链
`useState(sp.get("keyword") ?? "")` 初始化 + `useEffect` 响应参数变化（同页再次点深链也生效），
变化时重置分页。只作用于列表 tab（深链 `?tab=list&keyword=`）。不新增组件。

### B 差异化定价三维
- 类型层（SSOT）：`PricingDimension = "SITE" | "LOCATION" | "SCENE"`；
  `PRICING_DIMENSION_LABEL` 供表单/徽标/导出共用。`PricingDiff` 增 `dimension` + `matchRef`
  （对齐后端同名字段）；`siteNo/scene/locationName` 降为**冗余展示列**（SCENE 维 siteNo/locationName 为空串）。
- mock 层：`savePricingDiff` 按维度校验——SITE：站点须存在；LOCATION：点位须存在（站点/场景随点位反查）；
  SCENE：场景值须是站点表里真实出现过的 `sceneType`（防止规则挂在没有任何站点的场景上悬空）。
  冗余列一律服务端反查覆盖，不采信调用方。兼容旧调用形状（无 dimension 视为 SITE、`siteNo` 充当 matchRef）。
- 种子：12 条 SITE + 3 条 LOCATION + 2 条 SCENE，三维在页面上都看得见。
- 页面：表单加「维度」下拉，目标下拉随维度切换（站点/点位/场景三套选项源，点位复用 `listLocations`）；
  切维度清空 matchRef。列表加「维度」列（`StatusBadge`，不新增内联 `Badge tone=`，守住棘轮）、
  「站点」列改「目标」列；导出与搜索同步。
- 方案取舍：不做「一条规则多维组合」——后端一行一维（`dimension+matchRef`），前端造组合形态会与契约背离。

### C 代理端门户标题（拍板 #5：只改标题）
- `lib/nav.ts` 加纯函数 `portalTitleOverride(role, pathname, currentKey, isDefault)`：
  命中该角色门户叶（path 相同，叶带 tab/view 则与当前 key 相等；不带则要求当前是页面默认 tab）
  时返回叶 label，否则 undefined。纯数据函数，进 `nav.test.ts`。
- `lib/use-portal-title.ts` 薄 hook（usePathname + useAuth）包装之；
  `TabHeader` h1 与工作台 `PageTitle` 用 override 兜底原标题。**不改 tab 条、不改列**（拍板边界）。
- 覆盖叶：我的看板(/)、我的设备(/devices)、我的订单(/orders)、我的收益(/finance?tab=records)、
  我的结算(/finance?tab=settlements)、设备报修(/work-orders?view=list)。

## 4. 测试策略

- `pricing.test.ts`：三维种子引用完整性（分维度断言冗余列一致）；LOCATION/SCENE 落库校验
  （点位不存在拒、场景无站点拒、冗余列反查覆盖）；旧形状兼容（只传 siteNo 仍按 SITE 落）。
- `integrity.test.ts`：`pricingDiffs` 的 ref 断言按维度拆分（SITE→sites、LOCATION→locations、SCENE→sceneType 集合）。
- `nav.test.ts`：`portalTitleOverride` 全部门户叶命中 + 非默认 tab 不覆盖 + 非 AGENT 恒 undefined。
- 全量 `npx vitest run` + `npx tsc --noEmit`；浏览器实机验证（dev server）后交人工审核。

## 5. 风险与注意事项

- 并行会话在途改动覆盖同批文件（app/ 16 页 +9.7k 行未提交）：本轮只做外科手术式小改，
  不提交 git，回报中说明重叠；文档回填仅动本 TDD 涉及的行。
- 页面层棘轮（design-tokens.test.ts）：新增徽标一律走 `StatusBadge`，不加内联 `Badge tone=`。
- ⚠️ 后端缺口注记：`PriceDtos.PricingDiff` 尚无 `dimension/matchRef/siteNo` 字段（实体有），
  已在 `lib/api/https/pricing.ts` 更新标注；`USE_MOCK=0` 前需补 DTO。

## 6. 实现任务

- [x] A 订单 keyword 深链（orders page + quick-actions 注释更新）
- [x] B 差异化定价三维（types / mock / 页面 / 测试 / https 标注）
- [x] C 门户标题（nav 纯函数 + hook + TabHeader/PageTitle + 测试）
- [x] 验证：vitest 全绿 + tsc 干净 + 实机自检
- [x] 文档回填：逐菜单待办清单对应行（含绩效报表核实结论）

---
确认记录：2026-08-04 用户定调「前端基于 mock 数据先实现」「完成后展示前端界面（人工审核）」；
拍板 #5（代理端标题）为 2026-07-30 既有确认。
