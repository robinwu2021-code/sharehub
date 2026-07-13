# TDD · 接入层按端分离 + common 复用 ai-neargo

状态：待确认（2026-07-12）
关联：[architecture §4 触点BFF](./architecture.md) · [api/README 路径分层](../api/README.md) · [后端三层架构梳理](./后端三层架构梳理.md) · [ADR-014 更名](./ADR/ADR-014-平台更名ShareHub与设备类型抽象.md) · [ADR-006 复用neargo](./ADR/ADR-006-复用neargo基础框架与依赖方式.md)

## 1. 需求摘要

用户两条定调：① **C端与运营端接入层清晰分开**，底层（domain 服务/mapper/实体）可复用；参考架构重新梳理两端的 **API 与 controller**。② **common 尽量复用 ai-neargo 的 common**。

## 2. 参考架构（ai-neargo 实证）

ai-neargo 的端分离**不在 controller 子包，而在部署单元**：一个受众一个 Spring Boot **BFF app**（`apps/nearboss`=B端商户、`apps/neargo`=C端消费者、`apps/nearadmin`=后台前端），每个 app 在 `SecurityConfig` 里**锁定 Realm**（`new OpaqueTokenAuthFilter(store, Realm.MERCHANT/CONSUMER)`），realm 闸门拒绝跨池 token；内部 `modules/*`（域服务）用 **feature-based controller + `TenantContextFilter`（只读受信头，不碰 Redis）**。边缘 app 校验不透明 token→注入 `X-*` 受信头→内部模块只读头。

→ **powerbank 是模块化单体（ADR-001/010，单 deployable）**，对应实现：**接入层按端分包**，两个包 1:1 映射未来两个 BFF app（`portal/ops`→ops-app、`portal/mp`→mp-app）；**domain 核心端无关、两端共享**（未来裂解为域服务）。端隔离今日已由**双 SecurityFilterChain**（[SecurityConfig](../../backend/powerbank-app/src/main/java/ai/neargo/powerbank/config/SecurityConfig.java)：@Order1 `securityMatcher("/mp/**")` 消费者链、@Order2 兜底运营端链）按路径强制——本方案让包布局镜像这一既有切分。

## 3. 当前问题

1. **端未清晰分开**：C端 controller 埋在 `user/consumer/`（`ConsumerAuthController`/`MpController`），运营端 controller 散在各域包（`ops/`、`trade/`、`user/`、`platform/`、`gateway/`、`agent/`）。一眼看不出"哪些是 C端、哪些是运营端"。
2. **C端 API 路径漂移**：实现是 `/mp/auth/*`、`/mp/user/profile`、`/mp/orders/{ownerNo}`；[api/README §6](../api/README.md) 设计是 `/mp/user/login`、`/mp/nearby/*`、`/mp/trade/orders/rent`…——不一致，C端借还闭环端点大多未落地。
3. **common 重复造轮子**：`common/{ApiResult,PageData,ApiResponseWrapper,GlobalExceptionHandler}`、`auth/{SessionStore,OtpService}`、各实体手写审计字段——neargo-common-* 已有对应件。

## 4. 方案设计

### §A 接入层按端分包（controller 层）· domain 共享（service/mapper/entity）

```
ai.neargo.powerbank（迁移后 sharehub）
├── portal/                     ★接入层（端分离；未来裂解为 BFF app）
│   ├── ops/    运营端 → /api/** /internal/**   StaffContext + @PreAuthorize(RBAC) + DataScope 横切
│   │   ├── DashboardController   /api/ops/dashboard
│   │   ├── DeviceController      /api/ops/cabinets*            → dev 域服务
│   │   ├── LocationController    /api/ops/sites|locations|venues|contracts → loc 域服务
│   │   ├── WorkOrderController   /api/ops/work-orders*         → wo 域服务
│   │   ├── AgentController       /api/agent/agents             → agent 域服务
│   │   ├── TradeAdminController  /api/trade/orders(intervene)|price-plans → trade 域服务
│   │   ├── FinanceController     /api/trade/share-*|settlements|withdrawals* → finance 域服务
│   │   ├── UserAdminController   /api/user/*                   → user 域服务
│   │   ├── PlatformController    /api/platform/*               → iam 域服务
│   │   └── VendorController      /internal/gw/vendors          → gw 域服务
│   ├── mp/     C端 → /mp/**       ConsumerContext 属主鉴权（无 RBAC）
│   │   ├── ConsumerAuthController /mp/user/login|login/otp|token/refresh|logoff  ← 纠正自 /mp/auth/*
│   │   ├── AccountController      /mp/user/profile|wallet|coupons|invoices|messages → user 域服务
│   │   ├── NearbyController       /mp/nearby/cabinets|sites/{no}（public）→ loc/dev 域服务
│   │   ├── RentController         /mp/trade/orders/rent|{no}|pay|deposit/free → trade 域服务
│   │   └── ReportController       /mp/user/report(s)           → wo/user 域服务
│   └── south/  南向/回调 → /gw/** /notify/**（monolith 内 access-gateway 占位）
│
├── domain/                     ★业务核心（端无关，两端复用；未来裂解为域服务）
│   └── loc/ dev/ wo/ agent/ trade/ finance/ user/ iam/ gw/
│         每域 { service(interface+impl) · mapper · entity · statemachine · event }
│
└── support/  横切：auth/（权限对话）· config/（SecurityConfig 双链/MybatisPlusConfig）· common/（→尽量退役复用 neargo）
```

