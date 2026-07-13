# TDD-c-app-v2-alignment（C 端功能骨架 + UI 方案 · 对齐「简电·全球版」原型）

状态：草稿（方案已锁，待放行按 P0 开工）
关联：[TDD-c-app.md](./TDD-c-app.md)、[C端功能清单.md](../requirements/C端功能清单.md)、[ADR-005](./ADR/ADR-005-C端载体与MENA支付.md)/[008](./ADR/ADR-008-前端技术栈.md)/[009](./ADR/ADR-009-市场区域与合规.md)
参考原型：墨刀「共享充电宝_全球版」（优匠/简电，49 屏，密码 ujtek）
决策记录（用户 2026-07-13）：① 注册**两者都要**（OTP 静默建户 + 显式注册填信息）；② **密码 + OTP 双登录**（含忘记/修改密码）；③ 附近门店用**真实 Google 地图**（自有 Google 云，资源见 §6）；④ **锁定全量功能骨架 + UI**；⑤ 存档本文。

---

## 1. 目标
把 C 端对齐全球版原型的**完整信息架构**，补齐缺失能力；同时**保留**我们已建的增强（3 皮肤×明暗、中/英/阿+RTL、`pb-*` 组件库、线性图标、卡片阴影、入场动效、钱包/券/会员）。原型没有的这些作为加分项不回退。

## 2. 完整功能骨架（Screen Inventory）

> 状态：✅ 已实现 · ◐ 部分（需扩展）· ➕ 新增。优先级 P0(对齐核心+合规)/P1/P2。

### 2.1 启动与鉴权
| 屏 | 优先级 | 路由 / 载体 | 状态 | 关键点 |
|---|:--:|---|:--:|---|
| 启动页 Splash | P0 | `pages/splash` | ➕ | Logo/品牌、鉴权路由分发、语言/区域初始化 |
| 登录（OTP + 密码双 tab） | P0 | `pages/login` | ◐ | 顶部语言切换 + **区号选择** + OTP/密码切换 + 忘记密码/注册入口 |
| 区号选择 | P0 | `pb-country-picker`(弹层) | ➕ | 国旗+国家+`+区号`，搜索，全球版核心 |
| 忘记密码 | P0 | `pages/auth/forgot` | ➕ | 手机/邮箱 → OTP → 重置 |
| 注册 → 填写信息 | P1 | `pages/auth/register` | ➕ | 昵称/邮箱/密码；与 OTP 静默建户并存 |

### 2.2 首页 Hub（重构：非扁平找柜列表）
| 屏 | 优先级 | 路由 / 组件 | 状态 | 关键点 |
|---|:--:|---|:--:|---|
| 首页 Hub | P0 | `pages/home` | ◐ 重构 | 公告条 + **使用中订单常驻卡** + 附近门店 + 扫码 CTA |
| 查看公告（列表/详情） | P0 | `pages/notice` | ➕ | 运营公告，红点/已读 |
| 附近门店（地图） | P0 | `pages/home`/`pages/stores` | ◐ | **Google 地图撒点** + 列表切换 |
| 门店详情（弹层） | P0 | `pb-store-sheet` | ➕ | 地址/营业/可借可还/价格/导航/收藏 |
| 常用门店 | P1 | `pages/stores/favorites` | ➕ | 收藏门店列表 |

### 2.3 扫码租借（已闭环）
| 屏 | 优先级 | 载体 | 状态 |
|---|:--:|---|:--:|
| 扫码 → 借出确认 → 弹出充电宝 → 租借成功 | P0 | `ports/scan` + `pages/order/confirm` | ✅ 保持 |

