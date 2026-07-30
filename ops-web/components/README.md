# 组件分层与清单

三层，不要混。判断标准是**依赖方向**：下层不许知道上层的存在。

| 层 | 位置 | 判据 | 可以依赖 |
|---|---|---|---|
| **原语** | `components/ui/` | 无业务语义，换个行业照样能用。只认样式与 DOM | 只依赖 `lib/utils`、`lib/i18n` |
| **组合件** | `components/ui/` | 由原语拼成的通用交互单元（表格/抽屉/表单）。仍无业务语义，但有交互约定 | 原语 |
| **业务件** | `components/`（根） | 认得充电宝的业务词：权限码、工单状态、归档语义 | 原语 + 组合件 + `lib/types`、`lib/permissions` |

`ui/` 里放不下业务词。反过来，业务件不许被 `ui/` 引用 —— 这条一破，`ui/` 就不再可复用。

---

## 原语（primitives）

| 组件 | 文件 | 说明 |
|---|---|---|
| `Button` | `ui/button.tsx` | |
| `Input` / `Select` | `ui/input.tsx` | 裸控件。**筛选下拉请用组合件 `FilterSelect`** |
| `DateInput` | `ui/date-input.tsx` | |
| `Badge` | `ui/badge.tsx` | 导出 `BadgeTone` —— **全站色调联合的唯一真源** |
| `Card` / `CardHeader` / `CardContent` / `CardTitle` | `ui/card.tsx` | |
| `Table` / `THead` / `TBody` / `TR` / `TH` / `TD` | `ui/table.tsx` | 裸表格；列表页用 `DataTable` |
| `Tabs` | `ui/tabs.tsx` | 页内维度切换器（非 tab 导航）。形状（灰槽/全圆/字重）来自 `ui/segmented.ts` |
| `segmentedTrackClass` / `segmentedItemClass` | `ui/segmented.ts` | 分段控件（灰槽+全圆+白色药丸）的 className 拼装，供 `Tabs` 与 `TabHeader` 共用；两者场景不同（内容切换 vs URL 导航）不合并组件，只共享形状 |
| `Progress` | `ui/progress.tsx` | |
| `Notice` | `ui/notice.tsx` | 页内灰底提示条。权限降级用业务件 `ReadOnlyNotice` |
| `StatCard` / `EmptyState` / `Skeleton` / `PageTitle` / `Pagination` | `ui/misc.tsx` | |
| `Tooltip` | `ui/tooltip.tsx` | |
| `Checkbox` / `CheckboxField` | `ui/checkbox.tsx` | 三态（含半选）。`DataTable` 的行选择用它 |
| `RadioGroup` / `RadioGroupItem` / `Radio` | `ui/radio-group.tsx` | 选项 ≤4 且需全部可见时用它，别用下拉 |
| `Switch` / `SwitchField` | `ui/switch.tsx` | **立即生效**的开关；待提交的布尔字段用 `Checkbox` |
| `Textarea` | `ui/textarea.tsx` | 多行输入，与 `Input` 同一套填充与圆角 |
| `Label` | `ui/label.tsx` | 可编辑控件的标签（`required` 出星号）。只读详情行是 `Field` |
| `Separator` | `ui/separator.tsx` | 语义分界线。**不要**拿它给卡片/工具栏描边 |
| `Avatar` / `AvatarLabel` | `ui/avatar.tsx` | 带首字母兜底，图挂了不留空圈 |
| `Popover` / `PopoverTrigger` / `PopoverContent` | `ui/popover.tsx` | 可交互轻浮层。Portal 定位，不会被 `overflow` 裁掉 |
| `DropdownMenu*` / `RowActions` | `ui/dropdown-menu.tsx` | 动作菜单；`RowActions` 是表格操作列的「更多」 |

## 组合件（composites）

| 组件 | 文件 | 说明 |
|---|---|---|
| `DataTable` | `ui/data-table.tsx` | 列表页表格：列配置 + 加载/空态 + 行选择/展开/排序/行样式 |
| `FormDrawer` | `ui/form-drawer.tsx` | 配置化编辑抽屉（`FieldDef[]` → 表单 + 校验 + 分区 + 联动） |
| `Drawer` / `Field` | `ui/drawer.tsx` | 右侧抽屉 + **详情行**（`Field` 全站唯一一份，见下） |
| `Toolbar` | `ui/toolbar.tsx` | 搜索 + 筛选槽 + 导出/新增；选中时切批量操作条 |
| `TabHeader` | `ui/tab-header.tsx` | 页内 tab 条（含分期屏蔽） |
| `ConfirmDialog` / `useConfirm` | `ui/confirm-dialog.tsx` | 二次确认（支持 `requireText` 强确认） |
| `MultiSelect` | `ui/multi-select.tsx` | |
| `StatusBadge` / `StatusMap` / `statusOptions` | `ui/status-badge.tsx` | 「枚举 → 徽标」的渲染与类型。**映射表本身留在页面** |
| `FilterSelect` | `ui/filter-select.tsx` | 列表页筛选下拉；传 `StatusMap` 时选项自动派生 |
| `Timeline` | `ui/timeline.tsx` | 审计时间线（时间 + 操作人 + 前后值 + 说明） |
| `SiteMap` | `ui/site-map.tsx` | |
| `Toaster` | `ui/toaster.tsx` | |

