# TDD · 运营管理菜单（前端先行）

> 状态：**已确认**（2026-09-22，确认记录见 §5）
> 需求来源：[运营管理-功能清单](../requirements/features/运营管理-功能清单.md)（下称「清单」）
> 范围：只做 ops-web 前端。后端没有的接口，前端先按清单里约定的接口写好 http 实现，同时用 mock 跑通；后端补齐后直接切换，页面不改。

---

## 0. 开工前必须先处理：约 1 万行未提交的在途改动

> **已处理（2026-09-22，方案 A）**：按主题分 3 个提交入库——`3b8edbc` 工具脚本、`c453e1e` ops-web 静态收尾（118 文件）、`5425717` 设计文档（48 文件）。提交前对工作区验证：tsc 0 错误、vitest 778/778、`next build` 通过。下文保留原始分析备查。

`ops-web` 工作区里有 **97 个文件、约 +10,000 / −1,300 行未提交**，另有十几个未跟踪文件。修改时间集中在 **2026-07-30 ～ 08-04**，已经放了七周多。

这批改动覆盖了本方案**必须改的共享文件**：

| 本方案要改的文件 | 在途状态 |
|---|---|
| `lib/nav.ts`（加一级菜单） | 已修改 +25 行 |
| `lib/permissions.ts`（加权限码） | 已修改 |
| `lib/api/contract.test.ts`（登记新接口方法） | 已修改 |
| `lib/mock/db/index.ts`（导出新 mock） | 已修改 |
| `lib/design-tokens.test.ts`（棘轮基线） | 已修改 |

还有一个隐患：**线上部署是从 HEAD 构建的，不包含这批改动**。所以线上运营端和本地 dev 看到的并不是同一个版本。

按 CLAUDE.md 的并发纪律，我不能把这些改动当成自己的去提交，也不能在它们上面直接改。三条路：

| 方案 | 做法 | 代价 |
|---|---|---|
| **A（推荐）** | 先由改动的主人（或你确认后由我）把这批改动**按主题拆开提交**，跑通 `vitest` 和 `next build`；然后我在干净的 HEAD 上开发 | 先花 0.5～1 天梳理；之后没有冲突 |
| B | 我在独立的 git worktree（基于 HEAD）里开发，共享文件只做最小追加；完成后再和在途改动合并 | 五个共享文件大概率冲突；在途改动如果后来提交，还要再合一次 |
| C | 直接在当前工作区开发 | **不可行**：我的提交会混进别人的改动，CLAUDE.md 明确禁止 |

**下文计划按方案 A 排期。**

---

## 1. 总体方案

### 1.1 目录结构

**全部新建文件，不修改旧页面**（`app/system`、`app/pricing`、`app/locations`、`app/marketing` 都在在途改动里，而且在第三步下线前要保持原样可用）。

```
app/operation/
  page.tsx                    重定向到 /operation/overview
  overview/page.tsx           S1 站点概览
  sites/page.tsx              S2 站点管理
  fee-plans/page.tsx          S3 收费方案
  fee-adjustments/page.tsx    S4 预约调价
  site-sharing/page.tsx       S5 站点分成
  payee-sharing/page.tsx      S6 分成方分成
  app-versions/page.tsx       B1 应用版本
  banks/page.tsx              B3 银行管理
  problems/page.tsx           B4 问题管理
  notices/page.tsx            N1 公告管理

components/operation/         本菜单专用的业务组件（详情抽屉、各类编辑器）
lib/types/operation.ts        新增类型（调价单、分成视图、概览、统计）
lib/api/contracts/operation.ts
lib/api/mocks/operation.ts
lib/api/https/operation.ts
lib/mock/db/operation.ts      mock 数据与业务规则（真改 db，状态机在这里强制）
lib/pricing-summary.ts        计费摘要生成（纯函数）
lib/market-time.ts            市场时区的显示与换算（纯函数）
```

