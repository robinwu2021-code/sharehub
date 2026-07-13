# TDD-c-app（C 端 App 技术方案）

状态：已确认（2026-07-12）
关联需求：[docs/requirements/C端功能清单.md](../requirements/C端功能清单.md)（17 模块权威清单）、[features/C端-功能细化.md](../requirements/features/C端-功能细化.md)
关联决策：[ADR-005 C端载体与MENA支付](./ADR/ADR-005-C端载体与MENA支付.md)、[ADR-008 前端技术栈](./ADR/ADR-008-前端技术栈.md)、[ADR-009 市场与合规](./ADR/ADR-009-市场区域与合规.md)、[tech-stack-frontend.md](./tech-stack-frontend.md)
关联契约：[docs/api/README.md](../api/README.md)（`/mp/**` C 端 BFF）、[TDD-认证鉴权](./TDD-认证鉴权.md)（C 池 Bearer + 属主鉴权）、[TDD-trade-rental](./TDD-trade-rental.md)（借还状态机）
创建日期：2026-07-12

---

## 1. 需求摘要

面向 MENA 终端消费者的充电宝租借 App。**App 优先（P0，App Store / Google Play 为中东主渠道）**，微信小程序次要（P1，海外微信中国用户）。一套 uni-app 代码，条件编译隔离端差异。

**MVP 验收（对齐 C端功能清单 §六 P0 净清单）**：登录建户 → 找柜/地图 → 扫码借出（确认→下单→免押/押金授权→等弹出→兜底）→ 使用中实时计费 → 任意点归还结算 → 订单账单 → 自助报障/退款跟踪 → 借还账单通知 → 个人中心（信用分/语言 RTL/隐私注销）。全链路 **ar/en + RTL、AED**；**支付委托 nearpay，MVP 用 Stub 跑通借还状态机**（真实收费待 nearpay 对接）。

---

## 2. 当前架构分析

- **无 C 端工程**：仓库现有 `backend/`（模块化单体 + 内存种子/loc 已落 MariaDB）、`ops-web/`（Next 运营端，已跑通）、`docs/`。C 端需新建目录 `c-app/`，与三者平级。
- **可复用的已验证资产**：
  - `ops-web/lib/api/` 的**「唯一契约 + mock↔真实一键切换」**架构（`contract.ts` / `mock.ts` / `http.ts` / `http-client.ts` / `index.ts`）——本次在 C 端**复刻同一模式**，先 mock 出全部 UI，再翻转到真实 `/mp`。这套已在 ops-web + backend seed 两处验证。
  - `ops-web/lib/types.ts` 的契约镜像思路 + `OrderStatus` 状态机枚举——C 端 `types.ts` 与之**同源**（后端 `powerbank-common-api` 就绪后统一 openapi 生成）。
  - 后端 `/mp/**` 端点目录已在 [api/README §六/§七](../api/README.md) 定义（rent / orders / pay / deposit-free / login / profile / report / wallet / coupons / membership / nearby）。
- **契约口径（与 commons 有别，沿用 ops-web 已踩坑结论）**：对外统一 `{code,msg,data}` + 分页 `{records,total,page,size}`（非 commons 的 `{code,message,data}`/`{total,list}`）。C 端请求层拆包对齐此口径。
- **鉴权**：C 端走 auth-core **C 池**，`POST /mp/user/login {grantType}` 换 **Bearer(realm=CONSUMER)**，之后统一 `Authorization: Bearer`；**无 RBAC，仅属主鉴权**（后端 `ConsumerContext.assertOwner` 防 IDOR），前端只持有 token，不自造受信头。

---

## 3. 方案设计

### 3.1 渲染引擎选型（tech-stack-frontend 待确认 #1）

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| **常规 uni-app（Vue3 + TS + Vite）**（推荐）| App+小程序+H5 一套码成熟稳定；wot-design-uni / UnoCSS 生态齐全；可 tsc/eslint/CI；关键页可用 nvue 优化 | App 端非原生渲染，极端性能弱于 UTS | ✅ 采用 |
| uni-app x（UTS 原生渲染）| App 端原生性能最佳 | 小程序编译路径较新、组件库/生态不齐；**违背「一套码覆盖小程序」硬约束** | ❌ 小程序是 P1 硬需求，风险不接受 |

