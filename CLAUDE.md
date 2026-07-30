# ShareHub（powerbank）· 项目约定

多个会话会并行开发本仓库。本文是**每个会话自动载入**的约定，用于避免重复踩坑与规范漂移。

---

## 运营端 UI：先读设计规范，别凭印象改

**唯一真源（只有这两份）**

- [`docs/technical/设计规范-ShareHub运营台.md`](docs/technical/设计规范-ShareHub运营台.md)
  —— token 架构 / 色阶 / 皮肤 / 排版 / 间距 / 形状 / 层次 / 动效 / 层级 / 密度 / 可访问性 / 信息设计
- [`docs/technical/设计规范-布局与外壳.md`](docs/technical/设计规范-布局与外壳.md)
  —— 区域划分 / 表面与分隔 / 尺寸 / 导航状态 / 滚动归属 / RTL

入口索引与**被否决方案清单**见 [`设计规范-README.md`](docs/technical/设计规范-README.md)。
**提出配色/布局方案前先看那份「被否决的方案与原因」表** —— 药丸按钮、Nunito 字体、
简电青主色、灰底白底双轨、三级灰阶梯等都已被否决过，附了原因。

### 硬性约束（`npx vitest run` 会拦）

`ops-web/lib/design-tokens.test.ts` 检查：

1. `components/` 不许用废弃圆角类 —— 只用 `rounded-control/field/card/sheet/chip` **五档**
2. 组件层不许写死颜色（hex / rgb / oklch）—— **颜色一律改 token**
3. 豁免清单不许变长（加豁免必须改数字，在 review 里显形）

### 三个反复踩过的坑

1. **改 `globals.css` 的 token 后必须清 `.next` 重启验证。**
   Turbopack 会缓存类名候选与部分 CSS，出现过三次「源码改了但 served CSS 是旧的」，
   **肉眼完全看不出来**。最可靠的确认方式：`curl` 拉 served CSS 逐字检查 token 是否在。
2. **写类名示例文案要避开完整类名形状。** Tailwind 4 扫描所有源文件的字符串与注释，
   写 `z-[var(--z-*)]` 这种带通配符又像类名的文本会生成非法 CSS、**让整站白屏**。
   用省略号（`--z-…`）或举真实档位名。
3. **别在 dev server 运行时跑 `npm run build`** —— 两者共用 `.next`，会污染 dev 的 chunk。

---

## 并发开发纪律

- **提交只用显式文件路径，绝不用 `git add -A` 或目录级 `add`。**
  本仓库长期有多个会话并行，曾两次误把别人在途的文件提交进自己的 commit。
- 动手前先 `git status --porcelain -- <目标文件>` 确认没有别人的在途改动；
  有则改用 CSS 选择器等非侵入方式，或跳过并在回报里说明。
- 提交信息里如实说明"本次含另一会话的在途改动"（若无法分离）。

---

## 运营端功能状态

- 功能矩阵与实现状态：[`docs/requirements/运营端功能清单.md`](docs/requirements/运营端功能清单.md) §三（与 `ops-web/lib/nav.ts` 菜单 1:1）
- 逐菜单待办：[`docs/technical/前端静态实现-逐菜单待办清单.md`](docs/technical/前端静态实现-逐菜单待办清单.md)
- 权限码唯一真源：[`docs/requirements/功能权限清单.md`](docs/requirements/功能权限清单.md)
  —— **用权限码前先确认它在此表中存在**，曾出现过用了表里不存在的码

## 运营端技术约定

- 纯前端 + mock，API 契约与后端一致（`Result{code,message,data}` / `PageResult{list,total}` /
  camelCase / `xxxNo` / `xxxAt` / 枚举大写下划线）；端点前缀只用既有 6 个
- **契约禁止 `delete*`** —— 软删除语义，用 `archive*` / `unarchive*`
- mock 必须真改 `db`（重开能读回），状态机在 mock 层强制、非法迁移抛错
- 组件总览页 `/dev/ui`：全状态矩阵 + 运行时规范体检，改完组件可在此自查