**B1 / B3 / B4 / N1 为什么不从旧页面抽组件，而是重写**：这四块现在内联在 1653 行的 `app/system/page.tsx` 和 `app/marketing/page.tsx` 里，而这两个文件都在在途改动中。抽组件就得改它们。重写一份的代价是**第一、二步期间有两份相近的代码**；第三步删掉旧页面后，重复自然消失。

### 1.2 数据层

- **已有的接口方法直接复用，不另起新名字**：`listSites` / `saveSite` / `archiveSite` / `savePoint` / `listPricePlans` / `savePricePlan` / `listPricingSchedules` / `savePricingSchedule` / `listAppVersions` / `saveAppVersion` / `rollbackAppVersion` / `listBanks` / `saveBank` / `listProblems` / `saveProblem` / `listNotices` / `saveNotice` 及各自的归档与取消归档
- **只为新能力新增方法**，统一放进新的 `operation` 域：

| 方法 | 用于 | 后端现状 |
|---|---|---|
| `getOperationOverview` | S1 | 未实现 |
| `pauseSite` / `resumeSite` / `getSiteStats` | S2 | 未实现 |
| `getPricePlanDetail` / `simulatePricePlan` / `setPricePlanStatus` / `listPlanSites` | S3 | 部分实现（后端有 `PricePlanDetail` 结构，接口待确认） |
| `listPriceAdjustments` / `savePriceAdjustment` / `cancelPriceAdjustment` / `revertPriceAdjustment` / `retryPriceAdjustment` | S4 | 未实现（要新表） |
| `listSiteSharing` / `saveSiteSharing` / `listSiteSharingHistory` / `listPayeeSharing` | S5 / S6 | 未实现，**契约取决于 D2** |
| `publishNotice` / `offlineNotice` | N1 | 待确认（现在发布和下线是否走 `saveNotice` 改状态） |

- **mock 必须真改 db**（项目约定）：刷新页面后能读回；非法状态迁移直接抛错
- **预约调价的「到点执行」在 mock 里怎么模拟**：每次读调价列表或方案时，先按当前时间把到期的调价执行掉（惰性执行）。时间通过参数注入，单测可以拨时钟验证。这样 mock 里不需要真的起定时器
- `contract.test.ts` 的锚数组同步登记新方法（该文件的「mock 与 http 方法集合一致」三道检查会兜住漏实现）

### 1.3 线上怎么上：后端没好的页面不能点

线上构建是 `NEXT_PUBLIC_USE_MOCK=0`，走真实后端。后端还没实现的接口，页面一调就报错。处理办法：

- 菜单项用现有的 `soon` 字段（灰显、不可点）。但**只在真实后端模式下灰显**：本地 mock 模式照常可点，方便开发和演示
- 新增一个集中的开关表 `BACKEND_READY`，逐页登记后端是否就绪；后端上线一项，改一行即可放开
- 页面内的**局部功能**同理：例如站点管理的列表和表单后端已有，可以上线；但「暂停营业」「统计」依赖新接口，在后端就绪前不显示这两个按钮，而不是整页灰掉

### 1.4 菜单与权限

- `nav.ts` 新增一级菜单 `operation`（运营管理），三个分组：场站管理 / 基础管理 / 公告管理。**不挂阶段标记**（清单 D7）
- **一级菜单可见性（清单 D1）**：现在的规则是「角色有该模块前缀的权限就显示一级菜单」，而运营管理横跨五个模块。给菜单配置加 `modules?: string[]`，规则改为：**只要有一个子菜单可见，一级菜单就显示**。改动集中在 `nav.ts` 的可见性函数，并补单测
- 权限码：子菜单沿用资源权限码。新增三组，并在 `permissions.ts` 的角色映射里配好：
  - `location:overview:read`：ADMIN / OPS / BD / FINANCE
  - `pricing:adjustment:read / :create / :cancel`：ADMIN / FINANCE
  - `marketing:notice:read / :update`：后端早就在用，前端和功能权限清单都缺，一并补上
- **功能权限清单文档同步登记这些码**（CLAUDE.md：用权限码前先确认它在清单里）
- 菜单标签三语齐全（`nav-labels`，已有「全菜单三语覆盖」单测兜底）

### 1.5 需要新做的通用组件

