# TDD — C端 BFF 与用户域（cend-bff `/mp`）

状态：草稿 / 待确认
关联需求：[C端功能清单](../requirements/C端功能清单.md) · [features/C端-功能细化](../requirements/features/C端-功能细化.md)
关联：[api/README §六·§五·§七](../api/README.md) · [TDD-trade-rental](./TDD-trade-rental.md)（借还引擎，本文不重复）· [TDD-认证鉴权](./TDD-认证鉴权.md) · [architecture.md](./architecture.md)
创建日期：2026-07-12

---

## 1. 需求摘要
为 C端（App 优先 / 微信小程序 niche）提供统一 `/mp/**` 接入层：**C 池会话鉴权 + 边缘受信头注入 + 面向端的聚合/裁剪**，并落地非借还的 C端用户域能力（账户/资料/注销、钱包押金、会员券、报障售后、发票、消息、附近网点、邀请）。**借还/支付/计费/结算编排委托 trade 域（[TDD-trade-rental](./TDD-trade-rental.md)），BFF 仅聚合不重编排**（api §八-1 定调）。核心验收：一套 uni-app 两端可用、契约 `ApiResult/PageData` 一致、幂等、PDPL/ar-en 合规。

## 2. 当前架构分析
- **现状骨架**：`powerbank-app` 合并模块化单体已服 `/api/**`（运营端）+ `/internal/**`；对外契约用自有 `ApiResult{code,msg,data}`/`PageData{records,total,page,size}`（见 [TDD-backend-bootstrap](./TDD-backend-bootstrap.md)）。运营端鉴权=员工 token（staff 池）。
- **复用 commons/既有**：`Result`/`IdGenerator`/`DomainEventPublisher`/auth-core（**C 池** TokenIssuer/OTP/RedisSessionStore）/`OperateLog`。
- **上游**：C端 App/小程序（uni-app）。**下游域**：trade（借还/支付/账务）、user（账户/信用/钱包）、loc+device（附近网点/影子库存）、marketing（券/会员/活动）、workorder+cs（报障）、finance（发票）、message（触达）。
- **差异点**：C 会话与员工会话是**两条独立安全链**（不同 realm/token 池，见 [ADR-010](./ADR/ADR-010-库合并.md) `pb_auth` realm 区分）。

## 3. 方案设计

### 3.1 BFF 边界与形态（方案选型）
| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A `/mp` 作 modulith 内独立 BFF 包 + 独立 C 安全链（推荐）| 与运营端同进程、复用域服务、零跨进程；MVP 快 | 与运营端共部署，按信号再裂解 | ✅ 采用（对齐 ADR-001 modulith-default）|
| B 独立 cend-bff 应用 | 物理隔离 C/员工流量 | 早期过度拆分、跨进程调用 | ❌ 规模化后按信号裂解 |
| C 端直连各域 | 无 BFF | 端多次往返、鉴权/裁剪散落、小程序往返敏感 | ❌ |

- **落地**：`powerbank-app` 新增 `mp/`（C端 BFF）包：`mp/web`(Controller)、`mp/aggregate`(聚合器)、`mp/security`(C 会话链)、`mp/dto`(端出参)。域能力在各自域 service，BFF 只聚合/裁剪/编排轻流程。

### 3.2 C 会话安全链（与员工链隔离）
- `CUserTokenAuthFilter`：解析 `Authorization: Bearer <c-token>` → auth-core C 池校验 → 注入 `CUserContext(cUserNo, memberLevel, blacklisted)`。
- 独立 `SecurityFilterChain`（`securityMatcher("/mp/**")`），与员工 `/api/**` 链并列；`permitAll`：`/mp/user/login`、`/mp/user/login/otp`、`/mp/nearby/**`、`/mp/user/agreements`、`OPTIONS`。
- **边缘受信头**：BFF 换取并注入 `X-User-Id`(cUserNo)/`X-Region-Id`；**客户端伪造同名头一律剥离**（api §1.3）。
- 风控守卫：`blacklisted` 用户在借出/支付前置拦截（读 user 信用），返 403 + 申诉入口码。