### 2.4 订单
| 屏 | 优先级 | 路由 | 状态 | 关键点 |
|---|:--:|---|:--:|---|
| 订单列表（全部/进行中） | P0 | `pages/orders` | ✅ | 已有分段筛选 |
| 订单详情_使用中 | P0 | `pages/order/detail` | ◐ 扩展 | 实时计时/费用 + 归还指引 |
| 买断 | P0 | detail 动作+态 | ➕ | 达买断价可买断转持有 |
| 计费达到封顶 | P0 | detail 态 | ➕ | 到日封顶提示、费用不再涨 |
| 已完成 | P0 | detail(settled) | ✅ | |
| 订单疑问反馈 | P0 | `pages/order/feedback` | ◐ | 问题类型/描述/**图片上传** → 工单 |
| 问题详情 | P1 | `pages/order/feedback-detail` | ➕ | 处理进度/结果 |
| 投诉商家 | P1 | `pages/order/complain` | ➕ | 针对门店投诉 |

### 2.5 个人中心
| 屏 | 优先级 | 路由 | 状态 | 关键点 |
|---|:--:|---|:--:|---|
| 我的 | P0 | `pages/me` | ✅ 对齐 | 资料/钱包/入口聚合 |
| 账号设置 | P0 | `pages/me/settings` | ➕ | 语言/改密/注销/退出 聚合 |
| 修改密码 | P0 | `pages/me/change-pwd` | ➕ | 依赖密码体系 |
| **注销账户** | P0 | `pages/me/delete-account` | ➕ | **PDPL 合规硬需求**：冷静期/数据删除 |
| 退出登录 | P0 | `pages/me`/settings | ✅ | |
| 关于我们 | P0 | `pages/me/about` | ➕ | 版本/客服 |
| 隐私政策 | P0 | `pages/me/policy` | ➕ | ar/en/zh、PDPL |
| 个人信息（编辑） | P1 | `pages/me/profile` | ➕ | 昵称/头像 |
| 绑定手机 / 绑定邮箱 | P1 | `pages/me/bind-phone`/`bind-email` | ➕ | 全球版邮箱绑定 |
| 意见反馈 | P1 | `pages/me/feedback` | ➕ | 通用反馈 |

### 2.6 我们的增强（原型无，保留）
| 屏 | 状态 |
|---|:--:|
| 外观（3 皮肤 / 明暗 / 语言） | ✅ |
| 优惠券 / 会员 | ✅ |
| 钱包（余额/冻结，内嵌 Me；独立页 P1） | ◐ |
| 免押/押金（借出确认内） | ✅ |

## 3. 导航结构（IA）
- **底部 tabBar（自定义 `pb-tabbar`）**：首页Hub · 订单 · 我的。
- **栈页**：登录/注册/忘记密码、公告、门店详情(弹层)、订单详情/买断/反馈、设置/改密/注销/关于/隐私、个人信息/绑定、常用门店、券/会员/外观。
- 未登录：Splash → 登录；已登录直达 Hub。属主鉴权（Bearer realm=CONSUMER）。

## 4. 关键流程
1. **登录/注册**（两者都要）：Splash → 登录页 →〔OTP：区号+手机→验证码→静默建户直登〕/〔密码：手机+密码〕/〔忘记密码→OTP→重置〕/〔注册→填信息→建户〕。
2. **借还**（含新增态）：扫码→确认(免押/押金)→弹出→使用中→〔买断〕/〔到封顶〕→归还→结算→已完成。
3. **附近门店**：定位→Google 地图撒点→点选→门店详情弹层→借/导航/收藏。
4. **疑问反馈**：订单→反馈(类型+描述+图片)→工单→问题详情→（可）投诉商家。
5. **合规注销**：设置→注销账户→风险确认+冷静期→数据删除（PDPL）。

## 5. 新增组件（`src/components/ui/`）
`pb-country-picker`（区号弹层）· `pb-otp-input`（6 格验证码+倒计时）· `pb-field`/`pb-input`（表单行）· `pb-tabs`（OTP/密码切换）· `pb-map`（Google Maps 封装）· `pb-store-sheet`（门店详情弹层）· `pb-notice-bar`（公告条）· `pb-ongoing-bar`（首页使用中订单常驻卡）· `pb-dialog`/`pb-confirm`（注销/退出确认）· `pb-uploader`（反馈图片）· `pb-list-nav`（设置分组，复用 `pb-cell`）。均沿用 token/皮肤/图标体系。

## 6. Google 地图接入 + Google Cloud 资源指示 ⭐（你的第 3 点）

### 6.1 端侧接入方式（按我们打包现状）
- **当前 = Capacitor(WebView) / H5** → 用 **Maps JavaScript API**，封装 `pb-map`（H5 用 `renderjs` 直接加载 Google Maps JS SDK 渲染，避免 uni `<map>` 的 Google 兼容坑）。
- **未来 = uni 原生 App** → `manifest.json` 配 `app-plus.distribute.sdkConfigs.maps.google.key`（Android/iOS Maps SDK）。
- **微信小程序（P2）** → uni `<map>` + 腾讯地图（Google 在中国不可用），`#ifdef MP-WEIXIN` 条件编译隔离。
- key 注入：`.env` `VITE_GMAPS_KEY`（`.gitignore`，勿硬编码进仓库）。

### 6.2 你需要在 Google Cloud 开通/创建的资源（照做即可）
1. **GCP 项目**：用现有 Google 云新建（或复用）一个项目，**绑定结算账号**（Maps 平台需启用 Billing；每月约 $200 免费额度，够开发+早期用量）。
2. **启用 API**（APIs & Services → Library → Enable）：
   - `Maps JavaScript API`（**必需**：H5/Capacitor 地图渲染）
   - `Places API (New)`（门店搜索 / 地点详情 / 自动补全）
   - `Geocoding API`（地址 ↔ 坐标）
   - `Routes API`（或 `Directions API`，导航到门店；也可仅唤起外部地图，则可暂不开）
   - （未来原生）`Maps SDK for Android` + `Maps SDK for iOS`
3. **创建 API Key 并按平台分离 + 加限制**（Credentials → Create credentials → API key）：
   - **Web Key（现在就要）**：Application restrictions = **HTTP referrers**，白名单我们的生产域名 + `http://localhost:*`（开发）；API restrictions = 只勾 `Maps JavaScript API`+`Places`+`Geocoding`。
   - **Android Key（未来原生）**：Application restrictions = **Android apps**，填包名 `ai.neargo.powerbank` + 签名 **SHA-1**（debug 与 release 各一）；API restrictions = `Maps SDK for Android`。
   - **iOS Key（未来原生）**：Application restrictions = **iOS apps**，填 Bundle ID；API restrictions = `Maps SDK for iOS`。
4. **配额与安全**：给每个 key 设配额告警；key 只放 `.env` / 服务端注入，仓库不留明文。
5. **交付给我**：先给 **Web Key**（放 `.env` `VITE_GMAPS_KEY`）即可开工首页地图；Android/iOS key 待原生打包时再给。
6. **地区确认**：Google Maps 在 MENA/全球可用（✅ 主市场）；中国大陆不可用 → 中国用户/小程序端走腾讯（条件编译，P2）。

## 7. 契约新增（`/mp`，mock 先行，一键切换，后端 TDD 补齐）
- 鉴权：`POST /mp/user/login`(扩 grantType=password)、`POST /mp/user/register`、`POST /mp/user/otp/send`、`POST /mp/user/password/reset`(忘记)、`POST /mp/user/password/change`、`POST /mp/user/bind`(手机/邮箱)、`POST /mp/user/logoff`(注销)。
- 门店/公告：`GET /mp/notice`(+`/{no}`)、`GET /mp/sites/{siteNo}`(门店详情)、`GET/POST /mp/user/favorites`(常用门店)。
- 订单：`POST /mp/trade/orders/{no}/buyout`(买断)、`GET /mp/user/reports/{no}`(问题详情)、投诉端点。
- 资料：`PUT /mp/user/profile`。
- 契约镜像进 `src/api/contract.ts` + `mock.ts` + `http.ts`，`mock/db.ts` 加数据。

## 8. 设计规范（沿用第 1–3 轮成果）
白底为基 · 3 皮肤(mono/blue/purple)×明暗（CSS 变量换肤）· Nunito/Tajawal/Noto Sans SC · `pb-icon` 线性图标 · 白卡柔和阴影 · `.pb-h1/.pb-h2/.pb-num` 排版 · press 回弹 · `.pb-rise` 入场动效 · 中/英/阿+RTL。新页面一律用现有 token 与组件。

## 9. 分期与任务
**P0（对齐核心闭环 + 合规 + 地图）**
- [ ] 启动页 Splash + 鉴权路由
- [ ] 登录区：`pb-country-picker` + OTP(`pb-otp-input`)/密码双 tab + 忘记密码 + 登录页语言切换
- [ ] 首页 Hub 重构：`pb-notice-bar`(公告) + `pb-ongoing-bar`(使用中订单) + 附近门店 + 扫码 CTA
- [ ] `pb-map`(Google Maps JS) + `pb-store-sheet`(门店详情)
- [ ] 订单详情扩展：买断 + 计费封顶态
- [ ] 疑问反馈页（+`pb-uploader`）
- [ ] 账号设置 + 修改密码 + **注销账户(PDPL)** + 退出
- [ ] 关于我们 + 隐私政策
- [ ] 契约/mock 补齐上述端点

**P1**：注册填信息 · 个人信息编辑 + 绑定手机/邮箱 · 常用门店 · 意见反馈 · 问题详情/投诉商家 · 钱包独立页。
**P2**：消息中心 · 邀请裂变 · 蓝牙近场 · `MP-WEIXIN` 小程序分支（微信登录/支付/订阅消息/腾讯地图）。

## 10. 风险 / 待确认
- Google 地图在 Capacitor WebView 定位需 App 授予定位权限（`manifest`/Capacitor Geolocation 插件）；H5 用浏览器定位。
- 密码体系落地后端 auth-core（C 池）需支持 password realm；MVP 端侧 mock。
- 注销/绑定/改密等端点后端未就绪，先 mock，后端 TDD（沿用 [powerbank-backend] 契约口径）。

---
确认记录：待用户放行 P0。