**核心原则**：一个 domain 服务被两端复用——如 `loc/dev` 域服务同时供 `portal/ops/DeviceController`（运营端设备台账）与 `portal/mp/NearbyController`（C端找附近可借柜）。controller 从 domain 包**移出**到 `portal/<端>`，domain 包只留 service/mapper/entity。

**controller 迁移映射（现 → 目标）**：

| 现 | 目标 | 端 |
|---|---|---|
| `ops/OpsController`（拆分） | `portal/ops/{Dashboard,Device,Location,WorkOrder}Controller` | 运营端 |
| `trade/TradeController`（拆分） | `portal/ops/{TradeAdmin,Finance}Controller` | 运营端 |
| `user/UserController` | `portal/ops/UserAdminController` | 运营端 |
| `platform/PlatformController` | `portal/ops/PlatformController` | 运营端 |
| `gateway/VendorController` | `portal/ops/VendorController` | 运营端 |
| `agent/AgentController` | `portal/ops/AgentController` | 运营端 |
| `user/consumer/ConsumerAuthController` | `portal/mp/ConsumerAuthController`（路径对齐 README） | C端 |
| `user/consumer/MpController`（拆分/扩展） | `portal/mp/{Account,Nearby,Rent,Report}Controller` | C端 |

domain 保留：`loc/agent/dev/wo` 现有 service/mapper/entity 原地留在 `domain/`；`trade/finance/user/iam/gw` 补齐（[后端三层架构梳理 §四](./后端三层架构梳理.md) 顺序）。

### §B common 复用 ai-neargo（逐模块决策）

Spring Security 顾虑已澄清：`-common-security` 的 `spring-security-core` 是 **provided（非传递）**，`-common-data` 只拖 **MyBatis-Plus+JDBC 驱动**（powerbank 已在用，顾虑消失）。

| 模块 | 结论 | 复用/退役内容 |
|---|---|---|
| **neargo-common-core** | ✅ 复用 | `IdGenerator`（替手拼 `<x>_no`）、`ErrorCode`、`ServerException`、`Query`、`JsonUtils`、`AssertUtils`。响应包 `Result`/`PageResult` 见下 ⚠️ |
| **neargo-common-web** | ✅ 复用 | `ServerExceptionHandler`（退役自有 `GlobalExceptionHandler`）；`@OperateLog`+`OperateLogAspect`（正好补 Task #11 审计切面）；`ClientHttpFactories`（未来域间 RestClient） |
| **neargo-common-data** | ✅ 复用 | `BaseEntity`（审计 id/createdAt/updatedAt/version/deleted，替各实体手写字段）、`BaseRepository`（upsert/insertOrGet）、`AuditMetaObjectHandler`（自动填充）、`NeargoTenantLineHandler`（行级隔离，默认关）；退役自有 MybatisPlusConfig 的重复件 |
| **neargo-common-i18n** | ✅ 复用 | `CurrencyUtil`（region→AED 默认）、`LocaleContextHolderUtil`（en/ar/zh），正好 MENA |
| **neargo-common-mq** | ✅ 复用 | `DomainEvent`/`DomainEventPublisher`（域事件，先 Logging 实现） |
| **neargo-common-security** | 🔷 权限对话主 | `AuthHeaders`/`TenantContext`/`OpaqueTokenAuthFilter`/session 模型——裂解 BFF 时接入；本单体先保留双链 |
| **neargo-auth-core** | 🔷 权限对话主 | `Credential`/`TokenIssuer`/`OtpAuthenticator`/`RedisSessionStore` 替 powerbank 手写 `SessionStore`/`OtpService`/策略（仅 login 所属服务嵌） |
| **neargo-common-api** | 📐 仿其式 | DTO/事件是 ai-neargo 业务专有；powerbank 自建 `sharehub-common-api`（权限码/契约常量前后端同源），复用"共享契约 jar + 各 app RestClient"**模式** |
| **neargo-common-config** | ⏸ 暂不 | Nacos 外部化配置，MVP 用不上 |

**⚠️ 唯一需拍板：响应包 envelope**。neargo `Result` 是 `{code, message, data}`、`PageResult` 是 `{total, list}`；powerbank 现用 `{code, msg, data}`、`{records, total, page, size}`，且 **ops-web + cend 的 http-client 拆包已锁定后者并联调通过**。两选：

- **方案① 采用 neargo `Result`/`PageResult`（最大复用，推荐）**：controller 显式返回 `Result<T>`（neargo 无透明包裹 advice），退役 `ApiResult/PageData/ApiResponseWrapper`；改两个前端 http-client 拆包（`msg`→`message`、`records`→`list`，分页 page/size 移入 `Query`/客户端）。一次性小改，契约彻底与 neargo 一致。
- **方案② 保留自有薄 envelope**：`ApiResult/PageData` 留着（前端零改动），只复用 core 的**非 envelope** 件（IdGenerator/ErrorCode/ServerException…）+ web 的 ServerExceptionHandler 适配自有包。复用度略低但前端不动。