现有 `FormDrawer` 只支持单值字段（文本、数字、下拉、开关、日期、多选），**不支持日期时间、可增删的多行、三语分栏、跨字段校验**。以下组件先做，并加到 `/dev/ui` 组件总览页：

| 组件 | 用在哪 | 要点 |
|---|---|---|
| `DateTimeInput` | S4 生效 / 恢复时间、N1 生效期 | 精确到分钟；旁边显示市场时区；配合 `lib/market-time.ts` 存 UTC |
| `RowsEditor<T>` | S3 计费项和阶梯、S5 分成方 | 行可增删、可拖动排序；逐行校验 + 整体校验（例如比例合计 ≤ 100%、阶梯首尾相接） |
| `I18nTextFields` | B1、B4、N1 以及站点阿语名 | 中 / 英 / 阿三栏；中文必填；阿语栏按 RTL 输入 |
| `TimeRangesInput` | S2 营业时间 | 多段时间 + 「24 小时」开关；校验各段不重叠 |
| `PeriodPicker` | S3 时段倍率 | 时间范围 + 星期多选 + 节假日。**在现有 mock 的结构化时段（`formatPeriod(spec)`）基础上做**，不另起一套语法 |
| `DiffTable` | S4 变更对比、审计记录 | 字段 / 旧值 / 新值，变化的字段高亮 |
| `AttentionList` | S1 待关注站点 | 严重程度、说明、快捷操作 |

图表沿用 recharts；指标卡沿用 `StatCard`；列表沿用 `DataTable`、`Toolbar`、`StatusBadge`、`ShowArchivedToggle`、`ArchiveActions`、`ReadOnlyNotice`、`exportCsv`。

### 1.6 页面的共同写法

- 筛选条件、页签和详情都写进 URL（`?tab=`、`?no=`），刷新和分享不丢。静态导出要求读 URL 参数的组件包在 `Suspense` 里（现有页面已是这个写法）
- **不写死颜色、圆角只用五档**（`design-tokens.test.ts` 会拦）；类型阶 `.txt-*` 不和 `font-*` / `leading-*` 同时用（CLAUDE.md）
- 棘轮基线只许下降：状态一律 `<StatusBadge>`，无写权限一律 `<ReadOnlyNotice>`，空态写清原因和下一步
- 金额统一走 `money()`，比率统一格式化为百分比

---

## 2. 逐页开发要点

每页的字段、规则和验收以清单为准，这里只写**前端实现上的关键点和风险**。

| 页面 | 前端关键点 | 风险 / 依赖 |
|---|---|---|
| **S1 站点概览** | 一个接口取回全部模块；mock 里把 6 条待关注规则写成纯函数并单测；指标卡跳转带筛选参数 | 后端未实现，线上先灰显 |
| **S2 站点管理** | 列表、表单、归档已有后端，**可以先上线**；详情抽屉 8 个页签按需懒加载；点位页签复用 `savePoint`；「所属代理」只读并链接到划拨；暂停 / 恢复 / 统计在后端就绪前不显示 | 线上表缺 `point_count / cabinet_count` 两列，前端不依赖这两个字段，改用接口返回的聚合值 |
| **S3 收费方案** | **分两期**：第一期按现有的单计费项模型（免费时长、计费单位、单价、日封顶、买断价）+ 适用范围 + 时段倍率 + 计费摘要 + 试算；**第二期**再做多计费项和阶梯价 | 前端现有 `PricePlan` 类型是单计费项的扁平结构，而后端库里有计费项、阶梯、范围三张子表。多计费项的读写接口要和后端对齐后才能做，这是第二期的原因 |
| **S4 预约调价** | 状态机在 mock 里强制；惰性执行 + 可注入时钟；表单以方案当前值为初始值，只提交改动的字段；底部实时试算（复用 S3 试算） | 后端要新表 + 定时任务，线上先灰显；时区依赖 D5 |
| **S5 / S6 分成** | 两页共用一份 mock 数据，只是聚合方向不同；比例合计实时计算 | **契约取决于 D2**。D2 未定时只做页面骨架和 mock，不写 http 实现，避免返工 |
| **B1 应用版本** | 按平台分页签；发布、回滚二次确认；灰度进度条复用现有 `Progress` | 后端已有，**可以先上线** |
| **B3 银行管理** | 选国家后预填 IBAN 长度；有引用的只能停用 | 后端已有，可以先上线。「引用数」这一列后端可能没有，缺的话先不显示 |
| **B4 问题管理** | 按分类分组；上移 / 下移改排序号；C 端样式预览 | 后端已有，可以先上线。「自动开工单」后端是 Stub，页面上加说明；「近 30 日被选次数」后端可能没有，缺的话先不显示 |
| **N1 公告管理** | 派生状态（待生效 / 已过期）在前端计算；置顶上限 3 条同时在 mock 和表单里校验；C 端公告条预览 | 后端已有，可以先上线。确认后端是否已经强制置顶上限，没有的话提给后端补 |