## 业务件（domain）

| 组件 | 文件 | 说明 |
|---|---|---|
| `OrderStatusBadge` / `WoStatusBadge` / `CabinetStatusBadge` / `OnlineBadge` | `status.tsx` | 域内固定枚举的徽标，文案走 i18n |
| `EnabledBadge` | `status.tsx` | 启用/停用（枚举值各页不同，故入参收成 boolean） |
| `useWoTypeLabel` | `status.tsx` | 工单类型文案 |
| `ReadOnlyNotice` | `read-only-notice.tsx` | 权限降级提示，句式统一 |
| `ShowArchivedToggle` / `ArchiveActions` / `ArchivedAt` / `archivedRowClass` / `archiveConfirm` | `archive.tsx` | G1 软删除的页面侧统一件 |
| `Providers` | `providers.tsx` | |
| `layout/*` | `layout/` | 侧栏 / 顶栏 / 二级导航 |

### `components/status.tsx` 的归位

它是**业务件**，不是原语。判据：它 `import type { OrderStatus, WorkOrderStatus } from "@/lib/types"`，
认得订单状态机 —— 这是业务知识。它位于 `components/` 根是对的，不要挪进 `ui/`。
它内部的 `type Tone` 已改为 `BadgeTone` 的别名，色调联合不再有第二份定义。

---

## 用法约定（第二阶段铺开时照这个来）

**props 命名沿用 `DataTable` / `FormDrawer` 的既有习惯**：
- 数据入参：`rows` / `columns` / `items` / `options`
- 空态文案：`empty`，且要写清「**为什么**空、下一步做什么」，不要只写「暂无数据」
- 受控值：`value` + `onChange`，回调直接给值不给 event
- 样式逃生口：`className`（`cn` 走 tailwind-merge，可覆盖内置类）

**颜色一律用 token**（`--*-tint` / `--*-ink` / 语义色），不要写死 hex。见 `app/globals.css` 顶部注释。

### `Field` 只此一份

原先三处定义（`ui/drawer.tsx`、`ui/form-drawer.tsx`、`app/devices/detail/page.tsx`）已收敛：

- `ui/drawer.tsx` 的 `Field` 是唯一的**详情行**，默认 `mb-4`；
  放进 grid / flex 由容器给间距时传 `className="mb-0"`。
- `ui/form-drawer.tsx` 的 `FieldRow` **不是重复定义**，是表单行（必填星号 / 字数计数 /
  错误态 / 控件分发）。两者只有外框间距长得像，合并会把表单关注点塞进只读展示件 —— 保持分开。

### 四种浮层怎么选

选错就会出现第二份重复实现，这条按**内容性质**判，不按「大小」判：

| 内容 | 用 | 依据 |
|---|---|---|
| 一句纯说明，hover 即出，不可点 | `Tooltip` | 无焦点、无交互 |
| 一列**动作**（编辑/停用/归档） | `DropdownMenu` / `RowActions` | 方向键 + typeahead 由 Radix 保证 |
| 任意可交互内容（筛选面板、迷你表单、二维码） | `Popover` | 顺手看一眼 / 改一下，**不遮挡列表上下文** |
| 成体系的一件事，有标题与底部动作条 | `Drawer` / `ConfirmDialog` | 进去做一件事 |

一句话判据：**Popover 是「顺手改一下」，Drawer 是「进去做一件事」。**

### 状态映射表放哪

映射表是**业务语义**（哪个状态叫什么、该是什么色），留在使用它的页面里：

```tsx
const RES_STATUS: StatusMap<Reservation["status"]> = {
  PENDING:   { label: "待履约", tone: "warning" },
  FULFILLED: { label: "已履约", tone: "success" },
};

<StatusBadge map={RES_STATUS} value={r.status} />
<FilterSelect value={s} onChange={setS} allLabel="全部状态" options={RES_STATUS} />
```

⚠️ 映射表的**键序即筛选下拉的选项顺序**。改键序 = 改 UI，别随手排序。