### 3.3 端点契约（对齐 api §六，字段级）
> 统一 `ApiResult<T>`；分页 `PageData<T>`；金额 `AED` 两位小数；时间 ISO8601 UTC；文案键 ar/en 由端本地化，服务端返稳定枚举码。

- **账户** `POST /mp/user/login`：`{channel:APP|MP, phone?,otp?, appleToken?, googleToken?, wechatCode?}` → `{cUserNo, token, expireIn, profile}`。`logoff`：软删 + PDPL 冷静期（`pii` 数据删除任务）。
- **附近** `GET /mp/nearby/cabinets?lat&lng&radius&keyword&returnable` → `PageData<NearbyCabinet{cabinetNo,siteName,distance,available,returnableSlots,priceBrief}>`（`available/returnable` 取设备影子）。
- **钱包** `GET /mp/user/wallet` → `{balance,bonus,deposit,frozen,currency}`；`transactions` 分页。
- **会员/券**：`membership` 购买经 `/mp/trade/pay`（复用支付）+ marketing 权益即时生效；`coupons` 领/用（用券在归还结算 §3.4）。
- **报障** `POST /mp/user/report`：`{orderNo?, type:NOT_EJECTED|CANNOT_RETURN|OVERCHARGE|OTHER, desc, images[]}` → 幂等（业务键）→ 开 `wo_workorder`（source=USER）或触发退款；`GET /mp/user/reports/{no}` 联动工单状态。
- **发票**：`invoice-titles`（VAT/TRN 校验）、`invoices`（仅 `SETTLED` 订单可开，防重复）。
- **消息**：`push-token` 注册（APNs/FCM/UniPush / 小程序订阅）；`notify-prefs` 分类开关 + 免打扰。

### 3.4 借还/支付编排（委托，不重写）
- 借出/归还/计费/结算/退款**全部落 trade 域**：BFF 转发 `POST /mp/trade/orders/rent`、`GET /mp/trade/orders`、`POST /mp/trade/pay`、`POST /mp/trade/deposit/free`；归还由 `DeviceEvent RETURNED` 事件驱动 `/internal/trade/orders/{no}/return`（见 [TDD-trade-rental §3.4/3.5](./TDD-trade-rental.md)）。
- BFF 仅做**面向端的聚合/裁剪**（如进行中订单 + 实时计费 + 归还指引 网点 一次返回），不做资金动作。
- 用券：结算用券规则在 trade 结算时应用，BFF 传 `couponNo`。

### 3.5 端差异（uni-app 条件编译，服务端契约端无关）
- 服务端契约对 App/小程序**同构**；端差异（登录方式/支付通道/免押/推送/地图）在端侧 `#ifdef APP-PLUS / MP-WEIXIN` 隔离（见 [C端功能清单 §五](../requirements/C端功能清单.md)）。服务端仅按 `login.channel` 分支换取会话与支付方式。

### 3.6 聚合器降级（小程序往返敏感）
- `MpAggregator`：有界并发扇出多域 + 单调用超时降级（对齐 nearboss BFF 模式）；聚合首页（钱包+进行中订单+券+附近）一次返回，弱依赖失败降级占位不整体失败。

## 4. 核心模型与数据
- 复用：`usr_user`/`usr_credit`（信用/黑名单）、`acct_*`（钱包/押金记账）、trade `ord_*`/`pay_*`、`wo_workorder`（报障）、marketing `mk_coupon`/`mk_membership`、finance 发票、`msg_*`。
- 个人数据（手机/证件/发票抬头）入 **`pb_pii`**（独立 KMS，PDPL）；BFF 出参按需脱敏。
- C 会话/凭据入 **`pb_auth`** C realm（auth-core）。