---

## 3. 开发计划

| 阶段 | 内容 | 产出 | 估时 | 线上可用？ |
|---|---|---|---|---|
| **F0 前置** | 处理在途改动（§0 方案 A） | 干净的 HEAD；vitest 和 build 全绿 | 0.5～1 天 | — |
| **F1 地基** | `operation` 数据域骨架（契约、mock、http、类型）；菜单、权限、三语；多模块可见性；`BACKEND_READY` 开关；`market-time`；10 个路由占位页（带解释性空态） | 菜单可见，每页能打开 | 1 天 | 菜单上线，未就绪页面灰显 |
| **F2 基础管理 + 公告** | 组件：`I18nTextFields`、`DateTimeInput`；页面：B1、B3、B4、N1 | 4 个完整页面 | 2.5 天 | **可以上线**（后端都已有） |
| **F3 站点** | 组件：`TimeRangesInput`、`AttentionList`；页面：S2（列表、表单、详情 8 页签）、S1 | 2 个页面 | 3 天 | S2 主体可上线；S1 和 S2 的暂停 / 统计等后端 |
| **F4 计费** | 组件：`RowsEditor`、`PeriodPicker`、`DiffTable`；`pricing-summary`；页面：S3 第一期、S4 | 2 个页面 | 3.5 天 | S3 列表和编辑可上线；试算、S4 等后端 |
| **F5 分成** | S5、S6（前提：D2 已定） | 2 个页面 | 2 天 | 等后端和 D2 |
| **F6 收尾** | `/dev/ui` 补新组件的状态矩阵；每页在浏览器里逐项核对验收点；更新实现状态总表、功能清单、功能权限清单 | 文档与自查 | 1 天 | — |

**合计约 13.5～14 天**（不含 S3 第二期的多计费项和阶梯价，约 +2 天，要等后端接口）。

**每个阶段结束都做同一套检查**：`npx vitest run` 全绿 → `next build` 通过 → 本地 mock 模式浏览器逐页核对 → 部署到线上（真实后端模式）再核对一遍已就绪的页面。

---

### 3.1 进度

| 阶段 | 状态 | 提交 |
|---|---|---|
| F0 | ✅ 在途改动分 3 个提交入库 | `3b8edbc` `c453e1e` `5425717` |
| F1 | ✅ 菜单、权限、后端就绪开关、市场时区、10 页占位 | `caf7b63` |
| F2 | ✅ 应用版本 / 银行管理 / 问题管理 / 公告管理 | `68e4608` |
| F3 | ✅ 站点管理（8 页签详情）/ 站点概览 + operation 数据域 | `2d60905` |
| F4+F5 | ✅ 收费方案 / 预约调价 / 站点分成 / 分成方分成——10 页全部落地 | `201804c` |
| 部署 | ✅ 2026-09-23 上线 powerbank.ichain.top（前端 `201804c`） | — |