**决策**：**常规 uni-app（Vue3 + Vite）**。App 打包用 HBuilderX 云打包/自有证书；工程主体走 **CLI + Vite**（保证 git/lint/tsc/CI）。App 端首屏/地图等重页面按需 `renderjs`，暂不引 uni-app x。

### 3.2 技术栈（锁定，对齐 ADR-008 / tech-stack-frontend §三）

| 关注点 | 选型 |
|--------|------|
| 框架 | uni-app（Vue3 + TypeScript + Vite，CLI 工程） |
| UI 组件 | **wot-design-uni** |
| 原子类 | **UnoCSS + preset-wind**（Tailwind 兼容类名，与运营端心智一致）+ `unocss-applet` |
| 状态 | **Pinia**（`pinia-plugin-persistedstate` 持久化登录态） |
| 请求 | `uni.request` 封装（`Result<T>` 拆包 + Bearer + 幂等 key），复刻 ops-web mock↔真实一键切换 |
| i18n | vue-i18n（uni i18n），**ar/en**，RTL 镜像 |
| 扫码/地图/支付/推送/登录 | 端能力抽象层 + `#ifdef APP-PLUS / MP-WEIXIN` 条件编译 |

### 3.3 目录结构（`c-app/`，平级于 ops-web/backend）

```
c-app/
├── src/
│   ├── api/                    # ★ 复刻 ops-web/lib/api 一键切换
│   │   ├── contract.ts         #   interface McpApi（唯一契约）
│   │   ├── mock.ts             #   mockApi（先行，出全部 UI）
│   │   ├── http.ts             #   httpApi（端点对齐 /mp/**）
│   │   ├── http-client.ts      #   uni.request 封装 Result<T>+Bearer
│   │   └── index.ts            #   export const api = USE_MOCK?mock:http
│   ├── types/                  # 契约镜像（OrderStatus 等，同源 ops-web/types）
│   ├── mock/db.ts              # 全域 mock 数据集 + paginate/CRUD helper
│   ├── stores/                 # Pinia：user(登录态) / order(进行中) / wallet / app(语言RTL)
│   ├── ports/                  # ★ 端能力抽象（条件编译隔离端差异）
│   │   ├── auth.ts             #   登录：phone-otp/apple/google | wx-openid
│   │   ├── payment.ts          #   PaymentPort 端侧：卡/ApplePay/GooglePay | 微信JSAPI；MVP Stub
│   │   ├── scan.ts             #   uni.scanCode | 微信扫一扫
│   │   ├── map.ts              #   Google Maps | 腾讯/微信地图
│   │   └── push.ts             #   APNs/FCM/UniPush | 订阅消息
│   ├── i18n/                   # locale/{ar,en}.ts + RTL 方向
│   ├── design/tokens.ts        # 色板/间距/圆角 token（跨端统一）
│   ├── components/             # 业务复用件（柜机卡/费用明细/状态时间线/收银台）
│   ├── pages/                  # 页面（见 3.5 页面清单）
│   ├── pages.json              # 路由 + tabBar
│   ├── manifest.json           # App/小程序 appid、权限、SDK
│   └── App.vue / main.ts
├── uno.config.ts / vite.config.ts / tsconfig.json / .env.*
└── package.json
```

### 3.4 请求层与「mock↔真实」一键切换（核心地基）

复刻 ops-web 已验证模式：页面统一 `import { api } from '@/api'` 调 `api.xxx()`，零 `if(USE_MOCK)`。

- `contract.ts`：`interface McpApi` 唯一契约，方法签名对齐 `/mp/**`（`login/profile/nearbyCabinets/cabinetAvailability/rentOrder/order/orders/pay/depositFree/report/wallet/coupons/membership`）。
- `index.ts`：`export const api = import.meta.env.VITE_USE_MOCK !== '0' ? mockApi : httpApi`。
- `http-client.ts`：`uni.request` 封装——拼 `Authorization: Bearer`、拆 `{code,msg,data}`、`code!==0` 抛错、分页 `{records,total,...}`、幂等 key（借出/支付带 `Idempotency-Key`）。
- **价值**：`/mp` 后端未就绪时即可跑通全部 C 端 UI 与借还状态机演示；后端就绪后 `.env` 置 `VITE_USE_MOCK=0 + VITE_API_BASE` 翻转，页面零改（与 ops-web 切后端同法）。

### 3.5 页面清单（MVP P0）