## 5. 配置项（零硬编码）
| 配置 | 位置 | 默认 |
|------|------|------|
| C token TTL / 刷新窗口 | Nacos `mp.session.*` | 7d / 滑动续期 |
| OTP 频控/有效期 | Nacos `mp.otp.*` | 60s 一条 / 5min |
| 聚合并发/超时 | Nacos `mp.aggregate.*` | pool 8 / 2000ms |
| 附近半径/上限 | Nacos `mp.nearby.*` | 2km / 50 条 |
| 报障图片数/大小 | Nacos `mp.report.*` | 6 张 / 5MB |
| 登录渠道开关（Apple/Google/微信）| Nacos `mp.login.*` | 按端/区域 |

## 6. 测试策略
- **单元**：`CUserTokenAuthFilter`（有效/过期/伪造头剥离/黑名单拦截）；`MpAggregator`（弱依赖降级、超时）；发票可开校验（仅 SETTLED、防重复）；报障幂等。
- **契约**：`/mp` 全端点 `ApiResult/PageData` 形状；`login` 各 channel 分支；受信头注入/剥离。
- **集成**：登录→附近→借出（走 trade Stub）→进行中→归还结算→账单→报障 全链路；PDPL 注销→数据删除；用券结算金额断言。
- **必测场景**：① 两端登录建户；② 黑名单拦截借出；③ 借还闭环（委托 trade）；④ 报障开工单/退款；⑤ 免押降级押金；⑥ 幂等（report/pay 重复）；⑦ 聚合弱依赖降级；⑧ 伪造受信头被剥离。

## 7. 风险与注意事项
- **双安全链**：C 会话与员工会话隔离，勿让 `/mp` 误挂员工 `@PreAuthorize`；`/mp` 用 `CUserContext` 而非 staff authorities。
- **不重编排**：借还/资金一致性归 trade（Outbox/幂等/记账），BFF 越权做资金动作会破坏一致性——严格只转发。
- **小程序能力受限**：支付分/订阅消息/蓝牙以 ◐ 标注，服务端契约同构、端侧降级；勿把端能力差异泄漏进服务端分支（除 `login.channel`/支付通道）。
- **PDPL**：注销/导出/最小化收集；个人数据入 `pb_pii`，出参脱敏；同意与撤回留痕。
- **契约稳定**：`/mp` 破坏性变更走新端点、只增不改（对齐 neargo）。

## 8. 实现任务
- [ ] common-api：`/mp` DTO（Login/Profile/Wallet/NearbyCabinet/Report/Invoice/Message…）+ `CUserContext`
- [ ] mp/security：`CUserTokenAuthFilter` + 独立 `SecurityFilterChain(/mp/**)`（auth-core C 池）
- [ ] mp/web：账户/附近/钱包/会员券/报障/发票/消息 Controller（对齐 api §六）
- [ ] mp/aggregate：`MpAggregator`（有界并发 + 超时降级）+ 首页聚合
- [ ] 借还/支付转发到 trade（`/mp/trade/*`，编排见 TDD-trade-rental）
- [ ] user/marketing/finance/message 域 service 落地被 BFF 调用的能力
- [ ] PDPL：注销/数据导出 + pii 脱敏
- [ ] 单元/契约/集成测试（8 必测场景）
- [ ] （骨架阶段）先出内存 Stub 版 `/mp`，点亮 uni-app C 端脚手架接真实契约

## 9. 分期
- **P0（借还闭环最小集）**：登录 · 附近 · 借出/使用/归还/结算（委托 trade）· 订单账单 · 报障 · 消息(借还通知) · 个人中心(信用/语言RTL/隐私)。
- **P1**：钱包押金 · 会员券 · 发票 · 客服会话 · 消息中心/偏好。
- **P2**：邀请裂变 · 蓝牙近场借还。

---
确认记录：待确认（依赖 [C端功能清单 §八](../requirements/C端功能清单.md) 待确认项 + api §八 BFF 边界）。
</content>