**F2 实现说明**
- 规则集中在 `lib/operation-rules.ts`，**表单提交前与 mock 写入调用同一份**：版本号 / 构建号递增、同平台只一个灰度、强更必须全量、已发布只能调灰度；银行代码唯一且不可改；公告状态机与置顶上限 3 条；问题同分类内换序
- mock 的 `saveAppVersion` / `rollbackAppVersion` / `saveBank` / `saveNotice` 已接入这些规则（旧页面读写同一份 mock，也一并受约束）
- `FormDrawer` 新增 `datetime` 字段类型（只新增，不影响已有类型）；时区换算在页面用 `lib/market-time`
- 新增 `components/operation/lang-preview.tsx`（三语 + RTL 预览，未填语言按 C 端回退中文并标注）、`summary-card.tsx`（中性摘要卡；`StatCard` 副文案是涨跌色，不适合写说明）

### 3.2 后端待办（F2 核对后端代码时发现）

这四页的后端接口都在，**页面可以上线**；但下列规则和字段后端缺失，前端这一层是目前唯一的防线，绕过前端直接调接口就能写入不合规数据：

| # | 缺口 | 影响 |
|---|---|---|
| BE-1 | `AppVersionServiceImpl` 未校验：版本号 / 构建号递增、同平台只一个灰度、强更必须全量、已发布只能调灰度、状态机 | 可能出现两个版本同时灰度、已发布版本被改内容 |
| BE-2 | `NoticeServiceImpl` 未校验：状态机、置顶上限、结束晚于开始 | 置顶可超过 3 条 |
| BE-3 | `BankServiceImpl` 未校验：代码格式、国家 / 币种格式、IBAN 长度范围 | — |
| BE-4 | 银行返回结构 `MdDtos.BankEntry` 缺 `bankNameAr`、`archivedAt`（表里有列） | 银行名称只能中英两语；归档状态后端列表无法区分 |
| BE-5 | 公告发布时不记录 `publishedBy` | 发布人列为空 |
| BE-6 | `ProblemActionResolver` 为 Stub | 「自动开工单」处置不生效（页面已提示） |
| BE-7 | c-app 未调用 `GET /mp/app/version` | 发布与强更不会弹给用户（页面已提示） |
| BE-8 | 站点概览聚合接口 `GET /api/ops/operation/overview` 未实现 | 概览页线上灰显；口径见 `lib/operation-overview`，后端实现时照此对齐 |
| BE-9 | 单站统计 `GET /api/ops/sites/{no}/stats` 未实现 | 站点详情「统计」页签线上不可用 |
| BE-10 | 站点暂停 / 恢复 `POST /api/ops/sites/{no}/pause\|resume` 未实现 | 线上不渲染这两个按钮；C 端「暂停站点不可借、可归还」也需后端配合 |
| BE-11 | `loc_site` 的 `point_count` / `cabinet_count` 是冗余计数，与真实关系不一致 | 前端已改为实时聚合；后端若要保留这两列，需在点位/机柜变更时同步维护，否则建议去掉 |
| BE-12 | 预约调价：新表 `price_adjustment` + 定时任务 + 5 个端点（`/api/trade/price-adjustments*`）未实现 | 线上整页灰显；执行语义见 `lib/mock/db/adjust.ts`（幂等、补执行、人工改过则不覆盖），后端照此实现 |
| BE-13 | 分成两视角接口未实现，且站点级比例口径未定（清单 D2） | 线上两页灰显；前端已给出只读聚合的形状 `lib/mock/db/sharing.ts` |
| BE-14 | 收费方案缺「启用/停用」与「命中站点」端点 | 页面对应按钮在真实后端下不渲染 |

## 4. 测试

| 类型 | 覆盖 |
|---|---|
| 纯函数单测 | 计费摘要生成；阶梯连续性校验；分成比例合计；时段表达式生成与回读；市场时区换算；公告派生状态；6 条待关注规则 |
| mock 业务单测 | 预约调价状态机（每条合法与非法迁移）；惰性执行（拨时钟验证到点生效、到点恢复、重复读不重复执行）；恢复时方案被人工改过则转失败；置顶上限；站点暂停后借出被拒 |
| 契约单测 | 新方法登记进锚数组；mock 与 http 方法集合一致 |
| 菜单单测 | 各角色下运营管理一级菜单是否可见；多模块规则；三语覆盖；`BACKEND_READY` 为否时灰显 |
| 设计规范 | `design-tokens.test.ts` 通过，棘轮数字不上升 |
| 浏览器核对 | 每页按清单的验收条目逐项核对，附截图；阿语界面检查 RTL |