推荐**方案①**（贴合"尽量复用"定调；前端改动量=每端 1~2 行拆包 + 分页字段）。

### §C 与既有安全/权限组件关系

不冲突：`portal/ops` 对应 @Order2 运营端链（StaffTokenAuthFilter+RBAC+DataScope）、`portal/mp` 对应 @Order1 消费者链（ConsumerTokenAuthFilter+属主）。本方案是**包与 controller 归位**，不改安全链逻辑。`auth/`、`config/` 由权限对话主导，涉及 neargo-common-security/auth-core 接入的部分（🔷 行）交由其推进，本轮不动。

## 5. 迁移步骤（分批，低风险优先；不 big-bang）

- [ ] M1 建 `portal/{ops,mp,south}` 与 `domain/` 骨架包；**已分层的 loc/agt/dev/wo**：controller 移入 `portal/ops`，service/mapper/entity 移入 `domain/`（纯 `git mv` + 包声明）。全量测试须仍绿。
- [ ] M2 拆 `OpsController`/`TradeController` 为按资源的多 controller（§A 映射）；端点路径不变，前端零感知。
- [x] **M3 envelope 方案① 已落地并验证（2026-07-12）**：后端 `ApiResponseWrapper` 改包 neargo `Result{code,message,data}`、`PageData`→neargo `PageResult{total,list}`（新增 `common/Pages` 内存分页助手）、`GlobalExceptionHandler` 返 `Result`（保留 IllegalArgument→400/AccessDenied→403 特定映射）、退役 `ApiResult/PageData`。两前端 http-client `msg`→`message`、`PageResult.records`→`list`、22 处 `.records`→`.list`、mock 同改。**验证**：后端 26 测试绿；ops-web 实机(USE_MOCK=0)登录→设备页渲染 48 柜机(共48条/分页1/5)，证明前端正确消费新 envelope。`neargo-common-core` 本已在依赖，无需新增。
- [ ] M4 C端 `portal/mp`：路径对齐 README（`/mp/user/login`…），补 `Nearby/Rent/Report` controller（依赖 trade/finance 域四件套，联动 Task #15）。
- [~] **M5 common 复用（core 部分已落地并验证，2026-07-12）**：
  - ✅ 主键统一 **自增 Long 物理主键**（用户定调）：新建 `common/BaseEntity`（`@TableId(AUTO) Long id` + `tenant_id` + `createdAt/updatedAt` + `@Version` + `@TableLogic Integer deleted`），`agt/dev/wo` 实体继承去重。
  - ✅ 业务键 `<x>No` 复用 **neargo `IdGenerator.next(prefix)`**（`AgentServiceImpl` 弃手拼 `AG%03d`）。实测：无 agentNo 建代理商 → `agent_no=AGMRHXGRIZ1M362YAA`（IdGenerator）、`id=13`（自增），26 测试绿。
  - ⚠️ **不能直接继承 neargo `BaseEntity`**：其 `region_id` 列 powerbank 表无、`deleted` 为 boolean、审计靠 `AuditMetaObjectHandler`（会填 region_id 且需注册进并行会话的 MybatisPlusConfig）。故 powerbank 自建 BaseEntity 镜像其形状、适配 `tenant_id`（隔离键差异见 §6）。
  - [ ] 待续：审计自动填充（`MetaObjectHandler`，需协调 MybatisPlusConfig）；`@OperateLog` 审计切面（引 neargo-common-web，补 Task #11）；loc/usr/iam 实体择机同步继承 BaseEntity（现属其他会话，避撞车）。
- [ ] M6 全量测试回归 + ops-web/cend 联调。

## 6. 风险与注意

- **前端契约**（方案①）：改 ops-web/cend http-client 拆包——outward-facing，需一并回归两前端。
- **并行会话**：`auth/`、`config/` 及 neargo-common-security/auth-core 接入由权限对话主导，M 步涉及处需协调，避免撞车。
- **隔离键差异**：neargo `BaseEntity` 用 `regionId`+按域 `merchantId`（ADR-010 去 tenantId），powerbank 用 `tenant_id=MAIN`（ADR-007）。复用 BaseEntity 审计字段可行，但**隔离键保持 powerbank 语义**（`tenant_id` 列不动，或映射 merchantId），此点单列不阻塞。
- **更名叠加**：与 ADR-014（powerbank→sharehub）择机一并做，减少两次大改。

## 7. 待确认（开工前）

1. **envelope 决策**：方案①（采用 neargo Result/PageResult，改前端）还是 方案②（保留自有薄包）？
2. 迁移执行时机：与 ADR-014 更名合并一次切换，还是先分端归位、更名后置？

---
确认记录：2026-07-12 用户确认——**envelope 方案①（采用 neargo `Result`/`PageResult`，改前端拆包）**；**先分端归位、ADR-014 更名后置**。进入 M1。
