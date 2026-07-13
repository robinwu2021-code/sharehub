# 服务端 API 接口定义（api/README.md）

> 状态：草稿（待确认）· 创建 2026-07-11
> 关联：[architecture.md](../technical/architecture.md) · [db-design.md](../technical/db-design.md)
> 约定对齐 ai-neargo：统一 `Result<T>`、`AuthHeaders` 可信头、`Query/PageResult` 分页、`ErrorCode`。
> 本文为端点目录（method/path/用途）；字段级 req/resp DTO 由各模块 TDD + `powerbank-common-api` 契约细化。

---

## 一、通用约定

### 1.1 响应包（commons `Result<T>`）
```json
{ "code": 0, "msg": "ok", "data": {...} }
```
错误码 `ErrorCode`：0 成功 · 400 参数 · 401 未认证 · 403 无权 · 404 不存在 · 409 冲突/幂等 · 500 服务端。业务细分码在 `msg` + 扩展 `bizCode`。

### 1.2 路径分层
| 前缀 | 面向 | 鉴权 |
|------|------|------|
| `/api/**` | 各端 BFF → 内部域 | 内部信任头（BFF 已换取 `AuthHeaders`） |
| `/mp/**` | C端小程序/App BFF | C端会话（auth-core C 池） |
| `/internal/**` | 域间 RestClient | 内网 + 受信头透传 |
| `/gw/**` | 硬件供应商南向回调 | 供应商验签（`DeviceDriver.verify`） |
| `/notify/**` | 支付渠道异步回调 | 渠道验签 |
| `/openapi/**` | 第三方 | AppKey 签名 + 限流 |

### 1.3 受信头（commons `AuthHeaders`，边缘注入、内部只读）
`X-Region-Id` · `X-User-Id` · `X-Merchant-Id`(承载 powerbank tenant，ADR-007) · `X-Roles` · `X-Store-Scope`。客户端伪造的同名头在入口一律剥离。

### 1.4 分页 / 幂等 / 版本
- 分页：query `page/size/sort` → `PageResult<T>{records,total,page,size}`。
- 幂等：写操作带 `Idempotency-Key`（或业务键 `*_no`）；重复返回首次结果（409 或原样）。
- 版本：路径不带版本，破坏性变更走新端点；契约演进只增不改（对齐 neargo）。

---

## 二、platform 域

### 2.1 租户（平台超管）
| Method Path | 用途 |
|---|---|
| `POST /api/platform/tenants` | 开通租户（品牌运营商） |
| `GET /api/platform/tenants` | 租户列表（分页） |
| `GET/PUT /api/platform/tenants/{tenantNo}` | 租户详情/更新（套餐/配额/到期） |
| `PUT /api/platform/tenants/{tenantNo}/status` | 启用/停用 |
| `GET/PUT /api/platform/tenants/{tenantNo}/config` | 租户配置（支付/计费/品牌/启用供应商渠道） |

### 2.2 IAM · 员工 · 权限
| Method Path | 用途 |
|---|---|
| `POST/GET /api/platform/employees` | 员工新增/列表 |
| `GET/PUT/DELETE /api/platform/employees/{no}` | 员工详情/更新/离职 |
| `GET/POST /api/platform/depts` | 组织架构 |
| `GET/POST /api/platform/roles` | 角色（含权限+数据范围）|
| `GET /api/platform/permissions` | 权限目录（全局）|
| `POST /internal/platform/authz/check` | 鉴权校验（BFF 调用）|

### 2.3 审计 / 通知 / 配置 / 开放平台
| Method Path | 用途 |
|---|---|
| `GET /api/platform/audit-logs` | 审计日志查询 |
| `GET/POST /api/platform/notify/templates` | 通知模板 |
| `POST /internal/platform/notify/send` | 发送通知（域间调用）|
| `GET /api/platform/dicts/{type}` | 字典 |
| `GET/POST /api/platform/openapi/apps` | 开放平台应用/密钥 |