---

## 5. 需要你拍板的事

| # | 事项 | 建议 |
|---|---|---|
| **P0** | 在途的约 1 万行改动怎么处理（§0） | 方案 A：先梳理提交，再开发。需要你确认这批改动是谁的、要不要保留 |
| P1 | 分成两页要不要等 D2 | 要等。D2 没定就只做骨架，不写 http 实现 |
| P2 | 收费方案要不要拆两期 | 拆。第一期用现有单计费项模型，多计费项和阶梯价等后端接口 |
| P3 | 后端没就绪的页面，线上是灰显还是完全隐藏 | 灰显：让运营知道功能在路上。本地 mock 模式照常可用 |
| P4 | 旧菜单什么时候隐藏 | 新菜单对应页面在线上跑满一周、没有问题反馈后，再隐藏旧入口 |

**确认记录（2026-09-22，用户）**：P0 按方案 A 先提交在途改动（已完成，见 §0）；P1～P4 按建议执行。


---

## 后端补齐（2026-09-23，提交 4e530b0 / f26f0cd）

前端十页早已完成，但菜单里四页灰着、三个按钮不渲染 —— `lib/backend-ready.ts`
如实登记着「后端未实现」。本次把那一批补上，**契约缺口 12 → 0**（严格覆盖率 93% → 97%）。

| 能力 | 端点 | 落点 |
|---|---|---|
| 站点概览 | `GET /api/ops/operation/overview` | `sharehub-app/operation`（跨域读模型，同 report） |
| 单站统计 | `GET /api/ops/sites/{no}/stats` | 同上 |
| 暂停 / 恢复营业 | `POST /api/ops/sites/{no}/pause\|resume` | `LocService`（V40 加 `pause_reason`） |
| 预约调价 ×5 | `/api/trade/price-adjustments…` | `svc-core/trade/price`（V40 建表） |
| 分成两视角 ×3 | `/api/trade/{site-sharing,…/stats,payee-sharing}` | `sharehub-app/operation/SharingQueryService` |

### 几处刻意的取舍

- **跨域聚合放 `sharehub-app`**：概览要同时看站点(platform)/机柜订单(core)/分成(finance)，
  放任一域都名不正。沿用 `ReportMappers` 的约定：一条聚合 SQL 出事实。
  待关注的七条规则仍在 Java 里判 —— 拼成一条 SQL 之后没人能改，而站点是百级规模。
- **订单按机柜连站点**，不读 `ord_order.site_no`：那列只在数据范围过滤时回填，历史单大量为空。
- **调价恢复靠快照，且恢复前先核对**：方案若在调价期间被人手工改过就置 FAILED 不恢复。
  悄悄覆盖别人的改动比不恢复更难查 —— 没有人会知道发生过。
- **不加 `@Scheduled`**：沿用仓库既有判断（多副本并发要分布式锁），改用系统 cron 调
  `/internal/.../tick`。该端点**只接受回环调用** —— 它会改价格，不能因为
  「nginx 没暴露 /internal」就当它安全。

### 途中发现的既有缺陷

**计价引擎选不中运营新建的方案**：`PriceResolver` 只认 `status='ENABLED'`，
而新建时写的是 `'ACTIVE'`（DDL 默认与前端契约也都是 ACTIVE/DISABLED）。
后果是运营新建一个方案、界面显示「启用」，订单却一律按兜底价计费且无任何报错。
今天没出事只因种子里那行恰好是 ENABLED。V41 归一 + 引擎改判。

另：`fee-plans.status`（方案启停）本就不需要新端点 —— 页面做的是
`savePricePlan({...p, status})`，保存接口一直吃 status；那个标志写着 false
纯属登记时想当然，于是一个能用的按钮被藏了。`fee-plans.sites` 登记了却无人使用，已删。

### 验证

