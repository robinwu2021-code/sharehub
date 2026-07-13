# ADR-005 C端载体与 MENA 支付

状态：已接受（2026-07-11，取代原「共用小程序与服务商支付」）

## 背景
主市场 MENA（[ADR-009](./ADR-009-市场区域与合规.md)）：C端以 **App 为主**、小程序为次要（海外微信中国用户）；支付以**中东本地渠道**为主，微信/支付宝为 niche。原「共用小程序 + 微信支付服务商」方案（假设中国市场）作废。

## 决策
### C端载体
- **App 优先（P0）**：App Store / Google Play 为中东主分发渠道；uni-app 一套码，App 端为主目标。
- **小程序次要（P1）**：微信小程序服务海外中国用户；一套 uni-app 代码条件编译。
- 用户账户按 **openid×租户 / 手机号×租户** 建（App 用手机号/Apple/Google 登录，小程序用微信 openid）。

### 支付（决策：委托 neargo，延后集成）
- **powerbank 不自建支付渠道/PSP 适配，直接对接 neargo 支付 API（nearpay）**，且**该集成延后**（非 MVP 首批）。neargo 是 UAE 平台，nearpay 已规划承载 MENA 本地 PSP（Network Intl/Telr/PayTabs/Checkout）、卡收单、Apple/Google Pay、预授权、退款、对账。
- powerbank trade 内定义 `PaymentPort`（pay/preAuth/capture/release/refund/query）：MVP 给 **Stub 实现**跑通借还状态机；后接 **NearpayAdapter**（延后）。业务只依赖 `PaymentPort`。
- 免押 = 银行卡预授权冻结，由 nearpay 提供；powerbank 只做业务编排。
- niche（微信/支付宝、BNPL）由 nearpay 侧决定，powerbank 不感知。

### 分账
见 [ADR-004](./ADR-004-分账双模式.md)：分润**规则**在 powerbank；**执行/打款**优先复用 nearpay，随其契约确定，与支付一并延后。

## 备选与否决
- powerbank 自建 PSP 适配 → 否决（重复建设；neargo/nearpay 已面向同一 MENA 市场，直接复用）。
- 微信服务商 sub_mchid 为主 → 否决（中国专属，非主市场）。
- 一期即上独立品牌小程序 → 不适用（App 优先，小程序本就次要）。

## 影响
- powerbank 无 `PaymentChannel`/PSP 密钥/渠道回调；改为 `PaymentPort` + NearpayAdapter（延后）。
- db-design：`pay_order` 简化为**支付引用**（存 nearpay txn 引用 + 状态镜像），powerbank 不落渠道明文密钥；`pay_auth` 记免押编排状态，实际冻结在 nearpay。
- MVP 用 Stub，真实收费待 nearpay 对接；免除首批签约 PSP 的决策（归 nearpay）。