---

## 三、ops 域

### 3.1 设备（柜机/仓位/充电宝）
| Method Path | 用途 |
|---|---|
| `GET /api/ops/cabinets` | 柜机列表（状态/在线/点位筛选，分页/地图）|
| `GET /api/ops/cabinets/{cabinetNo}` | 柜机详情 + 仓位明细 |
| `POST /api/ops/cabinets/import` | 批量导入设备台账 |
| `POST /api/ops/cabinets/{cabinetNo}/commands` | 远程控制（弹出/锁定/重启）→ 转 access-gateway |
| `PUT /api/ops/cabinets/{cabinetNo}/location` | 绑定/换绑/调拨点位 |
| `GET /api/ops/powerbanks` | 充电宝列表/生命周期查询 |
| `PUT /api/ops/powerbanks/{no}/status` | 充电宝状态变更（报废/丢失）|
| `POST /internal/ops/devices/events` | 接收 access-gateway 上行设备事件（DeviceEvent）|
| `GET /internal/ops/cabinets/{no}/availability` | 可借/可还库存（trade 借出前校验）|

### 3.2 点位 · 场地方 · 合同
| Method Path | 用途 |
|---|---|
| `GET/POST /api/ops/locations` | 点位 POI |
| `GET/POST /api/ops/venues` | 场地方（点位商户）|
| `GET/POST /api/ops/contracts` | 进场合同（分成/账期）|

### 3.3 工单
| Method Path | 用途 |
|---|---|
| `GET/POST /api/ops/work-orders` | 工单列表/手动创建 |
| `GET /api/ops/work-orders/{woNo}` | 工单详情 |
| `POST /api/ops/work-orders/{woNo}/dispatch` | 派单（就近/负载/手动）|
| `POST /api/ops/work-orders/{woNo}/accept` | 运维接单（Ops App）|
| `POST /api/ops/work-orders/{woNo}/handle` | 现场处理（打卡/拍照/换件）|
| `POST /api/ops/work-orders/{woNo}/close` | 完成/审核关单 |
| `POST /internal/ops/alerts` | 告警上报（自动开单联动）|

### 3.4 OTA · 告警
| Method Path | 用途 |
|---|---|
| `POST /api/ops/ota/releases` | 发布固件版本 |
| `POST /api/ops/ota/rollouts` | 创建投放（scope/forced）|
| `GET /api/ops/ota/rollouts/{id}` | 投放进度（task 汇总）|
| `GET /api/ops/alerts` | 告警列表/处置 |

---

## 四、access-gateway（南向接入 · 独立进程）

| Method Path | 用途 |
|---|---|
| `POST /gw/vendors/{vendor}/callback` | 供应商 HTTP 回调入口（验签→driver.parseWebhook→发 DeviceEvent）|
| `POST /internal/gw/commands` | 业务域下发统一指令（EJECT_SLOT 等，返回 commandId）|
| `GET /internal/gw/commands/{commandId}` | 指令状态查询（幂等/结果）|
| `GET /internal/gw/devices/{sn}/online` | 设备在线态查询 |
| `POST /internal/gw/vendors/{vendor}/config` | 供应商接入配置（driver 注册/密钥）|

> TCP/MQTT 上行不经 REST：Netty/EMQX 接入 → driver.decode → 统一 `DeviceEvent` → `DomainEventPublisher`（ops/trade 消费）。指令下发内部走 `/internal/gw/commands`，网关经 driver.encode/invoke 发设备。

---

## 五、trade 域

### 5.1 租借订单 · 计费
| Method Path | 用途 |
|---|---|
| `POST /mp/trade/orders/rent` | 扫码借出（校验→创单→预授权→触发弹出）|
| `GET /mp/trade/orders/{orderNo}` | 订单详情（进行中计费）|
| `GET /mp/trade/orders` | 我的订单（C端）|
| `POST /internal/trade/orders/{orderNo}/return` | 归还结单（access-gateway 归还事件驱动）|
| `POST /api/trade/orders/{orderNo}/intervene` | 客服干预（强制归还/免单/补偿）|
| `GET/POST /api/trade/price-plans` | 计费模板管理 |