| 分组 | 页面 | 关键端点 |
|------|------|---------|
| tabBar | 首页(地图/找柜) · 订单 · 我的 | `/mp/nearby/cabinets`、`/mp/trade/orders`、`/mp/user/profile` |
| 登录 | 登录/授权（条件编译） | `POST /mp/user/login` |
| 借出 | 扫码 → 借出确认页 → 等待弹出 → 借出结果 | `rent` + `deposit/free`\|`pay` + 弹出事件 |
| 使用 | 进行中订单（实时计费/封顶/归还指引/悬浮入口） | `GET /mp/trade/orders/{no}`、`?status=IN_USE` |
| 归还 | 归还成功 + 费用结算展示 | `return`（内部事件驱动）、`GET order` |
| 账单 | 我的订单列表 · 订单详情（状态时间线/费用明细/欠费补缴） | `orders`、`order`、`pay` |
| 售后 | 自助报障 · 报障进度 · 退款跟踪 | `POST /mp/user/report` |
| 个人 | 个人中心 · 信用分 · 语言RTL · 隐私/注销 · 关于 | `profile`、`logoff`(待定) |

P1 增量（钱包/押金、会员/次卡、优惠券、发票、消息中心、邀请分享）与 P2（蓝牙近场借还）按里程碑排后。

### 3.6 借还状态机（端侧，对齐 C端功能清单 §四 / ops-web OrderStatus）

`CREATED`(已下单待弹出) → `DISPENSING`(弹出中) → `IN_USE`(使用中) → `RETURNED`(已归还待结算) → `SETTLED`(已结算) → `CLOSED`(关闭)；任意态 → `EXCEPTION`(报障/干预)。

- **等弹出**：下单后**轮询** `GET /mp/trade/orders/{no}`（MVP，3s 目标；后续 push/SSE 提前结束轮询）；`DISPENSING→IN_USE` 成功进使用页，超时→提示 + 触发解冻/关单兜底。
- **进行中计费**：端只**展示**，计费以服务端为准（轮询刷新时长/费用/封顶）。
- **归还**：由设备归还事件驱动服务端 `return`（停计费→capture 请款→用券→结算），端收到 `RETURNED/SETTLED` 拉结算明细展示。

### 3.7 支付/免押（PaymentPort 端侧 + Stub，nearpay 延后）

- 端侧 `ports/payment.ts` 抽象 `preAuth / pay / capture(展示) / query`，**App**=卡/Apple Pay/Google Pay，**小程序**=微信 JSAPI，条件编译隔离；**MVP 全部走 Stub**（本地模拟成功/失败/超时分支），与后端 trade `PaymentPort` Stub 对齐，跑通「免押冻结→弹出→请款→解冻」编排。
- 免押主路径=**卡预授权冻结**（`POST /mp/trade/deposit/free`）；失败/不支持→降级押金（`POST /mp/trade/pay`）。卡信息不落端（nearpay 托管，端不留存 PAN）。

### 3.8 i18n / RTL / 本地化（中东刚需，一等公民）

- vue-i18n，`ar/en` 双语资源；`app` store 持久化语言，切换即时生效。
- **RTL**：根 `dir` 随语言切换；UnoCSS 用 logical properties（`ms-`/`me-`/`ps-`/`pe-`）避免物理左右；图标/进度/轮播镜像；数字/货币（AED）/日期本地化。
- 协议/隐私（PDPL）ar/en 双语；阿语字体与断行。

### 3.9 端能力抽象与条件编译（一套码覆盖差异）

`ports/*` 统一接口，`#ifdef APP-PLUS / MP-WEIXIN` 隔离实现；**业务逻辑与 UI 共用**：登录、支付、免押、推送、地图、扫码、分享、蓝牙 8 项按 C端功能清单 §五 逐项隔离；能力受限项（小程序支付分/订阅消息/蓝牙）降级并 ◐ 标注。

---

## 4. 测试策略

- **单元测试**（vitest）：请求层 `Result<T>` 拆包 / 错误分支 / 幂等 key；`mock.ts` CRUD helper；计费展示格式化（时长/AED/封顶）；i18n RTL 方向切换；状态机转移函数（合法/非法转移）。
- **关键场景（必测）**：① 扫码借出成功（下单→轮询 DISPENSING→IN_USE）；② 弹出超时兜底（解冻/关单，不产生费用）；③ 免押失败降级押金；④ 任意点归还结算（免费时长内 0 费用全解冻）；⑤ 报障生成工单闭环；⑥ ar↔en RTL 切换布局镜像。
- **端到端演示**：`VITE_USE_MOCK=1` 走通全流程 UI；翻转 `=0` 对接 backend `/mp` 验真实链路（与 ops-web 切后端同法）。App 真机验证扫码/地图/推送/条件编译分支。

