# TDD · ops-web 前端结构整理（对齐 ai-shop/ops-web）

> 2026-09-23 · R1～R6 **已全部落地**（进度见文末）。
> 对齐对象：`~/work/ai/ai-shop/ops-web`。两边同源（powerbank 的 ops-web 就是从它派生的），
> 所以本文不讨论"要不要换栈"，只讨论**派生之后 ai-shop 长出来、而我们没跟上的那一层**。

---

## 一、结论先行

目录骨架两边**已经一致**（`lib/api/{contract,contracts,https,mocks}` / `lib/mock/db` /
`lib/types` / `lib/stores` / `components/{ui,layout}`），不需要重排。

差的是**页面与组件之间那一层**：ai-shop 有 `constants` / `hooks` / 组合件 / 双语业务报错 /
护栏脚本，powerbank 没有，于是同一段接线在 29 个页面里各抄了一遍。抄出来的不只是行数，
是**缺口**——下面第二节是实测数据，不是印象。

---

## 二、实测差距（`ops-web/` 当前状态）

### 1. 错误态在整个运营端是不存在的 ⚠️ 最严重

| 事实 | 数字 |
|---|---|
| `<DataTable>` 调用点 | **125** |
| 其中传了 `error` / `onRetry` 的 | **0** |
| `useQuery` 调用点 | **96** |
| 其中处理了 `isError` 的 | **1** |

根因在组件本身：`components/ui/data-table.tsx` 的 props 里**压根没有 `error` / `onRetry`**，
只有 `loading` / `empty`。所以接口 500 的表现是 —— 表格渲染成
**「没有符合条件的数据」**。运营看到的是"筛没了"，于是去改筛选条件，不会报障。

`Pagination` 同理没有 `onSize`，每页条数无法调。

### 2. 常量散落

`size: N` 在页面里出现 **38 次**，取值 6 种：`200`×20、`500`×7、`100`×4、`999`×3、`50`×3、`10`×1。
没有 `PAGE_SIZE`。`money()` 把默认币种 `"AED"` 写死在 `lib/utils.ts` —— 多市场（i18n 已上 ar）必错。

### 3. 没有 hooks 层

| 重复的接线 | 页面数 |
|---|---|
| 手写分页 `useState(1)` | **17 / 29** |
| 手写 tab ↔ URL 同步（`useSearchParams`） | **18 / 29** |

后者在 powerbank 尤其贵：静态导出（`output: "export"`）下每个用 `useSearchParams` 的页面
都要自己包 `<Suspense>`，漏一个构建期才报错。收进 hook 是一次性解决。

### 4. mock 的业务报错是单语中文

`throw new Error("中文")` 在 mock 层 **70 处**，用 `ApiError` 的只有 **8 处**。
界面切到 EN / AR 之后，页面是英文/阿语、错误提示是中文 —— 而错误提示恰恰是最需要看懂的那句。
（`http-client.ts` 真实后端那条路已经做对了：发 `Accept-Language`，用后端本地化 message。
mock 这条路没跟上，于是**两种模式下体验不一致**。）

### 5. lib 根目录已经开始糊

28 个扁平文件里，基础设施（`utils` / `auth` / `notify` / `permissions`）与
业务规则（`operation-rules` / `pricing-rules` / `operation-overview` / `market-time`）混放。
再长几个就找不到东西了。

### 6. 护栏没有并进 `npm run check`

我们有 `scripts/check-backend-parity.py` / `check-perm-parity.py`，但 `check` 只跑
`typecheck + test`，drift 检查要手动跑 `check:drift` —— 等于没有。
ai-shop 的同类护栏是 **vitest 用例**（`openapi-parity.test.ts`、`perm-map.test.ts`），
跑测试就跑到了；另有 `assert-prod-build.mjs` 在生产构建后断言产物。

---

## 三、目标结构

```
ops-web/
  app/                      页面：只管"这页展示什么"，不管接线
  components/
    ui/                     原语 + 组合件（不认业务、不认取数库）
    layout/                 外壳
    <domain>/               业务组合件（operation/ 已有，按域归位）
  lib/
    api/                    契约 / mock / http（结构不动）
    mock/db/                mock 数据与状态机（结构不动）
    types/  stores/  i18n/  （结构不动）
    hooks/       ← 新增：use-paging / use-page-tab / use-can / use-portal-title
    rules/       ← 新增：operation-rules / pricing-rules / operation-overview / market-time
    constants.ts ← 新增
    biz-error.ts ← 新增
    utils.ts  auth.ts  notify.ts  permissions.ts  nav.ts  phase.ts …（基础设施留在根）
```