后端 145 tests（新增 `OperationMenuTest` 7 条）/ 前端 869 tests / tsc 全绿；
本地起真实后端 + `NEXT_PUBLIC_USE_MOCK=0` 逐页实测：概览（20 站点 / 48 机柜 / 在线率 81.3%）、
预约调价（新建 → 打开列表自动生效、快照原值 → 提前恢复 → 单价回到原值）、
站点分成（20 站 / 已配置 2）、分成方分成（3 个分成方，近 30 日金额来自 M1 生成的真实分润）。


### 上线（2026-09-23）

后端 `fe4e6bd` / 前端 `d169d84`，迁移到 V41，cron 装在 `/etc/cron.d/powerbank-price-adjustment`。
上线前 mysqldump 备份（`pb_core-20260923-1051.sql`）。

V41 在生产上精确改了 **1 行**（唯一的收费方案 `ENABLED → ACTIVE`）。
⚠️ **迁移与代码是配对的**：若迁移已跑而需回滚旧包，旧包仍找 `ENABLED`，会取不到方案、
订单落到兜底价。回滚时要一并把该行改回 `ENABLED`。

线上实测：概览（规模/经营/趋势/排行/待关注三条规则全部命中）、预约调价
（新建 → 打开列表自动生效、快照原值 3.0 → 方案改为 7.5 → 提前恢复 → 回到 3.0）、
站点分成、分成方分成。

### 上线时发现并修掉的一个通病

会话过期后打开站点概览，**标签页隐藏时整页只剩一个标题** —— 没有骨架、没有错误态。
根因是 React Query retryer 的 `canContinue = focusManager.isFocused() && …`：
隐藏时失败重试被挂起，`status` 停在 pending、`isLoading` false、`error` null，
页面上「加载中 / 失败 / 有数据」三个分支一个都不成立。

这与之前在 `PagedTable` 里处理过的 `fetchStatus==="paused"` 是同一条机制，
但那次只覆盖了分页表格，概览这类自己写三分支的页面照样白板。
改在源头：`focusManager.setFocused(true)`（`d169d84`）。


---

## 演示数据上线（2026-09-23，后端 `4acfede`）

生产此前没有任何业务数据，概览/看板全是 0。本次灌入演示数据：
**12 站点 / 30 点位 / 48 机柜 / 120 订单（近 7 日 80 单有金额）/ 9 代理 / 18 合同**，
概览的经营指标、趋势、排行、场景分布、待关注全部有值。

操作：`SHAREHUB_SEED_ENABLED` 临时置 true → 重启 → 灌完**立刻改回 false**
（避免将来某张表被清空时意外重灌）。灌种前已 mysqldump 备份。

### ⚠️ 第一次尝试把生产打挂了

灌种失败 → 应用起不来 → 停机约 90 秒（已回滚恢复）。根因：
**从来没有人在干净库上灌过种** —— 本机库的数据早于那些迁移、被历史回填覆盖过，
所以本地永远正常。

教训是把本机库**清空到与生产同样的起点**再复现，而不是拿生产试错。
一轮轮跑下来修了六处（详见提交 `4acfede`），其中两处值得记住：

- **`SeedData.iso()` 产出的 `…T…Z` 塞不进 DATE/DATETIME 列**。全项目有 90 处
  「String 字段 ↔ 日期列」，运行时代码写入前都会先格式化，唯独种子没有。
- **数据范围锚点没人回填**：V9 那次 UPDATE 针对的是「当时库里已有的数据」，
  而种子在所有迁移跑完之后才插入。后果是代理商登录后什么都看不到，且不报错。
  新增 `ScopeAnchorSeeder`，用与 V9 逐字相同的四条 JOIN UPDATE 回填。

### 顺带查干净的一类缺陷

「实体有字段、迁移没建列」今天撞了四次。做了全量比对（129 张表），修掉最后三处（V42），
并把 `entity-column-diff.py` 的比对对象从**开发库**改成**迁移脚本** ——
开发库里那些列是历史上手工 ALTER 加的，它跑在迁移前面，所以一直报「缺列 0 张」而生产照崩。