---

## 5. 风险与注意事项

1. **`/mp` 端点部分「待定」**（注销/退押/钱包流水/发票/报障进度/站内消息）——mock 先行不阻塞 UI；真实对接前需在服务端 TDD + `powerbank-common-api` 契约补齐（C端功能清单 §八·4）。
2. **契约口径差异**：务必用自有 `{code,msg,data}`/`{records,total}` 拆包，勿直接套 commons `Result`（ops-web 已踩坑）。
3. **RTL 全链路**：物理左右类名是最大隐患，一律 logical properties，组件库 wot 的 RTL 覆盖需早验。
4. **App 打包链路**：证书/推送(APNs·FCM·UniPush)/地图 key/扫码权限在 `manifest.json`，需早跑通云打包一次真机（别留到最后）。
5. **支付延后**：MVP 用 Stub，勿在端写死任何 PSP；PaymentPort 抽象必须先立，避免 nearpay 接入时改业务。
6. **小程序范围收敛**（C端功能清单 §八·2 待确认）：小程序 P1 建议首期仅「借还+订单+报障」最小集，营销/会员/发票延后。

---

## 6. 实现任务（里程碑）

**M0 工程地基** ✅ 已完成（2026-07-12，H5 实测通过）
- [x] `c-app/` uni-app(Vite)+TS 脚手架，wot-design-uni + UnoCSS(preset-wind via unocss-applet) + Pinia + vue-i18n
- [x] 请求层：`api/{contract,mock,http,http-client,index}` + `mock/db.ts` + `types/`（复刻 ops-web 一键切换）
- [x] i18n ar/en + RTL（H5 dir 切换）+ 设计 token + tabBar/路由 + 页面(home/orders/me/login)
- 实测：home 找柜(mock 数据) / Me 语言切换 **RTL 全镜像(含原生 tabBar)** / login **wd-button** / mock 登录闭环(取凭据→换 token→loadProfile→已登录态) 全通过。
- 坑：① `unocss/vite` 是 ESM-only → vite.config 改 `.mts`；② `@dcloudio/vite-plugin-uni` 在 ESM 下工厂在 `.default` 上；③ 模板 `@vue/tsconfig@0.1.3` 用了 TS5 已删选项 → 自包含 tsconfig(moduleResolution bundler)。

**M1 借还闭环（全程 mock /mp）**
- [ ] 登录建户（端能力抽象，App phone-OTP mock）→ Pinia 持久化 Bearer
- [ ] 找柜/地图（列表+撒点，map port）→ 扫码 → 借出确认页 → 下单 → 等弹出(轮询) → 借出结果
- [ ] 使用中（实时计费/封顶/归还指引/悬浮入口）→ 归还结算展示 → 订单列表/详情（状态时间线/费用明细/欠费）

**M2 支付/售后/触达**
- [ ] PaymentPort 端侧 + Stub（免押冻结/请款/降级押金/失败重试）
- [ ] 自助报障→工单/退款跟踪；消息触达（Push 通道抽象，借还/账单通知）
- [ ] 个人中心（信用分/语言RTL/隐私/注销/关于）
- [ ] **翻转真实后端**：对接 backend `/mp`（后端就绪后）验证借还链路

**M3 规模化（首期纳入 ✅ 2026-07-12 用户确认）**：钱包/押金 · 会员/次卡 · 优惠券。三块纳入 MVP 首期建设（借还闭环稳定后紧接开发）。

**M3.5 延后 P1**：发票(VAT/TRN) · 消息中心 · `MP-WEIXIN` 小程序分支（微信登录/支付/订阅消息）

**M4 增强 P2**：蓝牙近场借还 · 邀请裂变归因 · nearpay 真实支付替换 Stub

---

确认记录（2026-07-12 用户确认）：
- 渲染引擎 = **常规 uni-app（Vue3+Vite）**（否决 uni-app x：小程序 P1 硬约束）。
- MVP 首期范围 = **P0 借还闭环 + 钱包/会员/券（P1 三块纳入首期）**；发票/消息中心/小程序分支延后。
- 下一步 = **开工 M0 工程地基**。