**分层判据**：`ui/` 不认业务也不认 TanStack Query（换取数库时这层不该改）；
`rules/` 是纯函数、无 React、被页面与 mock **共用**（这条已经在做，只是没有目录名）；
`hooks/` 认 React 但不认具体页面。

---

## 四、分阶段方案

每阶段独立可提交、可回滚。**并发纪律**：ops-web 当前无其他会话在途改动（已 `git status` 确认），
但 R3 触及 29 个页面，必须分批 + 棘轮，不能一把梭。

### R1 · 地基（不改任何页面）

| 产出 | 内容 |
|---|---|
| `lib/constants.ts` | `PAGE_SIZE=10`、`SEARCH_DEBOUNCE_MS`、`MINOR_UNIT`、`DEFAULT_CURRENCY`（从 `utils.money` 里挪出来）、以及各页现在写死的那几个阈值 |
| `lib/biz-error.ts` | `fail(zh, en, ar?)` / `notFound(...)`，抛 `ApiError(400/404)`，**在抛出那一刻**按当前 locale 定稿（与 ai-shop 同构，多一路 ar） |
| `lib/hooks/use-paging.ts` | `{page,setPage,size,setSize}`；`setSize` 内部强制复位页码（换条数还停在第 5 页 = 空表） |
| `lib/hooks/use-page-tab.ts` | tab ↔ URL 同步 + 切 tab 复位页码 + tab 判权（与菜单同口径）+ Suspense 边界 |

风险：低。纯新增，不碰存量。

### R2 · 组件库补件

| 产出 | 说明 |
|---|---|
| `ui/data-table.tsx` **加 `error` / `onRetry`** | 先让组件有能力表达失败 |
| `ui/paged-table.tsx` **新增** | 收一个 `query` 对象进去，`rows/loading/error/onRetry/total` **五项没地方可漏**；`onSize` 由可选改**必填**——编译期拦得住的事不该靠正则扫 |
| `ui/section-header.tsx` **新增** | 页内小节标题。先 grep 一遍现有 `<h3>` 的写法数量，把字阶收口（ai-shop 那边实测长出了五种写法，其中一种用了不存在的类名 `txt-h3`，静默按正文渲染） |
| `ui/help-note.tsx` **新增** | 常驻说明性 `Notice` 收进浮层；**`warning`/`danger` 的不许换**（那是当前状态警示，收起来等于把警告藏了） |
| `ui/config-card.tsx` **新增** | 配置页统一「保存 + 上次修改：时间 · 操作人」页脚 |
| `ui/filter-chip.ts` **新增** | 「生效中的筛选」回显；控件自挂静态 `toChip`，避免 `ui/` 反向依赖上层 |
| `components/operation/summary-card.tsx` | 评估是否上提为 `ui/`（它已被多页使用，且与 `StatCard` 的区别是语义中性而非趋势色） |

风险：中。改 `DataTable` 会碰 125 个调用点的类型 —— 新 prop 全部可选，**不破坏存量**。

### R3 · 页面迁移（分批，棘轮）

按菜单域分批：`operation` → `finance` → `device`/`alarm`/`workorder` → 其余。每批：

1. 分页页换成 `PagedTable` + `usePaging`
2. 多 tab 页换成 `useNavTabs`
3. `size: 200/500/999` 逐个判定：**是真的不分页的配置表，还是懒得接分页**。前者保留并写明理由，后者改真分页

棘轮守卫（写进 `lib/design-tokens.test.ts` 同款的基线测试，数字只许降）：

- 裸 `<DataTable>` 且由 `useQuery` 支撑的调用点数 ≤ 当批迁移后的实测值
- 页面里 `size: <字面量>` 出现次数 ≤ 实测值
- 手写 `useState(1)` 分页页数 ≤ 实测值

### R4 · mock 报错三语化

