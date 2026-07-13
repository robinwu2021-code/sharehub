# 前端技术栈（统一）— tech-stack-frontend.md

> 状态：草稿（待确认）· 创建 2026-07-11 · 修订：MENA/App 优先 + ai-boss 栈
> 关联：[architecture.md](./architecture.md) · [ADR-008 前端技术栈](./ADR/ADR-008-前端技术栈.md) · [ADR-009 市场与区域](./ADR/ADR-009-市场区域与合规.md)
> 基线：运营端对齐 `ai-boss/ops-web`（Next16+React19+Tailwind4+shadcn）· C端 uni-app（App 优先）
> 核心：**上层各端独立，下层共享地基**（TS 契约 + 请求层 + Tailwind 兼容原子类 + 设计 token）。

---

## 一、全端总览

| 端 | 形态 | 框架 | UI | 状态 | 优先级 |
|----|------|------|----|------|--------|
| **运营端 Web** | 平台超管 + 租户后台 | **Next.js 16 + React 19 + Tailwind 4** | **shadcn/ui + TanStack Table** | Zustand + React Query | P0 |
| **C 端** | **App → 小程序** | **uni-app**（Vue3 + TS）| wot-design-uni | Pinia | **App P0** / 小程序 P1 |
| **运维 App** | App | uni-app（App 端）| wot-design-uni | Pinia | P0 |
| **商户/代理端** | 小程序/H5 | uni-app 换角色 | wot-design-uni | Pinia | P1 |

> 样式：运营端 Tailwind 4（shadcn 依赖）；C端 UnoCSS + `preset-wind`（Tailwind 兼容类名）——原子类心智跨端一致。

---

## 二、运营端 Web（ai-boss 栈）

采用 ai-boss `ops-web` 同栈，复用其组件与设计系统。

| 关注点 | 选型 | 生态/包 |
|--------|------|---------|
| 框架 | **Next.js 16 + React 19 + TypeScript** | 对齐 ai-boss ops-web |
| **UI 组件** | **shadcn/ui**（Radix + cva + clsx + tailwind-merge + lucide）| 复制进项目、完全可改；ai-boss 已有基线 |
| **数据表格** | **TanStack Table** | shadcn 无开箱企业表格，配 TanStack 自装排序/筛选/分页/虚拟滚动 |
| 样式 | **Tailwind CSS 4** | ai-boss 同款；沿用其 lint（禁任意字号/禁裸控件）保持设计规整 |
| 数据请求 | **TanStack Query** + fetch/ky 封装 | 封装 `Result<T>` 拆包 + `AuthHeaders` + `ErrorCode` |
| 全局状态 | **Zustand** | |
| 图表 | ECharts / Recharts | 经营看板/设备点位分析/大屏 |
| 地图 | 高德（中国）/ **Google Maps**（中东）| 按 region 切换 |
| 表格导出 | xlsx / exceljs | |
| 权限 | 路由守卫 + 按钮级（读权限码）| 对接 platform RBAC |
| i18n / RTL | **next-intl / i18next**，**ar/en 双语 + RTL**（中东）| 见 [ADR-009](./ADR/ADR-009-市场区域与合规.md) |
| 工程 | pnpm + ESLint + Prettier + ai-boss lint 规则 | |

**两级后台同工程**：平台超管 / 租户后台共用代码，按角色动态菜单 + 数据范围。

---

## 三、C 端（uni-app，App 优先）

一套代码 → **App（P0）** + 微信/支付宝小程序（P1，服务海外中国用户）+ H5，条件编译隔离差异。

| 关注点 | 选型 | 生态/包 |
|--------|------|---------|
| 框架 | **uni-app**（Vue3 + TS + Vite）| App 端 uni-app x（UTS 原生渲染，性能最佳）/ 常规 nvue |
| **UI 组件** | **wot-design-uni** | Vue3+TS 高质量；备选 uView-plus |
| 样式 | **UnoCSS + preset-wind** | `unocss-applet`；Tailwind 兼容类名，与运营端心智一致 |
| 状态 | **Pinia** | |
| 请求 | `uni.request` 封装 | `Result<T>` 拆包、登录态、扫码归属租户 |
| 登录 | 条件编译 `#ifdef APP-PLUS/MP-WEIXIN` | **App：手机号/Apple/Google 登录**（中东主）；小程序：微信 openid（海外中国用户）|
| 支付 | 条件编译 | **App：本地 PSP SDK / Apple Pay / Google Pay**；小程序：微信 JSAPI → trade `/mp/trade/pay`（见 [ADR-005](./ADR/ADR-005-C端载体与MENA支付.md)）|
| 免押 | **银行卡预授权冻结**（通用）| App/卡；微信支付分仅小程序中国用户 |
| 扫码 | `uni.scanCode` | 扫柜机码借出 |
| 地图 | uni `<map>` + Google Maps(中东)/高德 | 附近网点/导航 |
| 推送 | App UniPush 2.0 / APNs+FCM；小程序订阅消息 | 中东走 FCM/APNs |
| i18n/RTL | uni i18n，**ar/en + RTL** | |

**载体推进**：**App P0 先行**（App Store/Google Play，中东主战场）→ 微信小程序 P1（海外中国用户，加 `MP-WEIXIN` 分支）→ 支付宝小程序按需。

---

## 四、运维 App / 商户代理端

- 运维 App：uni-app App 端，复用 C端基座；工单/接单/处理、扫码定位、导航、拍照上报、离线缓冲、Push。
- 商户/代理端（P1）：uni-app 小程序/H5 换角色渲染，复用 C端基座。

---

## 五、共享地基（跨端统一）

| 共享层 | 内容 | 落地 |
|--------|------|------|
| **TS 契约** | 后端 `powerbank-common-api` DTO/枚举 → 前端 TS 类型 | openapi 生成，前后端零漂移 |
| **请求层** | `Result<T>` 拆包 + `ErrorCode` + 鉴权头/会话 + 幂等 key | 运营端/C端 各一份适配，同口径 |
| **原子类心智** | Tailwind 兼容类名 | 运营端 Tailwind4；C端 UnoCSS preset-wind |
| **i18n/RTL** | ar/en 双语 + RTL | 全端统一资源结构（中东刚需，见 ADR-009）|
| **设计 token + 规范** | 色板/间距/圆角 token、pnpm、ESLint、TS strict | 跨仓库统一 |

---

## 六、生态"能不能用"速查

| 技术 | 运营端 Web | C端(uni-app) |
|------|:---------:|:-----------:|
| **Tailwind CSS** | ✅ 4.x（shadcn 依赖）| ◐ 经 `weapp-tailwindcss`；uni 更推 UnoCSS |
| **UnoCSS**（C端首选）| 可选 | ✅ `unocss-applet` + preset-wind |
| **shadcn/ui** | ✅ 主组件库 | ❌ 无 DOM 不可用 |
| **wot-design-uni** | ❌ | ✅ |
| **TanStack Table/Query** | ✅ | Query 可用，Table 用 wot |
| **Ant Design** | ❌ 不采用（选 shadcn）| ❌ |

---

## 七、待确认
1. C端 App 端渲染：uni-app x（UTS 原生，新）还是常规 nvue/webview（稳）？
2. 运营端地图：中东 Google Maps + 中国高德 双适配是否第一期就要？
3. i18n 首发语言集（ar/en 必备，是否加其他中东语言）？