### 5.2 支付 · 退款（**委托 neargo nearpay，延后集成 · ADR-005**）
> powerbank 经 `PaymentPort` 调 nearpay；MVP 用 Stub。以下为 powerbank 侧编排端点，实际收单在 nearpay。

| Method Path | 用途 |
|---|---|
| `POST /mp/trade/pay` | 发起支付（经 PaymentPort→nearpay，返回收银台参数）|
| `POST /mp/trade/deposit/free` | 免押授权（经 nearpay 预授权冻结）|
| `POST /notify/pay/nearpay` | nearpay 支付结果回调/事件（幂等→改状态→发 PayEvent）|
| `POST /api/trade/refunds` | 发起退款（经 PaymentPort→nearpay）|
| `POST /notify/refund/nearpay` | nearpay 退款回调 |

### 5.3 账务 · 分账 · 结算
| Method Path | 用途 |
|---|---|
| `GET/POST /api/trade/share-rules` | 分润规则配置 |
| `GET /api/trade/share-records` | 分润明细 |
| `GET /api/trade/settlements` | 结算单 |
| `POST /api/trade/withdrawals` | 提现申请 |
| `POST /api/trade/withdrawals/{no}/audit` | 提现审核/打款 |
| `GET /api/trade/recon/{reconNo}` | 对账结果/差错 |

---

## 六、user 域 & C端 BFF（`/mp`）

> C端 App/小程序统一走 `/mp/**`（auth-core C 池会话；受信头由 BFF 边缘注入）。端点对齐 [C端功能清单](../requirements/C端功能清单.md)（末列为其子功能编号），聚合与编排见 [TDD-cend-bff](../technical/TDD-cend-bff.md)。租借/支付端点在 §五（trade 域）。

### 6.1 账户与登录（清单 §1/§16）
| Method Path | 用途 | 清单 |
|---|---|---|
| `POST /mp/user/login/otp` | 发送手机 OTP（App）| C-AC-01 |
| `POST /mp/user/login` | 授权登录（App 手机OTP/Apple/Google · 小程序 微信 code→openid），建 openid×MAIN 账户、发 C 池 token | C-AC-01 |
| `POST /mp/user/token/refresh` | 会话续期 | C-AC-03 |
| `GET/PUT /mp/user/profile` | 我的资料/信用分 · 修改昵称头像 | C-AC-04/C-ME-02 |
| `POST /mp/user/logoff` | 账号注销（PDPL 冷静期 + 数据删除）| C-AC-05 |
| `GET /mp/user/agreements` | 用户/隐私协议（ar/en）| C-AC-06 |
| `POST /mp/user/data-export` | 个人数据导出（PDPL）| C-AC-06 |

### 6.2 附近网点（清单 §2）
| Method Path | 用途 | 清单 |
|---|---|---|
| `GET /mp/nearby/cabinets` | 附近网点/可借可还/筛选（经纬度 + keyword）| C-MAP-01/02/05 |
| `GET /mp/nearby/sites/{siteNo}` | 网点详情（柜机/价格/营业时间）| C-MAP-03 |

### 6.3 钱包与押金（清单 §9）
| Method Path | 用途 | 清单 |
|---|---|---|
| `GET /mp/user/wallet` | 钱包（余额/赠金/押金/冻结）| C-WA-01/C-DF-04 |
| `GET /mp/user/wallet/transactions` | 钱包流水（充值/消费/退款/赠送）| C-WA-05 |
| `POST /mp/user/deposit/refund` | 退押金申请（无进行中订单/欠费）| C-WA-04 |