70 处 `throw new Error(中文)` → `fail(zh, en, ar)`。可脚本批量改，但
**CLAUDE.md 坑 4**：脚本改完必须 grep 验证落点（Python `replace` 锚点不匹配会静默无操作）。

### R5 · 护栏并入 `npm run check`

- 两个 python drift 脚本改写/包一层成 vitest 用例（或在 `check` 里串起来），让"跑测试就跑到"
- 加 `scripts/assert-prod-build.mjs` + `build:prod`：生产构建后断言产物里**没有 mock 痕迹**
  （`NEXT_PUBLIC_USE_MOCK=0` 漏配 = 静默退回 mock，这正是最该被拦的一种）
- `lib/api-mode.ts`（零依赖读构建期常量，根 layout 输出 meta 标记）——
  ai-shop 那边踩过：在根布局 import `lib/api` 会把整个 mock 拉进服务端构建，构建挂在毫不相干的地方

### R6 · 目录分层搬迁

纯 `git mv` + 改 import。放最后，因为它与所有阶段冲突面最大，且收益最小。

---

## 五、明确不做

| 不做 | 理由 |
|---|---|
| 换 UI 库 / 换取数库 | 两边同栈，没有问题要解决 |
| 引入 `@tanstack/react-table` | ai-shop 装了但用得很少；我们的 `DataTable` 是自写列配置，够用 |
| 抄 ai-shop 的业务件（`notify-*`、`recon-streak`、`store-print-sheet` 等） | 是电商的业务，与充电宝无关 |
| 重排 `lib/api` 三件套 | 已经一致 |
| `command-palette` / `notify-bell` / `scroll-hint` | 锦上添花，排在功能之后 |

---

## 六、建议起点

**R1 + R2 的 `DataTable error` 与 `PagedTable` 是唯一有"缺陷性质"的部分**——
其余是整洁度。如果只做一件事，做这一件：现在整个运营端 96 个查询里只有 1 个能告诉用户"取数失败了"，
其余 95 个失败时都在说"没有数据"。


---

## 七、落地记录（2026-09-23）

| 阶段 | 提交 | 结果 |
|---|---|---|
| R1 地基 + R2 组件补件 | `61e6834` | constants / biz-error / hooks；DataTable 加 error/onRetry、PagedTable / SectionHeader / HelpNote / ErrorState；SummaryCard 上提 |
| R3 运营管理 10 页 | `0e09be2` | PagedTable + UNPAGED_SIZE + 错误态 |
| R3 /finance | `3ae92fd` | useNavTabs（含 tab 判权）+ usePaging |
| R3 /cs /pricing /alarms | `a504133` | navTabs 认默认 tab 与 `?view=`；加死菜单项守卫 |
| R3 /reports /agents /orders | `46b93c9` | 七组分页状态各自 usePaging |
| R3 /work-orders /locations /users | `bb4951c` | usePageTab 支持 `?view=` |
| R3 /system /marketing /devices /employees | `550018c` | 13 个多 tab 页迁完；棘轮全面收紧 |
| R4 mock 报错三语化 | `9ad6df4` | 70 处裸 Error → fail/notFound |
| R5 护栏 | `e33b64a` | 漂移检查并入 check；生产产物断言 |
| R6 目录分层 | 本次 | `lib/{rules,hooks}`；`lib/README.md` |

### 棘轮数字（只许降）

| 指标 | 整理前 | 现在 |
|---|:-:|:-:|
| 裸 `DataTable` 未接 `error` | 111 | **24** |
| 页面层 `size:` 数字字面量 | 36 | **0** |
| 手写 `useState(1)` 分页 | 15 | **2**（都不是分页） |
| mock 层裸 `throw new Error` | 70 | **1**（开发期断言，刻意保留） |

### 未尽项

1. **`validate*` 的文案仍是单语中文**（4 处 `throw new ApiError(400, errors[0])` 的上游）。
2. **`marketing:coupon-issues` 没有菜单入口** —— 功能完整但只能从优惠券页内切过去，
   要么补进菜单、要么承认它是页内子视图。
3. 剩余 24 处裸 `DataTable` 是详情抽屉里的小表，多数没有 `useQuery` 支撑，
   逐个确认后再决定是接错误态还是登记豁免。
4. `ConfigCard` 刻意没抄 —— 本仓库只有 1 处「上次修改」页脚，证据不足。