### 6.4 会员与营销（清单 §10/§11）
| Method Path | 用途 | 清单 |
|---|---|---|
| `GET/POST /mp/user/membership` | 会员/次卡 购买与权益 | C-MB-01/03 |
| `DELETE /mp/user/membership/auto-renew` | 取消自动续费 | C-MB-04 |
| `GET /mp/user/coupons` | 我的券 / 领券中心 | C-CP-01/02 |
| `POST /mp/user/coupons/{couponNo}/claim` | 领券（防重复/库存）| C-CP-01 |

### 6.5 售后与客服（清单 §12）
| Method Path | 用途 | 清单 |
|---|---|---|
| `POST /mp/user/report` | 自助报障（未弹出/未归还/多扣费）→ 工单/退款 | C-CS-01 |
| `GET /mp/user/reports` | 我的报障列表 | C-CS-02 |
| `GET /mp/user/reports/{reportNo}` | 报障进度/结果 | C-CS-02 |

### 6.6 发票（清单 §13）
| Method Path | 用途 | 清单 |
|---|---|---|
| `GET/POST /mp/user/invoice-titles` | 抬头管理（个人/企业 + VAT/TRN）| C-IV-02 |
| `POST /mp/user/invoices` | 开票申请（选已结算订单）| C-IV-01 |
| `GET /mp/user/invoices` | 发票记录/下载/重发 | C-IV-03 |

### 6.7 消息触达（清单 §14）
| Method Path | 用途 | 清单 |
|---|---|---|
| `POST /mp/user/push-token` | 注册 Push token（APNs/FCM/UniPush）| C-MS-01 |
| `GET /mp/user/messages` | 站内消息中心（历史/已读未读）| C-MS-03 |
| `PUT /mp/user/notify-prefs` | 通知偏好（分类/免打扰/语言）| C-MS-04 |

### 6.8 邀请与分享（清单 §15，P2）
| Method Path | 用途 | 清单 |
|---|---|---|
| `GET /mp/user/invite` | 邀请码/邀请链接 | C-SH-01 |
| `POST /mp/user/invite/bind` | 新用户绑定邀请人（归因/反作弊）| C-SH-03 |

### 6.9 内部（域间）
| Method Path | 用途 |
|---|---|
| `POST /internal/user/credit/blacklist` | 风控拉黑（客服/系统）|

---

## 七、借还主流程端点编排（对照 [分端对照 §5](../requirements/功能矩阵-分端对照.md)）

```
借出:  POST /mp/trade/orders/rent
        → GET /internal/ops/cabinets/{no}/availability (校验)
        → POST /mp/trade/deposit/free (免押) | POST /mp/trade/pay
        → POST /internal/gw/commands (EJECT_SLOT)
        ← DeviceEvent RENT_CONFIRMED (DomainEventPublisher) → 订单 IN_USE
归还:  DeviceEvent RETURNED → POST /internal/trade/orders/{no}/return
        → 停止计费 → capture 请款 → share_record → 通知
异常:  POST /mp/user/report → POST /api/trade/orders/{no}/intervene
```

## 八、待确认
1. BFF 边界：`/mp`（C端）与 `/api`（运营）是否各自独立 BFF 应用，指令/支付编排放 BFF 还是 trade 域？（本文倾向编排在域、BFF 仅聚合）
2. 南向指令下发：`/internal/gw/commands` 同步返回 commandId + 异步事件确认，业务侧等事件还是轮询？（本文：异步事件驱动）
3. OpenAPI 首期开放范围（设备状态/订单查询/对账）？
4. 是否需要 GraphQL/BFF 聚合层给小程序减少往返？
5. C端 `/mp` 契约（§六）已按 [C端功能清单](../requirements/C端功能清单.md) 补全端点目录；字段级 req/resp DTO 与聚合编排在 [TDD-cend-bff](../technical/TDD-cend-bff.md) 细化，落地前经 `powerbank-common-api` 契约固化。
